#!/usr/bin/env python3
"""u-blox 7 GPS 모듈 테스트 스크립트.

/dev/ttyACM0 에서 NMEA 문장을 읽어 위치/위성/속도 정보를 출력한다.
외부 의존성: pyserial 만 필요.

사용법:
    python3 gps_test.py                 # 파싱된 정보 출력
    python3 gps_test.py --raw           # NMEA 원문 그대로 출력
    python3 gps_test.py --port /dev/ttyACM0 --baud 9600
"""

import argparse
import sys
import time

import serial

PORT_DEFAULT = "/dev/ttyACM0"
BAUD_DEFAULT = 9600


def nmea_checksum_ok(line: str) -> bool:
    """$...*hh 형식의 NMEA 체크섬 검증."""
    if not line.startswith("$") or "*" not in line:
        return False
    body, _, chk = line[1:].partition("*")
    calc = 0
    for ch in body:
        calc ^= ord(ch)
    try:
        return calc == int(chk[:2], 16)
    except ValueError:
        return False


def dm_to_deg(value: str, direction: str) -> float | None:
    """NMEA의 도분(ddmm.mmmm) 표기를 십진수 도 단위로 변환."""
    if not value:
        return None
    dot = value.find(".")
    deg_len = dot - 2  # 분(minute)은 항상 소수점 앞 2자리
    deg = float(value[:deg_len])
    minutes = float(value[deg_len:])
    result = deg + minutes / 60.0
    if direction in ("S", "W"):
        result = -result
    return result


class GpsState:
    def __init__(self):
        self.lat = None
        self.lon = None
        self.alt = None
        self.sats = None
        self.hdop = None
        self.fix_quality = 0
        self.speed_kmh = None
        self.utc = None

    def update_gga(self, f: list[str]):
        # $GPGGA,hhmmss,lat,N,lon,E,fix,sats,hdop,alt,M,...
        self.utc = f[1]
        self.fix_quality = int(f[6]) if f[6] else 0
        self.sats = int(f[7]) if f[7] else 0
        self.hdop = float(f[8]) if f[8] else None
        if self.fix_quality > 0:
            self.lat = dm_to_deg(f[2], f[3])
            self.lon = dm_to_deg(f[4], f[5])
            self.alt = float(f[9]) if f[9] else None

    def update_rmc(self, f: list[str]):
        # $GPRMC,hhmmss,A/V,lat,N,lon,E,knots,course,date,...
        if f[2] == "A" and f[7]:
            self.speed_kmh = float(f[7]) * 1.852

    def line(self) -> str:
        if self.fix_quality == 0:
            return (
                f"[{self.utc or '--'}] FIX 없음 (위성 탐색 중, "
                f"가시위성 {self.sats if self.sats is not None else '?'}개)"
            )
        parts = [
            f"[{self.utc}] 위도 {self.lat:.6f}, 경도 {self.lon:.6f}",
            f"고도 {self.alt:.1f}m" if self.alt is not None else "",
            f"위성 {self.sats}개",
            f"HDOP {self.hdop}" if self.hdop is not None else "",
            f"속도 {self.speed_kmh:.1f}km/h" if self.speed_kmh is not None else "",
        ]
        return "  ".join(p for p in parts if p)


def main():
    ap = argparse.ArgumentParser(description="u-blox GPS NMEA 테스트")
    ap.add_argument("--port", default=PORT_DEFAULT)
    ap.add_argument("--baud", type=int, default=BAUD_DEFAULT)
    ap.add_argument("--raw", action="store_true", help="NMEA 원문 출력")
    args = ap.parse_args()

    try:
        ser = serial.Serial(args.port, args.baud, timeout=1)
    except serial.SerialException as e:
        sys.exit(f"포트 열기 실패: {e}")

    print(f"연결됨: {args.port} @ {args.baud}bps  (Ctrl+C로 종료)")
    state = GpsState()
    last_print = 0.0

    try:
        while True:
            raw = ser.readline().decode("ascii", errors="replace").strip()
            if not raw:
                continue
            if args.raw:
                print(raw)
                continue
            if not nmea_checksum_ok(raw):
                continue

            fields = raw.partition("*")[0].split(",")
            sentence = fields[0][3:]  # GPGGA/GNGGA -> GGA

            if sentence == "GGA":
                state.update_gga(fields)
            elif sentence == "RMC":
                state.update_rmc(fields)
            else:
                continue

            # GGA/RMC 갱신 시 1초에 한 번만 출력
            now = time.monotonic()
            if now - last_print >= 1.0:
                print(state.line())
                last_print = now
    except KeyboardInterrupt:
        print("\n종료")
    finally:
        ser.close()


if __name__ == "__main__":
    main()
