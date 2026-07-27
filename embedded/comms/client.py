"""MQTT 접속·발행·구독 — paho-mqtt.  [F-006]

topic 문자열·payload 조립은 여기서 하지 말고 전부 topics.py·factory.py 에서 가져온다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md
"""

from __future__ import annotations

import logging
import queue
import ssl
import threading
from datetime import datetime, timedelta, timezone

import paho.mqtt.client as mqtt
from paho.mqtt.packettypes import PacketTypes
from paho.mqtt.properties import Properties

from . import codec
from .config import MqttConfig
from .factory import MessageFactory
from .outbox import Outbox, OutboxRecord
from .topics import EVENT_KINDS, PUBLISH_POLICY, SUBSCRIBE_QOS, TopicKind

log = logging.getLogger(__name__)


RECONNECT_MIN_DELAY_SEC = 1
RECONNECT_MAX_DELAY_SEC = 30
RESEND_INTERVAL_SEC = 30.0
SENT_RETENTION_DAYS = 7
#: 등록보다 PUBACK 이 먼저 도착하는 경우를 대비한 짧은 기억. 크기는 넉넉하다.
_EARLY_ACK_LIMIT = 256


class MqttClient:
    """tbs/v2 발행·구독 클라이언트.

    수명: start() → (자동 재접속) → shutdown()
    presence 는 세 갈래로 나간다.
      LWT      OFFLINE / UNGRACEFUL_DISCONNECT — CONNECT 시 등록, 비정상 종료 시 브로커가 발행
      birth    ONLINE                          — 접속 성공 직후
      shutdown OFFLINE / GRACEFUL_SHUTDOWN     — 정상 종료 시 직접 발행
    """

    def __init__(
        self,
        config: MqttConfig,
        *,
        factory: MessageFactory = None,
        outbox: Outbox = None,
        command_queue: "queue.Queue" = None,
        client_factory=None,
        resend_interval_sec: float = RESEND_INTERVAL_SEC,
    ) -> None:
        self.config = config
        self.messages = factory or MessageFactory(config.robot_id)
        if self.messages.robot_id != config.robot_id:
            raise ValueError("factory.robot_id must match config.robot_id")
        self.topics = self.messages.topics
        self.outbox = outbox or Outbox(config.outbox_path)
        #: main.py 가 pop 해서 실행한다. 이 클래스는 명령을 해석하지 않는다.
        self.commands = command_queue or queue.Queue()

        self._resend_interval = resend_interval_sec
        self._connected = threading.Event()
        self._stopping = threading.Event()
        self._wakeup = threading.Event()
        self._resender = None

        self._ack_lock = threading.Lock()
        self._inflight = {}  # mid -> event_id
        self._early_acks = []  # mid, 최근 것부터 (_EARLY_ACK_LIMIT 로 제한)

        self._client = (client_factory or _build_paho_client)(config)
        self._client.on_connect = self._on_connect
        self._client.on_disconnect = self._on_disconnect
        self._client.on_message = self._on_message
        self._client.on_publish = self._on_publish

    # -- 수명 ------------------------------------------------------------

    @property
    def connected(self) -> bool:
        return self._connected.is_set()

    def start(self) -> None:
        """비동기 접속 시작. 접속을 기다리지 않고 즉시 돌아온다."""
        self._register_will()
        self._client.reconnect_delay_set(
            min_delay=RECONNECT_MIN_DELAY_SEC, max_delay=RECONNECT_MAX_DELAY_SEC
        )
        self.outbox.prune_sent(
            codec.to_iso(
                datetime.now(timezone.utc) - timedelta(days=SENT_RETENTION_DAYS)
            )
        )
        self._resender = threading.Thread(
            target=self._resend_loop, name="mqtt-outbox", daemon=True
        )
        self._resender.start()
        # connect_async + loop_start: 브로커가 죽어 있어도 기동을 막지 않는다.
        # 재접속은 paho 가 1→2→4→…→30초 백오프로 반복한다.
        # CLEAN_START_FIRST_ONLY: 첫 접속은 새 세션, 재접속은 세션을 이어받아
        # 구독과 미수신 명령을 지킨다.
        self._client.connect_async(
            self.config.host,
            self.config.port,
            keepalive=self.config.keepalive,
            clean_start=mqtt.MQTT_CLEAN_START_FIRST_ONLY,
            properties=_connect_properties(),
        )
        self._client.loop_start()

    def wait_until_connected(self, timeout: float = None) -> bool:
        return self._connected.wait(timeout)

    def shutdown(self, timeout: float = 2.0) -> None:
        """presence OFFLINE(GRACEFUL_SHUTDOWN) 을 보내고 끊는다.

        disconnect() 로 정상 종료하면 브로커가 LWT 를 버리므로
        UNGRACEFUL_DISCONNECT 는 발행되지 않는다.
        """
        self._stopping.set()
        self._wakeup.set()
        if self._connected.is_set():
            try:
                info = self._publish_direct(
                    TopicKind.PRESENCE,
                    self.messages.presence_offline("GRACEFUL_SHUTDOWN"),
                )
                if info is not None:
                    info.wait_for_publish(timeout)
            except Exception:  # 종료 경로에서 예외로 프로세스를 막지 않는다.
                log.warning("failed to publish graceful presence", exc_info=True)
        self._client.disconnect()
        self._client.loop_stop()
        if self._resender is not None:
            self._resender.join(timeout=timeout)
        self.outbox.close()

    def _register_will(self) -> None:
        payload = self.messages.presence_offline("UNGRACEFUL_DISCONNECT")
        policy = PUBLISH_POLICY[TopicKind.PRESENCE]
        self._client.will_set(
            self.topics.of(TopicKind.PRESENCE),
            codec.encode(payload),
            qos=policy.qos,
            retain=policy.retain,
        )

    # -- 발행 ------------------------------------------------------------

    def publish(self, kind: TopicKind, payload: dict) -> bool:
        """topic 종류에 맞는 QoS·retain 으로 발행한다.

        events 4종은 Outbox 를 거친다 — 저장 → 발행 → PUBACK 시 sent.
        접속이 끊겨 있으면 저장만 하고 재접속 후 자동 재발행한다.

        반환값은 '지금 paho 로 넘겼는지' 다. event 가 False 여도 유실이 아니라
        Outbox 에 남아 있다는 뜻이다. 서버 수신 확인은 PUBACK 시점에 이뤄진다.
        """
        kind = TopicKind(kind)
        if kind not in PUBLISH_POLICY:
            raise ValueError(f"{kind} is not a publish topic")
        if kind in EVENT_KINDS:
            return self.publish_event(kind, payload)
        return self._publish_direct(kind, payload).rc == mqtt.MQTT_ERR_SUCCESS

    def publish_event(self, kind: TopicKind, payload: dict) -> bool:
        """event 를 Outbox 에 적고, 접속돼 있으면 바로 발행한다."""
        kind = TopicKind(kind)
        if kind not in EVENT_KINDS:
            raise ValueError(f"{kind} is not an event topic")
        event_id = payload.get("event_id")
        if not event_id:
            raise ValueError("event payload must carry event_id")
        self.outbox.enqueue(event_id, kind, payload)
        if not self._connected.is_set():
            log.info("offline: %s queued in outbox (event_id=%s)", kind.value, event_id)
            return False
        return self._send_stored(OutboxRecord(event_id, kind, payload))

    def _publish_direct(self, kind: TopicKind, payload: dict):
        policy = PUBLISH_POLICY[kind]
        return self._client.publish(
            self.topics.of(kind),
            codec.encode(payload),
            qos=policy.qos,
            retain=policy.retain,
        )

    def _send_stored(self, record: OutboxRecord) -> bool:
        """저장된 event 를 발행한다. sent_at 만 현재 시각으로 갱신한다."""
        payload = dict(record.payload)
        payload["sent_at"] = codec.utc_now()
        self.outbox.mark_attempted(record.event_id)
        info = self._publish_direct(record.kind, payload)
        if info.rc != mqtt.MQTT_ERR_SUCCESS:
            log.warning(
                "publish failed rc=%s, kept in outbox (event_id=%s)",
                info.rc,
                record.event_id,
            )
            return False
        # publish() 가 돌아온 뒤에 등록한다. paho 는 PUBACK 처리 중 내부 락을 쥐고
        # on_publish 를 부르므로, 여기서 _ack_lock 을 쥔 채 publish 하면 교착된다.
        acked_early = False
        with self._ack_lock:
            # 같은 event 의 이전 시도가 남아 있으면 정리한다 (PUBACK 없이 재전송한 경우).
            for stale in [
                mid for mid, eid in self._inflight.items() if eid == record.event_id
            ]:
                del self._inflight[stale]
            if info.mid in self._early_acks:
                self._early_acks.remove(info.mid)
                acked_early = True
            else:
                self._inflight[info.mid] = record.event_id
        if acked_early:
            self.outbox.mark_sent(record.event_id)
        return True

    # -- 재전송 ----------------------------------------------------------

    def _resend_loop(self) -> None:
        while not self._stopping.is_set():
            self._wakeup.wait(self._resend_interval)
            self._wakeup.clear()
            if self._stopping.is_set():
                break
            if not self._connected.is_set():
                continue
            try:
                self.flush_outbox()
            except Exception:  # 재전송 실패가 스레드를 죽이지 않게 한다.
                log.exception("outbox flush failed")

    def flush_outbox(self) -> int:
        """미전송 event 재발행. event_id·occurred_at 은 유지된다."""
        cutoff = codec.to_iso(
            datetime.now(timezone.utc) - timedelta(seconds=self._resend_interval)
        )
        records = self.outbox.pending(attempted_before=cutoff)
        sent = 0
        for record in records:
            if not self._connected.is_set():
                break
            if self._send_stored(record):
                sent += 1
        if sent:
            log.info("resent %d pending event(s) from outbox", sent)
        return sent

    # -- paho 콜백 -------------------------------------------------------

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        if getattr(reason_code, "is_failure", reason_code != 0):
            log.error("connect refused: %s", reason_code)
            return
        self._connected.set()
        log.info("connected to %s:%s", self.config.host, self.config.port)
        # birth — 재접속 때마다 다시 발행해서 retain 된 OFFLINE 을 덮는다.
        self._publish_direct(TopicKind.PRESENCE, self.messages.presence_online())
        client.subscribe(self.topics.of(TopicKind.COMMAND_REQUEST), qos=SUBSCRIBE_QOS)
        # 밀린 event 재발행은 네트워크 루프를 막지 않도록 워커에 맡긴다.
        self._wakeup.set()

    def _on_disconnect(self, client, userdata, *args):
        self._connected.clear()
        with self._ack_lock:
            # 미확인 mid 는 버린다. 해당 event 는 outbox 에 pending 으로 남아 있다.
            self._inflight.clear()
            self._early_acks.clear()
        if not self._stopping.is_set():
            log.warning("disconnected, retrying with backoff")

    def _on_publish(self, client, userdata, mid, reason_code=None, properties=None):
        with self._ack_lock:
            event_id = self._inflight.pop(mid, None)
            if event_id is None:
                # PUBACK 이 등록보다 빨랐거나, event 가 아닌 발행이다.
                self._early_acks.append(mid)
                del self._early_acks[:-_EARLY_ACK_LIMIT]
        if event_id is not None:
            self.outbox.mark_sent(event_id)

    def _on_message(self, client, userdata, message):
        if message.topic != self.topics.of(TopicKind.COMMAND_REQUEST):
            log.warning("ignoring message on unexpected topic %s", message.topic)
            return
        try:
            command = codec.decode(message.payload)
        except (ValueError, UnicodeDecodeError):
            log.warning("dropping malformed command payload")
            return
        # 검증·실행·중복 제거는 main.py 몫이다. 여기서는 큐에 넣기만 한다.
        self.commands.put(command)


def _build_paho_client(config: MqttConfig) -> mqtt.Client:
    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=config.robot_id,
        protocol=mqtt.MQTTv5,
    )
    if config.username:
        client.username_pw_set(config.username, config.password)
    if config.tls_enabled:
        client.tls_set(ca_certs=config.ca_cert, tls_version=ssl.PROTOCOL_TLS_CLIENT)
    else:
        log.warning("ca_cert is not set — connecting without TLS (local test only)")
    return client


#: 재접속 사이에 브로커가 세션(구독·미수신 명령)을 얼마나 들고 있을지.
SESSION_EXPIRY_SEC = 3600


def _connect_properties() -> Properties:
    properties = Properties(PacketTypes.CONNECT)
    properties.SessionExpiryInterval = SESSION_EXPIRY_SEC
    return properties
