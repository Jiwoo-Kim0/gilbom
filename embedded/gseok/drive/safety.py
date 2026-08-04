"""장애물 감지 — YDLidar (2D, USB serial). 판정만 하고 정지 실행은 events → motor.stop().  [F-003]

시리얼 파서·4초 워치독은 driving_test/ydlidar_driver.py 에서 가져왔다(원본은 읽기 전용).
⚠️ 이 프로세스가 시리얼 포트를 소유한다. driving_test 앱을 동시에 띄우면 /dev/ttyUSB0 를
   서로 뺏어 둘 다 스캔을 놓친다 — 둘 중 하나만 실행할 것.
개발 PC 에는 pyserial 도 장치도 없다. import·연결이 실패해도 예외를 올리지 않고
health() 가 FAULT 를 돌려주며 프로세스는 계속 뜬다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §5.3·§10.2
"""

from __future__ import annotations

import logging
import threading
import time

log = logging.getLogger(__name__)

#: 이 시간 넘게 유효 패킷이 없으면 연결을 다시 잡고 FAULT 로 본다 (원본 워치독과 같은 값).
WATCHDOG_SEC = 4.0
#: 패킷은 오는데 이만큼 늦으면 DEGRADED — 계약 §5.3 의 "주기·Freshness 미달" 이다.
STALE_SEC = 1.0
#: 스캔이 이보다 희박하면 각도 커버리지를 믿을 수 없다.
MIN_SCAN_POINTS = 10
#: 각도별 측정값의 수명. 센서는 7~10Hz 로 도니 0.5초면 서너 바퀴다.
#: 이보다 오래된 점은 버린다 — 안 버리면 지나쳐 온 장애물의 거리가 최소거리 계산에
#: 계속 섞여서, 이미 없는 것 때문에 서 있게 된다.
SCAN_TTL_SEC = 0.5

DEFAULTS = {
    "lidar_port": "/dev/ttyUSB0",
    "lidar_baudrate": 128000,
    #: 센서 장착 방향 보정 — driving_test 에서 실측으로 맞춘 값이다. 임의로 바꾸지 말 것.
    "lidar_angle_offset_deg": 200.0,
    "stop_distance_m": 0.40,
    "fov_front_min_deg": -30.0,
    "fov_front_max_deg": 30.0,
    "min_scan_distance_m": 0.10,
    "max_scan_distance_m": 4.00,
    "min_scan_points": MIN_SCAN_POINTS,
    "scan_ttl_sec": SCAN_TTL_SEC,
}


def _open_serial(port, baudrate):
    """기본 시리얼 팩토리. pyserial 이 없는 개발 PC 에서는 여기서 실패한다."""
    import serial

    conn = serial.Serial(port, baudrate, timeout=1.0)
    conn.flushInput()
    try:
        # YDLidar 모터 enable — DTR 을 올리고 스캔 시작 명령을 보낸다.
        conn.dtr = True
        conn.rts = False
    except Exception:
        pass
    conn.write(b"\xa5\x60")
    return conn


class LidarReader:
    """시리얼 프레임을 읽어 최신 스캔 한 장을 들고 있는다.

    스캔 해석(전방 최소거리·정지 판정)까지 여기서 한다 — events 는 (obstacle, distance)
    만 받는다. 드라이버를 주입할 수 있어서 하드웨어 없이 파서를 시험할 수 있다.
    """

    def __init__(self, config: dict = None, *, serial_factory=_open_serial, monotonic=time.monotonic):
        settings = dict(DEFAULTS)
        settings.update({k: v for k, v in (config or {}).items() if k in DEFAULTS})
        self._settings = settings
        self._serial_factory = serial_factory
        self._monotonic = monotonic

        self._lock = threading.Lock()
        self._thread = None
        self._running = False
        self._serial = None
        self._points = {}  # 각도(0.1도) -> (거리, 측정 시각)
        self._last_packet_at = None
        self._connected = False
        self._connect_logged = False

    # -- 수명 ------------------------------------------------------------

    def start(self) -> "LidarReader":
        if self._running:
            return self
        self._running = True
        self._last_packet_at = self._monotonic()
        self._thread = threading.Thread(target=self._read_loop, name="lidar", daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        self._running = False
        conn, self._serial = self._serial, None
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass

    # -- 판정 ------------------------------------------------------------

    def check(self) -> tuple:
        """(obstacle, distance_m). 스캔이 없으면 거리도 None 이다.

        계약 §3.1 — 의미 있는 값이 없으면 임의의 숫자 대신 null 이다. 원본의
        front_min_dist=99.0 매직값을 그대로 흘려보내지 않는다.
        """
        distance_m = self.front_distance_m()
        if distance_m is None:
            # 스캔이 없다 = 장애물이 없다는 뜻이 아니다. 그건 health 가 말한다.
            return False, None
        return distance_m <= self._settings["stop_distance_m"], distance_m

    def front_distance_m(self):
        """전방 FOV 최소 거리. 볼 수 있는 점이 없으면 None."""
        low = self._settings["fov_front_min_deg"]
        high = self._settings["fov_front_max_deg"]
        near = self._settings["min_scan_distance_m"]
        far = self._settings["max_scan_distance_m"]
        distances = [
            distance
            for angle, distance in self._fresh_points()
            if low <= angle <= high and near <= distance <= far
        ]
        return min(distances) if distances else None

    def _fresh_points(self) -> list:
        """수명이 지나지 않은 (각도, 거리). 오래된 각도는 여기서 사라진다."""
        oldest = self._monotonic() - self._settings["scan_ttl_sec"]
        with self._lock:
            return [
                (angle, distance)
                for angle, (distance, measured_at) in self._points.items()
                if measured_at >= oldest
            ]

    def health(self) -> str:
        """계약 §5.3 — Serial 연결·최근 Scan·Parse 실패로 판정한다."""
        if not self._running:
            return "UNKNOWN"  # 아직 기동 전이다. 가짜 OK 를 돌려주지 않는다.
        points = len(self._fresh_points())
        with self._lock:
            connected = self._connected
            last_at = self._last_packet_at
        if not connected:
            return "FAULT"
        idle = self._monotonic() - (last_at or 0.0)
        if idle > WATCHDOG_SEC:
            return "FAULT"
        if idle > STALE_SEC or points < self._settings["min_scan_points"]:
            return "DEGRADED"
        return "OK"

    # -- 시리얼 ----------------------------------------------------------

    def _connect(self) -> bool:
        settings = self._settings
        try:
            self._serial = self._serial_factory(
                settings["lidar_port"], settings["lidar_baudrate"]
            )
        except Exception as exc:
            if not self._connect_logged:
                # 1초마다 재시도한다 — 같은 실패를 계속 찍으면 로그가 그것만 남는다.
                self._connect_logged = True
                log.warning("lidar: cannot open %s (%s)", settings["lidar_port"], exc)
            self._serial = None
            with self._lock:
                self._connected = False
            return False
        self._connect_logged = False
        with self._lock:
            self._connected = True
        log.info("lidar: connected on %s", settings["lidar_port"])
        return True

    def _read_loop(self) -> None:
        buffer = bytearray()
        while self._running:
            try:
                conn = self._serial
                if conn is None or not getattr(conn, "is_open", True):
                    if not self._connect():
                        time.sleep(1.0)
                    buffer.clear()
                    continue
                if self._monotonic() - (self._last_packet_at or 0.0) > WATCHDOG_SEC:
                    # 워치독 — 열려 있는데 스캔이 안 온다. 연결을 다시 잡는다.
                    log.warning("lidar: no scan for %.0fs, reconnecting", WATCHDOG_SEC)
                    self._last_packet_at = self._monotonic()
                    self._close_serial()
                    buffer.clear()
                    continue
                waiting = getattr(conn, "in_waiting", 0)
                if not waiting:
                    time.sleep(0.01)
                    continue
                buffer.extend(conn.read(waiting))
                self._consume(buffer)
            except Exception as exc:
                log.warning("lidar: serial read failed (%s)", exc)
                self._close_serial()
                time.sleep(0.5)

    def _close_serial(self) -> None:
        conn, self._serial = self._serial, None
        with self._lock:
            self._connected = False
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass

    # -- 프레임 파서 (YDLidar 0xAA55) --------------------------------------

    def _consume(self, buffer: bytearray) -> None:
        """버퍼에서 완성된 프레임만 꺼내 최신 스캔에 반영한다."""
        offset = self._settings["lidar_angle_offset_deg"]
        near = self._settings["min_scan_distance_m"]
        far = self._settings["max_scan_distance_m"]
        while len(buffer) >= 10:
            if buffer[0] != 0xAA or buffer[1] != 0x55:
                buffer.pop(0)
                continue
            sample_count = buffer[3]
            frame_size = 10 + sample_count * 2
            if len(buffer) < frame_size:
                return  # 아직 덜 왔다
            start_angle = ((buffer[4] | (buffer[5] << 8)) >> 1) / 64.0
            end_angle = ((buffer[6] | (buffer[7] << 8)) >> 1) / 64.0
            span = end_angle - start_angle
            if span < 0:
                span += 360.0

            measured_at = self._monotonic()
            points = {}
            for index in range(sample_count):
                raw = buffer[10 + index * 2] | (buffer[10 + index * 2 + 1] << 8)
                distance = raw / 4000.0
                if not near <= distance <= far:
                    continue
                if sample_count > 1:
                    angle = start_angle + span / (sample_count - 1) * index
                else:
                    angle = start_angle
                # 장착 오프셋을 빼고 -180~180 으로 접는다 (0도가 정면).
                relative = (angle - offset + 180.0) % 360.0 - 180.0
                points[round(relative, 1)] = (distance, measured_at)
            del buffer[:frame_size]

            if points:
                with self._lock:
                    self._points.update(points)
                    self._last_packet_at = measured_at
                    self._expire(measured_at)

    def _expire(self, now: float) -> None:
        """수명이 지난 각도를 지운다. 호출자가 락을 쥐고 있다."""
        oldest = now - self._settings["scan_ttl_sec"]
        for angle in [
            angle
            for angle, (_distance, measured_at) in self._points.items()
            if measured_at < oldest
        ]:
            del self._points[angle]


# --------------------------------------------------------------------------
# 모듈 싱글턴 — main.py 가 configure() 로 설정하고 start() 로 장치를 연다.
# --------------------------------------------------------------------------

_config = {}
_reader = None


def configure(config: dict = None) -> None:
    """설정만 받아 둔다. 장치는 건드리지 않는다 — 테스트가 build() 를 부를 수 있어야 한다."""
    global _config
    _config = dict(config or {})


def start() -> None:
    """시리얼 리더를 1회 기동한다. 프로세스 수명 동안 유지된다."""
    global _reader
    if _reader is None:
        _reader = LidarReader(_config)
    _reader.start()


def stop() -> None:
    if _reader is not None:
        _reader.stop()


def check() -> tuple:
    """(obstacle, distance_m) — events/safety 의 2단계 판정 재료 (계약 §10.2).

    확정·해제 판정은 events.py 가 한다. 여기서는 지금 보이는 것만 돌려준다.
    """
    if _reader is None:
        return False, None
    return _reader.check()


def health() -> str:
    """state.health.lidar — Serial·SDK 연결·최근 Scan·연속 Parse 실패로 판정 (계약 §5.3).

    기동 전에는 UNKNOWN 이다. 계약상 UNKNOWN 은 '검사 전' 이라는 뜻이라
    지금 상태를 정확히 나타낸다 — 가짜 OK 를 돌려주면 안 된다.
    """
    if _reader is None:
        return "UNKNOWN"
    return _reader.health()
