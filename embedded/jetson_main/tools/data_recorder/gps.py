"""u-blox 7 GPS reader (NMEA over USB serial).

Reads $xxRMC/$xxGGA in a daemon thread and keeps the latest fix. No pynmea2
dependency - the two sentences we need are trivial to parse by hand. The
device can be unplugged/replugged at any time: the read loop reconnects
every RECONNECT_SEC and status() just reports connected=False meanwhile.
"""

import logging
import threading
import time

import serial

import recorder_config as rc

RECONNECT_SEC = 2.0
# No NMEA line for this long = treat the link as dead (u-blox sends ~1 Hz).
STALE_SEC = 5.0


def _nmea_checksum_ok(line):
    """line is the full sentence starting with '$'. Returns False on any malformation."""
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


def _parse_coord(value, hemi):
    """NMEA ddmm.mmmm / dddmm.mmmm -> signed decimal degrees, or None."""
    if not value or not hemi:
        return None
    try:
        raw = float(value)
    except ValueError:
        return None
    deg = int(raw / 100)
    minutes = raw - deg * 100
    dec = deg + minutes / 60.0
    return -dec if hemi in ('S', 'W') else dec


class GPSWorker:
    def __init__(self):
        self.port = getattr(rc, 'GPS_PORT', '/dev/ttyACM0')
        self.baud = getattr(rc, 'GPS_BAUD', 9600)
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None
        self._last_line_ts = 0.0
        # latest state, guarded by _lock
        self._st = {
            'fix': False, 'lat': None, 'lon': None, 'speed_kmh': None,
            'course': None, 'alt_m': None, 'sats': 0, 'hdop': None,
        }

    def start(self):
        self._thread = threading.Thread(target=self._run, name='gps', daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=3)

    def _run(self):
        while not self._stop.is_set():
            try:
                with serial.Serial(self.port, self.baud, timeout=1.0) as ser:
                    logging.info(f"[GPS] connected: {self.port} @ {self.baud}")
                    while not self._stop.is_set():
                        raw = ser.readline()
                        if not raw:
                            continue
                        line = raw.decode('ascii', errors='replace').strip()
                        if _nmea_checksum_ok(line):
                            self._last_line_ts = time.monotonic()
                            self._handle(line[1:line.rfind('*')].split(','))
            except (serial.SerialException, OSError) as e:
                logging.warning(f"[GPS] {self.port}: {e} - retrying in {RECONNECT_SEC}s")
                self._stop.wait(RECONNECT_SEC)

    def _handle(self, f):
        kind = f[0][2:]  # drop GP/GN/GL talker prefix
        if kind == 'RMC' and len(f) >= 10:
            with self._lock:
                self._st['fix'] = (f[2] == 'A')
                if f[2] == 'A':
                    self._st['lat'] = _parse_coord(f[3], f[4])
                    self._st['lon'] = _parse_coord(f[5], f[6])
                    self._st['speed_kmh'] = round(float(f[7]) * 1.852, 1) if f[7] else None
                    self._st['course'] = round(float(f[8]), 1) if f[8] else None
        elif kind == 'GGA' and len(f) >= 11:
            with self._lock:
                self._st['sats'] = int(f[7]) if f[7] else 0
                self._st['hdop'] = float(f[8]) if f[8] else None
                self._st['alt_m'] = float(f[9]) if f[9] else None

    def status(self):
        connected = (time.monotonic() - self._last_line_ts) < STALE_SEC
        with self._lock:
            out = dict(self._st)
        out['connected'] = connected
        if not connected:
            out['fix'] = False
        return out
