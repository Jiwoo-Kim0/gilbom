#!/usr/bin/env python3
"""위성 신호 실시간 모니터 — 야외 테스트용.

2초마다 위성별 신호세기(C/N0)를 막대그래프로 표시한다.
야외에서 정상이라면 수 분 내에 C/N0 35~50 dBHz 위성이 4개 이상 잡혀야 한다.

사용법: python3 gps_sats.py [포트]   (기본 /dev/ttyACM0)
"""

import time

import serial

from gps_diag import PORT, poll_svinfo

QUALITY = {0: "없음", 1: "탐색", 2: "획득", 3: "불가", 4: "코드락", 5: "코드락", 6: "락", 7: "락"}


def main():
    ser = serial.Serial(PORT, 9600, timeout=0.5)
    print(f"위성 신호 모니터: {PORT}  (Ctrl+C로 종료)")
    print("야외 기준: C/N0 35 이상 위성 4개 이상이면 곧 fix가 잡힙니다.\n")
    try:
        while True:
            info = poll_svinfo(ser)
            ts = time.strftime("%H:%M:%S")
            if info is None:
                print(f"[{ts}] UBX 응답 없음")
            else:
                _, svs = info
                active = sorted((s for s in svs if s[1] > 0), key=lambda t: -t[1])
                good = sum(1 for _, cno, _ in active if cno >= 35)
                print(f"[{ts}] 신호 {len(active)}개 / 양호(35dBHz+) {good}개")
                for svid, cno, qual in active:
                    bar = "#" * cno
                    print(f"  SV{svid:3d} {cno:2d} dBHz |{bar:<50s}| {QUALITY.get(qual, qual)}")
            print(flush=True)
            time.sleep(2)
    except KeyboardInterrupt:
        print("종료")
    finally:
        ser.close()


if __name__ == "__main__":
    main()
