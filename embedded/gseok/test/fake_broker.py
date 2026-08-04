"""테스트용 최소 MQTT 5 브로커.

mosquitto 없이도 접속·재접속·LWT·PUBACK 을 실제 TCP 위에서 검증하기 위한 것이다.
운영 브로커의 대체물이 아니다 — 우리가 쓰는 패킷만 처리한다.

구현 범위
  CONNECT/CONNACK, PUBLISH(QoS0·1), PUBACK, SUBSCRIBE/SUBACK, PINGREQ/PINGRESP, DISCONNECT
  Will: DISCONNECT 없이 소켓이 끊기면 will 을 발행한다 (구독자에게 전달 + 기록)
미구현
  retain 저장·재전송, QoS2, 세션 영속, 인증, 흐름 제어
"""

from __future__ import annotations

import socket
import struct
import threading
import time
from dataclasses import dataclass, field

CONNECT = 1
CONNACK = 2
PUBLISH = 3
PUBACK = 4
SUBSCRIBE = 8
SUBACK = 9
UNSUBSCRIBE = 10
PINGREQ = 12
PINGRESP = 13
DISCONNECT = 14


@dataclass
class Message:
    topic: str
    payload: bytes
    qos: int
    retain: bool
    client_id: str
    is_will: bool = False


@dataclass
class _Session:
    conn: socket.socket
    client_id: str = ""
    graceful: bool = False
    will: Message = None
    subscriptions: list = field(default_factory=list)


def _encode_varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value % 128
        value //= 128
        if value:
            byte |= 0x80
        out.append(byte)
        if not value:
            return bytes(out)


def _encode_string(text: str) -> bytes:
    raw = text.encode("utf-8")
    return struct.pack("!H", len(raw)) + raw


class _Reader:
    """수신 바이트 커서."""

    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def byte(self) -> int:
        value = self.data[self.pos]
        self.pos += 1
        return value

    def uint16(self) -> int:
        value = struct.unpack_from("!H", self.data, self.pos)[0]
        self.pos += 2
        return value

    def varint(self) -> int:
        multiplier = 1
        value = 0
        while True:
            byte = self.byte()
            value += (byte & 0x7F) * multiplier
            if not byte & 0x80:
                return value
            multiplier *= 128

    def blob(self) -> bytes:
        length = self.uint16()
        value = self.data[self.pos : self.pos + length]
        self.pos += length
        return value

    def string(self) -> str:
        return self.blob().decode("utf-8")

    def skip_properties(self) -> None:
        # varint() 이 pos 를 옮기므로 길이를 먼저 확정한다.
        length = self.varint()
        self.pos += length

    def rest(self) -> bytes:
        return self.data[self.pos :]


def _recv_exact(conn: socket.socket, count: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < count:
        chunk = conn.recv(count - len(chunks))
        if not chunk:
            return b""
        chunks += chunk
    return bytes(chunks)


def _recv_remaining_length(conn: socket.socket) -> int:
    multiplier = 1
    value = 0
    while True:
        raw = _recv_exact(conn, 1)
        if not raw:
            raise ConnectionError("closed while reading length")
        value += (raw[0] & 0x7F) * multiplier
        if not raw[0] & 0x80:
            return value
        multiplier *= 128


def _topic_matches(filter_: str, topic: str) -> bool:
    if filter_ == topic:
        return True
    f = filter_.split("/")
    t = topic.split("/")
    for index, part in enumerate(f):
        if part == "#":
            return True
        if index >= len(t):
            return False
        if part != "+" and part != t[index]:
            return False
    return len(f) == len(t)


class FakeBroker:
    """스레드로 도는 인메모리 브로커."""

    def __init__(self, host: str = "127.0.0.1", port: int = 0) -> None:
        self.host = host
        self.port = port
        self.messages = []
        self.subscribed = []  # 지금까지 받은 topic filter
        self.connect_count = 0
        self._lock = threading.Lock()
        self._changed = threading.Condition(self._lock)
        self._sessions = []
        self._server = None
        self._acceptor = None
        self._stopping = threading.Event()

    # -- 수명 ------------------------------------------------------------

    def start(self) -> "FakeBroker":
        self._stopping.clear()
        self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server.bind((self.host, self.port))
        self.port = self._server.getsockname()[1]
        self._server.listen(8)
        self._server.settimeout(0.2)
        self._acceptor = threading.Thread(target=self._accept_loop, daemon=True)
        self._acceptor.start()
        return self

    def stop(self) -> None:
        """리스너와 모든 연결을 끊는다 — 브로커 다운·재시작 시나리오용."""
        self._stopping.set()
        with self._lock:
            sessions = list(self._sessions)
        for session in sessions:
            try:
                session.conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                session.conn.close()
            except OSError:
                pass
        if self._server is not None:
            try:
                self._server.close()
            except OSError:
                pass
        if self._acceptor is not None:
            self._acceptor.join(timeout=2)
        self._server = None

    def _accept_loop(self) -> None:
        while not self._stopping.is_set():
            try:
                conn, _ = self._server.accept()
            except (socket.timeout, OSError):
                continue
            conn.settimeout(1.0)
            threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    # -- 관측 ------------------------------------------------------------

    def wait_for(self, predicate, timeout: float = 5.0):
        """predicate(messages) 가 참을 반환할 때까지 기다린다."""
        deadline = time.monotonic() + timeout
        with self._changed:
            while True:
                result = predicate(list(self.messages))
                if result:
                    return result
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self._changed.wait(remaining)

    def messages_on(self, topic: str) -> list:
        with self._lock:
            return [m for m in self.messages if m.topic == topic]

    def clear(self) -> None:
        with self._lock:
            self.messages.clear()
            self.subscribed.clear()

    def wait_for_subscription(self, topic_filter: str, timeout: float = 5.0) -> bool:
        deadline = time.monotonic() + timeout
        with self._changed:
            while topic_filter not in self.subscribed:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return False
                self._changed.wait(remaining)
            return True

    def send_to(self, topic: str, payload: bytes, qos: int = 1) -> bool:
        """브로커 → 로봇 발행 (commands/request 시뮬레이션)."""
        with self._lock:
            targets = [
                session
                for session in self._sessions
                if any(_topic_matches(f, topic) for f in session.subscriptions)
            ]
        for session in targets:
            self._deliver(session, topic, payload, qos)
        return bool(targets)

    # -- 연결 처리 -------------------------------------------------------

    def _serve(self, conn: socket.socket) -> None:
        session = _Session(conn=conn)
        with self._lock:
            self._sessions.append(session)
        try:
            while not self._stopping.is_set():
                try:
                    header = _recv_exact(conn, 1)
                except socket.timeout:
                    continue
                except OSError:
                    break
                if not header:
                    break
                packet_type = header[0] >> 4
                flags = header[0] & 0x0F
                length = _recv_remaining_length(conn)
                body = _recv_exact(conn, length) if length else b""
                if self._dispatch(session, packet_type, flags, body) is False:
                    break
        except (OSError, ConnectionError, IndexError, struct.error):
            pass
        finally:
            with self._lock:
                if session in self._sessions:
                    self._sessions.remove(session)
            if not session.graceful and session.will is not None:
                self._record(session.will)
                self._route(session.will)
            try:
                conn.close()
            except OSError:
                pass

    def _dispatch(self, session, packet_type, flags, body) -> bool:
        if packet_type == CONNECT:
            self._on_connect(session, body)
        elif packet_type == PUBLISH:
            self._on_publish(session, flags, body)
        elif packet_type == SUBSCRIBE:
            self._on_subscribe(session, body)
        elif packet_type == PINGREQ:
            session.conn.sendall(bytes([PINGRESP << 4, 0]))
        elif packet_type == DISCONNECT:
            session.graceful = True  # 정상 종료 — will 을 버린다
            return False
        elif packet_type in (PUBACK, UNSUBSCRIBE):
            pass  # 로봇이 보낸 PUBACK 은 무시한다
        return True

    def _on_connect(self, session, body) -> None:
        reader = _Reader(body)
        reader.string()  # protocol name
        reader.byte()  # protocol level
        connect_flags = reader.byte()
        reader.uint16()  # keepalive
        reader.skip_properties()
        session.client_id = reader.string()
        if connect_flags & 0x04:  # will flag
            reader.skip_properties()
            topic = reader.string()
            payload = reader.blob()
            session.will = Message(
                topic=topic,
                payload=payload,
                qos=(connect_flags >> 3) & 0x03,
                retain=bool((connect_flags >> 5) & 0x01),
                client_id=session.client_id,
                is_will=True,
            )
        with self._changed:
            self.connect_count += 1
            self._changed.notify_all()
        # CONNACK: session_present=0, reason=success, properties 없음
        session.conn.sendall(bytes([CONNACK << 4, 3, 0x00, 0x00, 0x00]))

    def _on_publish(self, session, flags, body) -> None:
        qos = (flags & 0x06) >> 1
        retain = bool(flags & 0x01)
        reader = _Reader(body)
        topic = reader.string()
        packet_id = reader.uint16() if qos > 0 else None
        reader.skip_properties()
        message = Message(
            topic=topic,
            payload=reader.rest(),
            qos=qos,
            retain=retain,
            client_id=session.client_id,
        )
        self._record(message)
        if qos == 1:
            session.conn.sendall(
                bytes([PUBACK << 4, 2]) + struct.pack("!H", packet_id)
            )
        self._route(message)

    def _on_subscribe(self, session, body) -> None:
        reader = _Reader(body)
        packet_id = reader.uint16()
        reader.skip_properties()
        granted = bytearray()
        while reader.pos < len(reader.data):
            topic_filter = reader.string()
            session.subscriptions.append(topic_filter)
            reader.byte()  # subscription options
            granted.append(0x01)
            with self._changed:
                self.subscribed.append(topic_filter)
                self._changed.notify_all()
        session.conn.sendall(
            bytes([SUBACK << 4, 3 + len(granted)])
            + struct.pack("!H", packet_id)
            + b"\x00"
            + bytes(granted)
        )

    def _record(self, message: Message) -> None:
        with self._changed:
            self.messages.append(message)
            self._changed.notify_all()

    def _route(self, message: Message) -> None:
        with self._lock:
            targets = [
                session
                for session in self._sessions
                if any(_topic_matches(f, message.topic) for f in session.subscriptions)
            ]
        for session in targets:
            self._deliver(session, message.topic, message.payload, min(message.qos, 1))

    def _deliver(self, session, topic: str, payload: bytes, qos: int) -> None:
        variable = _encode_string(topic)
        if qos > 0:
            variable += struct.pack("!H", 1)
        variable += b"\x00"  # 빈 property block
        packet = bytes([(PUBLISH << 4) | (qos << 1)])
        packet += _encode_varint(len(variable) + len(payload)) + variable + payload
        try:
            session.conn.sendall(packet)
        except OSError:
            pass
