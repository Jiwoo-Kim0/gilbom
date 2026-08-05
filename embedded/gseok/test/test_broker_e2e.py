"""실제 TCP·실제 paho 로 도는 접속 수명 테스트. (DoD 1, 2, 3, 6)

브로커는 test/fake_broker.py (MQTT 5 최소 구현). mosquitto 가 있으면
`TBS_BROKER=host:port` 로 실 브로커를 대신 쓸 수 있다 — 단 그 경우
브로커 재시작 시나리오(DoD 1)는 수동으로 재시작해야 하므로 건너뛴다.

브로커 단절 시간은 기본 3초다. DoD 그대로 60초로 돌리려면:
    TBS_DOWNTIME_SEC=60 python -m pytest test/test_broker_e2e.py -q
"""

import json
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest

from comms import MqttClient, MqttConfig, TopicKind, encode
from fake_broker import FakeBroker

ROBOT_ID = "RBT-001"
PRESENCE = f"tbs/v2/robots/{ROBOT_ID}/presence"
INCIDENT = f"tbs/v2/robots/{ROBOT_ID}/events/incident"
COMMANDS = f"tbs/v2/robots/{ROBOT_ID}/commands/request"
RESULTS = f"tbs/v2/robots/{ROBOT_ID}/commands/result"

DOWNTIME_SEC = float(os.environ.get("TBS_DOWNTIME_SEC", "3"))
#: 재접속 백오프는 최대 30초까지 늘어난다 — 그만큼 기다려 준다.
RECONNECT_TIMEOUT = 45.0


@pytest.fixture
def broker():
    instance = FakeBroker().start()
    yield instance
    instance.stop()


@pytest.fixture
def client(broker, tmp_path):
    instance = MqttClient(
        MqttConfig(
            robot_id=ROBOT_ID,
            host=broker.host,
            port=broker.port,
            keepalive=30,
            outbox_path=str(tmp_path / "outbox.sqlite3"),
        ),
        resend_interval_sec=1.0,
    )
    yield instance
    try:
        instance.shutdown(timeout=1.0)
    except Exception:
        pass


def presence_of(broker, status, reason=None):
    def predicate(records):
        hits = []
        for record in records:
            if record.topic != PRESENCE:
                continue
            body = json.loads(record.payload)
            if body["status"] != status:
                continue
            if reason is not None and body.get("reason") != reason:
                continue
            hits.append((record, body))
        return hits or None

    return predicate


def incidents(broker, count):
    def predicate(records):
        hits = [r for r in records if r.topic == INCIDENT]
        return hits if len(hits) >= count else None

    return predicate


def results(broker, count):
    def predicate(records):
        hits = [r for r in records if r.topic == RESULTS]
        return hits if len(hits) >= count else None

    return predicate


def make_incident(client, occurred_at):
    return client.messages.incident_event(
        occurred_at=occurred_at,
        mission_id="MIS-20260726-001",
        location_valid=True,
        lat=37.4979,
        lon=127.0276,
        zone_id="A-01",
        block_type="LINEAR",
        confidence=0.9,
        image_id=None,
        captured_at=occurred_at,
        image_width=1280,
        image_height=720,
        bbox_norm=[0.1, 0.2, 0.3, 0.4],
        model_name="tbs-braille-yolo",
        model_version="1.0.0",
    )


# --------------------------------------------------------------------------


def test_connect_publishes_retained_online_presence(broker, client):
    client.start()
    assert client.wait_until_connected(timeout=5)
    hits = broker.wait_for(presence_of(broker, "ONLINE"))
    assert hits, "birth presence was not published"
    record, body = hits[0]
    assert (record.qos, record.retain) == (1, True)
    assert body["schema_version"] == "2.0"
    assert body["mqtt_id"] == ROBOT_ID
    assert body["boot_id"] == client.messages.boot_id
    assert body["connected_at"]


def test_broker_restart_reconnects_and_republishes_online(broker, client):
    """DoD 1 — 브로커 재시작 후 자동 재연결 + presence ONLINE 재발행."""
    client.start()
    assert client.wait_until_connected(timeout=5)
    assert broker.wait_for(presence_of(broker, "ONLINE"))
    port = broker.port

    broker.stop()
    time.sleep(DOWNTIME_SEC)
    broker.clear()
    broker.port = port
    broker.start()

    hits = broker.wait_for(presence_of(broker, "ONLINE"), timeout=RECONNECT_TIMEOUT)
    assert hits, f"no reconnect within {RECONNECT_TIMEOUT}s"
    record, body = hits[0]
    assert (record.qos, record.retain) == (1, True)
    # 같은 부팅 세션이므로 boot_id 는 그대로다.
    assert body["boot_id"] == client.messages.boot_id
    assert client.connected
    # 재접속 후 명령 구독도 다시 걸려 있어야 한다.
    assert broker.wait_for_subscription(COMMANDS, timeout=5)
    assert broker.send_to(COMMANDS, encode({"command_id": "c-1"}))


def test_graceful_shutdown_publishes_reason(broker, client):
    """DoD 3 — 정상 종료 시 GRACEFUL_SHUTDOWN, LWT 는 발행되지 않는다."""
    client.start()
    assert client.wait_until_connected(timeout=5)
    assert broker.wait_for(presence_of(broker, "ONLINE"))

    client.shutdown(timeout=2.0)

    hits = broker.wait_for(presence_of(broker, "OFFLINE", "GRACEFUL_SHUTDOWN"))
    assert hits, "graceful presence was not published"
    record, _ = hits[0]
    assert (record.qos, record.retain) == (1, True)
    assert record.is_will is False
    time.sleep(0.5)
    assert broker.wait_for(
        presence_of(broker, "OFFLINE", "UNGRACEFUL_DISCONNECT"), timeout=0.5
    ) is None


def test_ungraceful_kill_triggers_lwt(broker, tmp_path):
    """DoD 2 — 프로세스 강제 kill 시 브로커가 UNGRACEFUL_DISCONNECT LWT 를 발행."""
    script = Path(__file__).resolve().parent / "_robot_process.py"
    child = subprocess.Popen(
        [
            sys.executable,
            str(script),
            broker.host,
            str(broker.port),
            ROBOT_ID,
            str(tmp_path / "child-outbox.sqlite3"),
        ],
        stdout=subprocess.PIPE,
        text=True,
    )
    try:
        line = child.stdout.readline().strip()
        assert line == "READY", f"child failed to connect: {line!r}"
        assert broker.wait_for(presence_of(broker, "ONLINE"))
        child.kill()  # DISCONNECT 를 보낼 틈 없이 즉사
        hits = broker.wait_for(
            presence_of(broker, "OFFLINE", "UNGRACEFUL_DISCONNECT"), timeout=10
        )
        assert hits, "broker did not publish the will"
        record, body = hits[0]
        assert record.is_will is True
        assert (record.qos, record.retain) == (1, True)
        assert body["mqtt_id"] == ROBOT_ID
        assert body["schema_version"] == "2.0"
    finally:
        child.kill()
        child.wait(timeout=5)


def test_outbox_keeps_events_across_broker_outage(broker, client):
    """DoD 6 — 브로커 다운 중 발생한 event 가 복구 후 event_id 그대로 도착한다."""
    client.start()
    assert client.wait_until_connected(timeout=5)
    assert broker.wait_for(presence_of(broker, "ONLINE"))
    port = broker.port

    broker.stop()
    time.sleep(0.3)  # on_disconnect 반영
    originals = [
        make_incident(client, f"2026-07-26T05:1{index}:00.000Z") for index in range(3)
    ]
    for payload in originals:
        client.publish(TopicKind.EVENT_INCIDENT, payload)
    assert client.outbox.pending_count() == 3

    time.sleep(DOWNTIME_SEC)
    broker.clear()
    broker.port = port
    broker.start()

    hits = broker.wait_for(incidents(broker, 3), timeout=RECONNECT_TIMEOUT)
    assert hits, f"events were lost, only {len(broker.messages_on(INCIDENT))} arrived"
    received = [json.loads(record.payload) for record in hits]

    assert [p["event_id"] for p in received] == [p["event_id"] for p in originals]
    assert [p["occurred_at"] for p in received] == [p["occurred_at"] for p in originals]
    assert [p["seq"] for p in received] == [p["seq"] for p in originals]
    assert [p["boot_id"] for p in received] == [p["boot_id"] for p in originals]
    # sent_at 만 갱신된다.
    for sent, original in zip(received, originals):
        assert sent["sent_at"] >= original["sent_at"]
        assert {k: v for k, v in sent.items() if k != "sent_at"} == {
            k: v for k, v in original.items() if k != "sent_at"
        }
    for record in hits:
        assert (record.qos, record.retain) == (1, False)

    # PUBACK 을 받았으니 Outbox 가 비어야 한다.
    deadline = time.monotonic() + 5
    while client.outbox.pending_count() and time.monotonic() < deadline:
        time.sleep(0.1)
    assert client.outbox.pending_count() == 0


def test_command_request_reaches_the_queue(broker, client):
    client.start()
    assert client.wait_until_connected(timeout=5)
    assert broker.wait_for_subscription(COMMANDS, timeout=5)

    command = {
        "schema_version": "2.0",
        "command_id": "a75d9cec-b04d-4f66-b18a-4c48782f805a",
        "command_type": "START_MISSION",
        "issued_at": "2026-07-26T05:30:00.000Z",
        "expires_at": "2026-07-26T05:31:00.000Z",
        "requested_by": {"actor_type": "ADMIN", "actor_id": "ADM-0042"},
        "parameters": {"mission_id": "MIS-20260726-001", "zone_id": "A-01"},
    }
    assert broker.send_to(COMMANDS, encode(command))
    assert client.commands.get(timeout=5) == command


def emergency_stop(command_id="95626222-c5c8-47ea-901e-f9e467b275f6"):
    return {
        "schema_version": "2.0",
        "command_id": command_id,
        "command_type": "EMERGENCY_STOP",
        "issued_at": "2026-07-26T05:21:00.000Z",
        # 실시계로 도는 테스트다 — 만료로 걸리지 않게 넉넉히 잡는다.
        "expires_at": "2030-01-01T00:00:00.000Z",
        "requested_by": {"actor_type": "ADMIN", "actor_id": "ADM-0042"},
        "parameters": {"reason": "operator_request"},
    }


def drain(runner, timeout=5.0):
    """paho 네트워크 스레드가 큐를 채울 때까지 기다렸다 비운다."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if runner.poll():
            return True
        time.sleep(0.05)
    return False


def test_command_round_trip_publishes_two_results(broker, client):
    """서버 → commands/request → 실행 → commands/result 왕복."""
    from commands import CommandRunner
    from events import EventPublisher
    from robot_state import RobotState

    client.start()
    assert client.wait_until_connected(timeout=5)
    assert broker.wait_for_subscription(COMMANDS, timeout=5)

    state = RobotState()
    state.boot_completed()
    runner = CommandRunner(client, state, EventPublisher(client, state))

    command = emergency_stop()
    assert broker.send_to(COMMANDS, encode(command))
    assert drain(runner), "command never reached the queue"

    hits = broker.wait_for(results(broker, 2), timeout=5)
    assert hits, "no command result arrived"
    bodies = [json.loads(record.payload) for record in hits]
    assert [b["status"] for b in bodies] == ["ACCEPTED", "COMPLETED"]
    assert {b["command_id"] for b in bodies} == {command["command_id"]}
    assert len({b["result_id"] for b in bodies}) == 2
    assert bodies[0]["completed_at"] is None
    # 정지가 먼저다 — 첫 결과가 이미 정지 후 상태를 싣고 있다 (§16-10).
    assert bodies[0]["state"] == {"mode": "SAFE_STOP", "reason_code": "REMOTE_ESTOP"}
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")
    for record in hits:
        assert (record.qos, record.retain) == (1, False)


def test_a_redelivered_command_is_executed_once(broker, client):
    from commands import CommandRunner
    from events import EventPublisher
    from robot_state import RobotState

    client.start()
    assert client.wait_until_connected(timeout=5)
    assert broker.wait_for_subscription(COMMANDS, timeout=5)

    state = RobotState()
    state.boot_completed()
    runner = CommandRunner(client, state, EventPublisher(client, state))

    command = emergency_stop()
    assert broker.send_to(COMMANDS, encode(command))
    assert drain(runner)
    assert broker.wait_for(results(broker, 2), timeout=5)

    state.resume()  # 다시 실행됐다면 SAFE_STOP 으로 돌아간다
    assert broker.send_to(COMMANDS, encode(command))
    assert drain(runner)

    time.sleep(0.5)
    assert len(broker.messages_on(RESULTS)) == 2
    assert state.mode == "PATROLLING"
