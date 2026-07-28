"""client.py 단위 테스트 — 발행 정책·presence 3종·구독·Outbox.

paho 클라이언트를 가짜로 갈아끼워 브로커 없이 검증한다.
실제 소켓 위 동작(재접속·LWT·60초 단절)은 test_broker_e2e.py 가 맡는다.
"""

import json
import logging
import queue
import time

import pytest
from paho.mqtt.packettypes import PacketTypes
from paho.mqtt.reasoncodes import ReasonCode

from comms import (
    EVENT_KINDS,
    PUBLISH_KINDS,
    PUBLISH_POLICY,
    MqttClient,
    MqttConfig,
    Outbox,
    TopicKind,
    encode,
    health,
)
from comms.client import RECONNECT_MAX_DELAY_SEC, RECONNECT_MIN_DELAY_SEC

ROBOT_ID = "RBT-001"

#: 실브로커가 실제로 돌려준 거부 사유들 (MQTT5 §3.4.2.1).
NOT_AUTHORIZED = 0x87
QUOTA_EXCEEDED = 0x97


def reason(packet_type, identifier):
    """paho 가 콜백으로 넘기는 것과 같은 ReasonCode 객체."""
    return ReasonCode(packet_type, identifier=identifier)


def granted_qos(qos):
    return reason(PacketTypes.SUBACK, qos)


class FakeInfo:
    def __init__(self, mid):
        self.mid = mid
        self.rc = 0
        self.waited = False

    def wait_for_publish(self, timeout=None):
        self.waited = True


class FakeMessage:
    def __init__(self, topic, payload):
        self.topic = topic
        self.payload = payload


class FakePahoClient:
    """paho.mqtt.client.Client 중 우리가 쓰는 부분만."""

    def __init__(self):
        self.on_connect = self.on_disconnect = None
        self.on_message = self.on_publish = None
        self.will = None
        self.on_subscribe = None
        self.published = []  # (topic, payload, qos, retain, mid)
        self.infos = []  # publish 가 돌려준 FakeInfo
        self.subscriptions = []
        self.subscribe_mids = []
        self.connect_args = None
        self.reconnect_delay = None
        self.loop_started = False
        self.disconnected = False
        self._mid = 0
        self.auto_ack = True
        self.ack_reason_code = 0  # auto_ack 이 흉내 낼 PUBACK 이유코드
        self.auto_suback = True
        # 실제 브로커는 요청한 QoS 를 승인하며 ReasonCode 객체를 넘긴다.
        self.suback_reason_codes = [granted_qos(1)]

    # -- paho API --------------------------------------------------------

    def will_set(self, topic, payload=None, qos=0, retain=False, properties=None):
        self.will = (topic, payload, qos, retain)

    def reconnect_delay_set(self, min_delay=1, max_delay=120):
        self.reconnect_delay = (min_delay, max_delay)

    def connect_async(self, host, port=1883, keepalive=60, **kwargs):
        self.connect_args = dict(host=host, port=port, keepalive=keepalive, **kwargs)

    def loop_start(self):
        self.loop_started = True

    def loop_stop(self):
        self.loop_started = False

    def disconnect(self):
        self.disconnected = True

    def subscribe(self, topic, qos=0):
        self._mid += 1
        mid = self._mid
        self.subscriptions.append((topic, qos))
        self.subscribe_mids.append(mid)
        if self.auto_suback:
            self.suback(mid)
        return (0, mid)  # paho 는 (rc, mid) 를 돌려준다

    def publish(self, topic, payload=None, qos=0, retain=False, properties=None):
        self._mid += 1
        self.published.append((topic, payload, qos, retain, self._mid))
        info = FakeInfo(self._mid)
        self.infos.append(info)
        if self.auto_ack and qos > 0:
            self.ack(self._mid, self.ack_reason_code)
        elif self.auto_ack:
            # QoS 0 은 paho 가 성공 이유코드로 즉시 콜백을 부른다.
            self.ack(self._mid, 0)
        return info

    # -- 테스트 조작 ------------------------------------------------------

    def ack(self, mid, reason_code=0):
        """PUBACK. reason_code 로 브로커의 거부를 흉내 낼 수 있다."""
        self.on_publish(self, None, mid, reason_code, None)

    def suback(self, mid=None, reason_codes=None):
        if mid is None:
            mid = self.subscribe_mids[-1]
        if reason_codes is None:
            reason_codes = self.suback_reason_codes
        self.on_subscribe(self, None, mid, list(reason_codes), None)

    def simulate_connect(self):
        self.on_connect(self, None, {}, 0, None)

    def simulate_disconnect(self, reason_code=0, *, version2=False):
        """version2=True 면 paho 2.x 모양(flags, reason_code, properties)으로 부른다."""
        if version2:
            self.on_disconnect(self, None, {}, reason_code, None)
        else:
            self.on_disconnect(self, None, reason_code)

    def deliver(self, topic, payload):
        self.on_message(self, None, FakeMessage(topic, payload))

    def payloads_on(self, topic):
        return [json.loads(p) for t, p, _, _, _ in self.published if t == topic]

    def last(self):
        return self.published[-1]


@pytest.fixture
def config(tmp_path):
    return MqttConfig(
        robot_id=ROBOT_ID,
        host="broker.example.com",
        port=8883,
        username=ROBOT_ID,
        password="secret",
        keepalive=30,
        outbox_path=str(tmp_path / "outbox.sqlite3"),
    )


@pytest.fixture
def paho():
    return FakePahoClient()


@pytest.fixture
def client(config, paho):
    instance = MqttClient(
        config,
        client_factory=lambda _config: paho,
        resend_interval_sec=0.05,
    )
    yield instance
    instance.outbox.close()


def incident(client, **overrides):
    kwargs = dict(
        occurred_at="2026-07-26T05:15:34.231Z",
        mission_id="MIS-1",
        location_valid=True,
        lat=37.4979,
        lon=127.0276,
        zone_id="A-01",
        block_type="LINEAR",
        confidence=0.9,
        image_id=None,
        captured_at="2026-07-26T05:15:34.120Z",
        image_width=1280,
        image_height=720,
        bbox_norm=[0.1, 0.2, 0.3, 0.4],
        model_name="tbs-braille-yolo",
        model_version="1.0.0",
    )
    kwargs.update(overrides)
    return client.messages.incident_event(**kwargs)


def sample_state(client):
    return client.messages.state(
        mode="PATROLLING",
        reason_code="NONE",
        battery_valid=True,
        battery_percent=50.0,
        battery_state="NORMAL",
        health_block=health(),
    )


# --------------------------------------------------------------------------
# 발행 정책 (계약 §3.3, §16.3)
# --------------------------------------------------------------------------


def test_policy_table_covers_every_publish_topic():
    assert set(PUBLISH_POLICY) == set(PUBLISH_KINDS)


def test_policy_values():
    def policy(kind):
        return (PUBLISH_POLICY[kind].qos, PUBLISH_POLICY[kind].retain)

    assert policy(TopicKind.PRESENCE) == (1, True)
    assert policy(TopicKind.STATE) == (1, True)
    assert policy(TopicKind.TELEMETRY) == (0, False)
    assert policy(TopicKind.COMMAND_RESULT) == (1, False)
    for kind in EVENT_KINDS:
        assert policy(kind) == (1, False)


def test_only_presence_and_state_retain():
    retained = {kind for kind, policy in PUBLISH_POLICY.items() if policy.retain}
    assert retained == {TopicKind.PRESENCE, TopicKind.STATE}


def test_publish_uses_policy(client, paho):
    paho.simulate_connect()
    client.publish(TopicKind.STATE, sample_state(client))
    topic, _, qos, retain, _ = paho.last()
    assert (topic, qos, retain) == ("tbs/v2/robots/RBT-001/state", 1, True)

    client.publish(
        TopicKind.TELEMETRY,
        client.messages.telemetry(
            position_valid=False,
            moving=False,
            battery_valid=False,
            battery_percent=None,
            battery_state="UNKNOWN",
        ),
    )
    topic, _, qos, retain, _ = paho.last()
    assert (topic, qos, retain) == ("tbs/v2/robots/RBT-001/telemetry", 0, False)


def test_publish_does_not_wait_by_default(client, paho):
    paho.simulate_connect()
    paho.infos.clear()
    client.publish(TopicKind.STATE, sample_state(client))
    assert [info.waited for info in paho.infos] == [False]


def test_publish_can_wait_for_the_socket(client, paho):
    """종료 직전처럼 곧바로 disconnect 가 뒤따르는 경로에서만 쓴다."""
    paho.simulate_connect()
    paho.infos.clear()
    assert client.publish(TopicKind.STATE, sample_state(client), wait_sec=1.5) is True
    assert [info.waited for info in paho.infos] == [True]


def test_publish_rejects_subscribe_only_topic(client):
    with pytest.raises(ValueError):
        client.publish(TopicKind.COMMAND_REQUEST, {})


# --------------------------------------------------------------------------
# 접속 · presence 3종 (DoD 1, 2, 3)
# --------------------------------------------------------------------------


def test_start_registers_lwt_before_connecting(client, paho, config):
    client.start()
    topic, payload, qos, retain = paho.will
    assert topic == "tbs/v2/robots/RBT-001/presence"
    assert (qos, retain) == (1, True)
    assert json.loads(payload) == {
        "schema_version": "2.0",
        "robot_id": ROBOT_ID,
        "boot_id": client.messages.boot_id,
        "status": "OFFLINE",
        "reason": "UNGRACEFUL_DISCONNECT",
    }
    assert paho.connect_args["host"] == config.host
    assert paho.connect_args["port"] == 8883
    assert paho.connect_args["keepalive"] == 30
    assert paho.loop_started
    client.shutdown()


def test_backoff_is_1_to_30_seconds(client, paho):
    client.start()
    assert paho.reconnect_delay == (RECONNECT_MIN_DELAY_SEC, RECONNECT_MAX_DELAY_SEC)
    assert paho.reconnect_delay == (1, 30)
    client.shutdown()


def test_birth_publishes_online_and_subscribes(client, paho):
    paho.simulate_connect()
    topic, payload, qos, retain, _ = paho.published[0]
    assert topic == "tbs/v2/robots/RBT-001/presence"
    assert (qos, retain) == (1, True)
    body = json.loads(payload)
    assert body["status"] == "ONLINE"
    assert body["connected_at"]
    assert "reason" not in body
    assert paho.subscriptions == [("tbs/v2/robots/RBT-001/commands/request", 1)]
    assert client.connected


def test_reconnect_republishes_online(client, paho):
    paho.simulate_connect()
    paho.simulate_disconnect()
    assert not client.connected
    paho.simulate_connect()
    online = [
        p
        for p in paho.payloads_on("tbs/v2/robots/RBT-001/presence")
        if p["status"] == "ONLINE"
    ]
    assert len(online) == 2
    assert paho.subscriptions[-1] == ("tbs/v2/robots/RBT-001/commands/request", 1)


def test_shutdown_publishes_graceful_then_disconnects(client, paho):
    paho.simulate_connect()
    client.shutdown()
    topic, payload, qos, retain, _ = paho.last()
    assert topic == "tbs/v2/robots/RBT-001/presence"
    assert (qos, retain) == (1, True)
    assert json.loads(payload) == {
        "schema_version": "2.0",
        "robot_id": ROBOT_ID,
        "boot_id": client.messages.boot_id,
        "status": "OFFLINE",
        "reason": "GRACEFUL_SHUTDOWN",
    }
    assert paho.disconnected
    assert not paho.loop_started


def test_shutdown_while_disconnected_does_not_raise(client, paho):
    client.start()
    client.shutdown()
    assert paho.disconnected


# --------------------------------------------------------------------------
# 구독 (commands/request)
# --------------------------------------------------------------------------


def test_command_is_pushed_to_queue(client, paho):
    paho.simulate_connect()
    command = {
        "schema_version": "2.0",
        "command_id": "a75d9cec-b04d-4f66-b18a-4c48782f805a",
        "command_type": "START_MISSION",
        "issued_at": "2026-07-26T05:30:00.000Z",
        "expires_at": "2026-07-26T05:31:00.000Z",
        "requested_by": {"actor_type": "ADMIN", "actor_id": "ADM-0042"},
        "parameters": {"mission_id": "MIS-20260726-001", "zone_id": "A-01"},
    }
    paho.deliver(client.topics.of(TopicKind.COMMAND_REQUEST), encode(command))
    assert client.commands.get_nowait() == command
    # 실행은 main.py 몫 — 클라이언트는 해석하지 않는다.
    assert client.commands.empty()


def test_malformed_command_is_dropped(client, paho):
    paho.simulate_connect()
    paho.deliver(client.topics.of(TopicKind.COMMAND_REQUEST), b"{not json")
    paho.deliver(client.topics.of(TopicKind.COMMAND_REQUEST), b'"just a string"')
    assert client.commands.empty()


def test_a_dropped_command_leaves_the_payload_in_the_log(client, paho, caplog):
    """무엇이 잘못됐는지 로그만 보고 알 수 있어야 한다.

    서버가 JSON 대신 Map.toString() 을 보낸 적이 있다. 그때 로그에 이유가 없어서
    브로커부터 다시 파야 했다.
    """
    paho.simulate_connect()
    # 따옴표 없는 실제 사례 — 키도 문자열 값도 인용되지 않았다.
    paho.deliver(
        client.topics.of(TopicKind.COMMAND_REQUEST),
        b"{schema_version:2.0,command_type:START_MISSION}",
    )
    assert client.commands.empty()
    assert "schema_version:2.0" in caplog.text


def test_message_on_other_topic_is_ignored(client, paho):
    paho.simulate_connect()
    paho.deliver("tbs/v2/robots/RBT-999/commands/request", encode({"a": 1}))
    assert client.commands.empty()


def test_external_command_queue_is_used(config, paho):
    shared = queue.Queue()
    client = MqttClient(config, client_factory=lambda _c: paho, command_queue=shared)
    try:
        paho.simulate_connect()
        paho.deliver(client.topics.of(TopicKind.COMMAND_REQUEST), encode({"command_id": "x"}))
        assert shared.get_nowait() == {"command_id": "x"}
    finally:
        client.outbox.close()


# --------------------------------------------------------------------------
# Outbox (DoD 6)
# --------------------------------------------------------------------------


def test_event_is_stored_then_published(client, paho):
    paho.auto_ack = False
    paho.simulate_connect()
    payload = incident(client)
    client.publish(TopicKind.EVENT_INCIDENT, payload)

    topic, raw, qos, retain, mid = paho.last()
    assert (topic, qos, retain) == ("tbs/v2/robots/RBT-001/events/incident", 1, False)
    # PUBACK 전에는 pending 이다.
    assert client.outbox.pending_count() == 1
    paho.ack(mid)
    assert client.outbox.pending_count() == 0


def test_event_stays_pending_without_puback(client, paho):
    paho.auto_ack = False
    paho.simulate_connect()
    client.publish(TopicKind.EVENT_SAFETY, incident(client))
    assert client.outbox.pending_count() == 1


def test_offline_event_is_queued_not_published(client, paho):
    payload = incident(client)
    assert client.publish(TopicKind.EVENT_INCIDENT, payload) is False
    assert paho.published == []
    assert client.outbox.pending_count() == 1


def test_reconnect_resends_with_same_event_id(client, paho):
    paho.auto_ack = False
    stored = incident(client)
    client.publish(TopicKind.EVENT_INCIDENT, stored)
    assert paho.published == []

    paho.simulate_connect()
    client.flush_outbox()

    sent = paho.payloads_on("tbs/v2/robots/RBT-001/events/incident")
    assert len(sent) == 1
    resent = sent[0]
    # event_id·occurred_at 은 유지, sent_at 만 갱신.
    assert resent["event_id"] == stored["event_id"]
    assert resent["occurred_at"] == stored["occurred_at"]
    assert resent["seq"] == stored["seq"]
    assert resent["boot_id"] == stored["boot_id"]
    assert resent["sent_at"] >= stored["sent_at"]
    assert {k: v for k, v in resent.items() if k != "sent_at"} == {
        k: v for k, v in stored.items() if k != "sent_at"
    }

    paho.ack(paho.last()[4])
    assert client.outbox.pending_count() == 0


def test_flush_preserves_order(client, paho):
    paho.auto_ack = False
    first = incident(client, occurred_at="2026-07-26T05:00:00.000Z")
    second = incident(client, occurred_at="2026-07-26T05:00:01.000Z")
    client.publish(TopicKind.EVENT_INCIDENT, first)
    client.publish(TopicKind.EVENT_INCIDENT, second)

    paho.simulate_connect()
    client.flush_outbox()
    sent = paho.payloads_on("tbs/v2/robots/RBT-001/events/incident")
    assert [p["event_id"] for p in sent] == [first["event_id"], second["event_id"]]


def test_duplicate_event_id_is_stored_once(client, paho):
    payload = incident(client)
    client.publish(TopicKind.EVENT_INCIDENT, payload)
    client.publish(TopicKind.EVENT_INCIDENT, payload)
    assert client.outbox.pending_count() == 1


def test_event_without_event_id_is_rejected(client, paho):
    paho.simulate_connect()
    with pytest.raises(ValueError):
        client.publish(TopicKind.EVENT_MISSION, {"schema_version": "2.0"})


def test_non_event_publish_does_not_touch_outbox(client, paho):
    paho.simulate_connect()
    client.publish(TopicKind.STATE, sample_state(client))
    client.publish(
        TopicKind.COMMAND_RESULT,
        client.messages.command_result(
            command_id="cmd-1",
            status="ACCEPTED",
            received_at="2026-07-26T05:21:00.130Z",
            reason_code="OK",
            mode="PATROLLING",
            state_reason_code="NONE",
        ),
    )
    assert client.outbox.pending_count() == 0


def test_puback_arriving_before_registration_still_marks_sent(client, paho):
    """paho 는 publish() 반환 전에 on_publish 를 부를 수 있다."""
    paho.auto_ack = True  # publish() 안에서 즉시 ack
    paho.simulate_connect()
    client.publish(TopicKind.EVENT_BATTERY, incident(client))
    assert client.outbox.pending_count() == 0


def test_disconnect_clears_inflight_but_keeps_pending(client, paho):
    paho.auto_ack = False
    paho.simulate_connect()
    client.publish(TopicKind.EVENT_INCIDENT, incident(client))
    mid = paho.last()[4]
    paho.simulate_disconnect()
    paho.ack(mid)  # 뒤늦은 PUBACK 은 무시된다
    assert client.outbox.pending_count() == 1


def test_outbox_survives_process_restart(config, paho):
    first = MqttClient(config, client_factory=lambda _c: paho)
    first.publish(TopicKind.EVENT_INCIDENT, incident(first))
    stored_id = first.outbox.pending(limit=1)[0].event_id
    first.outbox.close()

    # 같은 경로로 다시 연 Outbox 에 그대로 남아 있어야 한다.
    reopened = Outbox(config.outbox_path)
    try:
        records = reopened.pending()
        assert [r.event_id for r in records] == [stored_id]
        assert records[0].kind == TopicKind.EVENT_INCIDENT
    finally:
        reopened.close()


def test_prune_sent_keeps_pending(client, paho):
    paho.simulate_connect()
    client.publish(TopicKind.EVENT_INCIDENT, incident(client))  # auto ack → sent
    paho.auto_ack = False
    client.publish(TopicKind.EVENT_SAFETY, incident(client))
    assert client.outbox.pending_count() == 1
    client.outbox.prune_sent(before="2099-01-01T00:00:00.000Z")
    assert client.outbox.pending_count() == 1


# --------------------------------------------------------------------------
# PUBACK 이유코드 — 브로커가 거부한 event 는 Outbox 에 남아야 한다
#
# 실제로 겪은 일: commands/request 에 쓰기 권한이 없어 PUBACK 이
# 0x87 Not authorized 로 돌아왔는데도 event 가 sent 로 지워졌다.
# --------------------------------------------------------------------------


def test_rejected_puback_keeps_event_pending(client, paho):
    paho.auto_ack = False
    paho.simulate_connect()
    client.publish(TopicKind.EVENT_INCIDENT, incident(client))
    mid = paho.last()[4]

    paho.ack(mid, reason(PacketTypes.PUBACK, NOT_AUTHORIZED))

    # 유실이 아니라 재전송 대상으로 남는다.
    assert client.outbox.pending_count() == 1


def test_rejected_puback_is_logged_with_reason_and_event_id(client, paho, caplog):
    """조용히 재시도만 돌면 ACL 오설정 같은 영구 거부를 아무도 못 본다."""
    paho.auto_ack = False
    paho.simulate_connect()
    payload = incident(client)
    client.publish(TopicKind.EVENT_INCIDENT, payload)

    with caplog.at_level(logging.ERROR):
        paho.ack(paho.last()[4], reason(PacketTypes.PUBACK, NOT_AUTHORIZED))

    errors = [r for r in caplog.records if r.levelno >= logging.ERROR]
    assert errors, "브로커 거부는 error 로 남아야 한다"
    assert "Not authorized" in caplog.text
    assert payload["event_id"] in caplog.text


def test_rejected_puback_is_detected_from_plain_int_code(client, paho):
    """이유코드가 ReasonCode 가 아니라 int 로 와도 실패로 본다."""
    paho.auto_ack = False
    paho.simulate_connect()
    client.publish(TopicKind.EVENT_SAFETY, incident(client))
    paho.ack(paho.last()[4], QUOTA_EXCEEDED)
    assert client.outbox.pending_count() == 1


def test_rejected_event_is_resent_by_flush(client, paho):
    paho.auto_ack = False
    paho.simulate_connect()
    payload = incident(client)
    client.publish(TopicKind.EVENT_INCIDENT, payload)
    paho.ack(paho.last()[4], reason(PacketTypes.PUBACK, NOT_AUTHORIZED))

    # 다음 재전송 주기에 같은 event_id 로 다시 나간다.
    # (fixture 의 주기는 0.05초 — 방금 시도한 건은 그 전에는 다시 안 나간다.)
    assert client.flush_outbox() == 0
    time.sleep(0.1)
    assert client.flush_outbox() == 1
    resent = paho.payloads_on("tbs/v2/robots/RBT-001/events/incident")
    assert [p["event_id"] for p in resent] == [payload["event_id"]] * 2

    paho.ack(paho.last()[4])  # 권한이 복구되면 그때 sent
    assert client.outbox.pending_count() == 0


def test_rejected_puback_arriving_before_registration_is_not_sent(client, paho):
    """PUBACK 이 publish() 반환보다 빠르고 그게 실패인 경우.

    실패한 mid 를 early ack 로 기억하면 _send_stored 가 그걸 보고 sent 로 지운다.
    """
    paho.auto_ack = True  # publish() 안에서 즉시 ack
    paho.ack_reason_code = reason(PacketTypes.PUBACK, NOT_AUTHORIZED)
    paho.simulate_connect()
    client.publish(TopicKind.EVENT_BATTERY, incident(client))
    assert client.outbox.pending_count() == 1


def test_successful_puback_still_marks_sent(client, paho):
    """회귀 방지 — 성공 이유코드는 예전과 똑같이 동작한다."""
    paho.auto_ack = False
    paho.simulate_connect()
    client.publish(TopicKind.EVENT_MISSION, incident(client))
    paho.ack(paho.last()[4], 0)
    assert client.outbox.pending_count() == 0


def test_qos0_telemetry_is_unaffected(client, paho):
    """QoS 0 은 paho 가 성공 이유코드로 즉시 콜백을 부른다 — Outbox 와 무관하다."""
    paho.simulate_connect()
    client.publish(
        TopicKind.TELEMETRY,
        client.messages.telemetry(
            position_valid=False,
            moving=False,
            battery_valid=False,
            battery_percent=None,
            battery_state="UNKNOWN",
        ),
    )
    assert client.outbox.pending_count() == 0


# --------------------------------------------------------------------------
# SUBACK — 구독 거부를 감지한다
#
# ACL 이 읽기를 막으면 "멀쩡해 보이는데 서버 명령을 영원히 못 받는 로봇" 이 된다.
# --------------------------------------------------------------------------


def test_granted_subscription_is_recorded(client, paho):
    paho.simulate_connect()
    assert client.subscribed is True


def test_refused_subscription_is_logged(client, paho, caplog):
    paho.auto_suback = False
    paho.simulate_connect()

    with caplog.at_level(logging.ERROR):
        paho.suback(reason_codes=[reason(PacketTypes.SUBACK, NOT_AUTHORIZED)])

    errors = [r for r in caplog.records if r.levelno >= logging.ERROR]
    assert errors, "구독 거부는 error 로 남아야 한다"
    assert "Not authorized" in caplog.text
    # 어느 topic 이 막혔는지 알 수 있어야 한다.
    assert "tbs/v2/robots/RBT-001/commands/request" in caplog.text


def test_refused_subscription_is_visible_on_the_client(client, paho):
    """진단용 — 계약 payload 에는 싣지 않는다."""
    paho.auto_suback = False
    paho.simulate_connect()
    assert client.subscribed is None  # SUBACK 전에는 아직 모른다
    paho.suback(reason_codes=[reason(PacketTypes.SUBACK, NOT_AUTHORIZED)])
    assert client.subscribed is False
    assert [str(code) for code in client.subscribe_reason_codes] == ["Not authorized"]


def test_reconnect_resets_subscription_verdict(client, paho):
    paho.auto_suback = False
    paho.simulate_connect()
    paho.suback(reason_codes=[reason(PacketTypes.SUBACK, NOT_AUTHORIZED)])
    assert client.subscribed is False

    paho.simulate_disconnect()
    paho.simulate_connect()
    assert client.subscribed is None
    paho.suback()
    assert client.subscribed is True


def test_suback_arriving_before_subscribe_returns_still_names_the_topic(
    client, paho, caplog
):
    """SUBACK 이 subscribe() 반환보다 빨라도 topic 을 알 수 있어야 한다."""
    paho.auto_suback = True  # subscribe() 안에서 즉시 SUBACK
    paho.suback_reason_codes = [reason(PacketTypes.SUBACK, NOT_AUTHORIZED)]
    with caplog.at_level(logging.ERROR):
        paho.simulate_connect()
    assert "tbs/v2/robots/RBT-001/commands/request" in caplog.text
    assert client.subscribed is False


# --------------------------------------------------------------------------
# 끊김 이유코드
#
# 브로커 로그에는 "session taken over" 가 반복됐는데 우리 로그에는 단서가 없었다.
# --------------------------------------------------------------------------


def test_disconnect_reason_is_logged(client, paho, caplog):
    paho.simulate_connect()
    with caplog.at_level(logging.WARNING):
        paho.simulate_disconnect(
            reason(PacketTypes.DISCONNECT, 0x8E), version2=True  # Session taken over
        )
    assert "Session taken over" in caplog.text
    assert not client.connected


def test_disconnect_reason_is_logged_with_legacy_callback_shape(client, paho, caplog):
    """가짜 paho 의 예전 시그니처 (client, userdata, rc) 도 견딘다."""
    paho.simulate_connect()
    with caplog.at_level(logging.WARNING):
        paho.simulate_disconnect(142)
    assert "142" in caplog.text


def test_graceful_shutdown_does_not_log_a_disconnect_warning(client, paho, caplog):
    paho.simulate_connect()
    client.shutdown()
    with caplog.at_level(logging.WARNING):
        paho.simulate_disconnect(reason(PacketTypes.DISCONNECT, 0), version2=True)
    warnings = [r for r in caplog.records if r.levelno >= logging.WARNING]
    assert warnings == []
