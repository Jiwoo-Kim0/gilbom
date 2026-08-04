"""u-blox 7 GPS 리더 (USB 시리얼 NMEA) — tools/data_recorder/gps.py 이식.

$xxRMC/$xxGGA만 자체 파싱한다 (pynmea2 불필요). 원본과의 차이: 포트·보레이트를
config 주입으로 받는다 (recorder_config 의존 제거). 장치는 언제든 뽑혔다 꽂힐 수
있다 — 읽기 루프가 2초마다 재접속하고, 그동안 status()는 connected=False를 낸다.
"""

import logging
import threading
import time

logger = logging.getLogger("robot.webtest")

RECONNECT_SEC = 2.0
STALE_SEC = 5.0     # 이 시간 동안 NMEA 문장이 없으면 링크 사망 취급 (u-blox ~1Hz)


def _nmea_checksum_ok(line: str) -> bool:
    """'$'로 시작하는 전체 문장 검증 — 어떤 기형이든 False."""
    star = line.rfind('*')
    if not line.startswith('$') or star < 0 or len(line) < star + 3:
        return False
    calc = 0
    for ch in line[1:star]:
        calc ^= ord(ch)
    try:
        return calc == int(line[star + 1:star + 3], 16)
    except ValueError:
        return False


def _parse_coord(value: str, hemisphere: str):
    """NMEA ddmm.mmmm / dddmm.mmmm → 부호 있는 십진수 도(度), 실패 시 None."""
    if not value or not hemisphere:
        return None
    try:
        raw = float(value)
    except ValueError:
        return None
    degrees = int(raw / 100)
    minutes = raw - degrees * 100
    decimal = degrees + minutes / 60.0
    return -decimal if hemisphere in ('S', 'W') else decimal


class GpsReader:
    """최신 fix를 보관하는 GPS 리더 — start() 후 status()로 읽는다."""

    def __init__(self, device: str, baud: int):
        self._device = device
        self._baud = baud
        self._lock = threading.Lock()
        self._stop_requested = threading.Event()
        self._thread = None
        self._last_line_at = 0.0
        self._state = {
            'fix': False, 'lat': None, 'lon': None, 'speed_kmh': None,
            'course': None, 'alt_m': None, 'sats': 0, 'hdop': None,
        }

    def start(self):
        self._thread = threading.Thread(target=self._run, name='gps', daemon=True)
        self._thread.start()

    def stop(self):
        self._stop_requested.set()
        if self._thread:
            self._thread.join(timeout=3)

    def _run(self):
        import serial  # 보드 전용 의존 — 이 스레드 안에서만 쓴다

        while not self._stop_requested.is_set():
            try:
                with serial.Serial(self._device, self._baud, timeout=1.0) as port:
                    logger.info("[GPS] 연결: %s @ %d", self._device, self._baud)
                    while not self._stop_requested.is_set():
                        raw = port.readline()
                        if not raw:
                            continue
                        line = raw.decode('ascii', errors='replace').strip()
                        if _nmea_checksum_ok(line):
                            self._last_line_at = time.monotonic()
                            self._handle(line[1:line.rfind('*')].split(','))
            except (serial.SerialException, OSError) as error:
                logger.warning("[GPS] %s: %s — %.0f초 후 재시도",
                               self._device, error, RECONNECT_SEC)
                self._stop_requested.wait(RECONNECT_SEC)

    def _handle(self, fields):
        kind = fields[0][2:]  # GP/GN/GL 토커 접두 제거
        if kind == 'RMC' and len(fields) >= 10:
            with self._lock:
                self._state['fix'] = (fields[2] == 'A')
                if fields[2] == 'A':
                    self._state['lat'] = _parse_coord(fields[3], fields[4])
                    self._state['lon'] = _parse_coord(fields[5], fields[6])
                    self._state['speed_kmh'] = (round(float(fields[7]) * 1.852, 1)
                                                if fields[7] else None)
                    self._state['course'] = round(float(fields[8]), 1) if fields[8] else None
        elif kind == 'GGA' and len(fields) >= 11:
            with self._lock:
                self._state['sats'] = int(fields[7]) if fields[7] else 0
                self._state['hdop'] = float(fields[8]) if fields[8] else None
                self._state['alt_m'] = float(fields[9]) if fields[9] else None

    def status(self) -> dict:
        connected = (time.monotonic() - self._last_line_at) < STALE_SEC
        with self._lock:
            out = dict(self._state)
        out['connected'] = connected
        if not connected:
            out['fix'] = False
        return out
