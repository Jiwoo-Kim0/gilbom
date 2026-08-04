#!/usr/bin/env python3
"""u-blox GPS 하드웨어 진단: UBX-MON-HW(RF/안테나 상태) + UBX-NAV-SVINFO(위성별 신호세기)."""

import struct
import sys
import time

import serial

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM0"


def ubx_packet(cls, mid, payload=b""):
    body = bytes([cls, mid]) + struct.pack("<H", len(payload)) + payload
    ck_a = ck_b = 0
    for b in body:
        ck_a = (ck_a + b) & 0xFF
        ck_b = (ck_b + ck_a) & 0xFF
    return b"\xb5\x62" + body + bytes([ck_a, ck_b])


def read_ubx(ser, want_cls, want_id, timeout=3.0):
    """스트림에서 원하는 UBX 응답 하나를 찾아 payload 반환."""
    end = time.monotonic() + timeout
    buf = b""
    while time.monotonic() < end:
        buf += ser.read(256)
        i = buf.find(b"\xb5\x62")
        while i != -1 and len(buf) >= i + 6:
            cls, mid, length = buf[i + 2], buf[i + 3], struct.unpack_from("<H", buf, i + 4)[0]
            if len(buf) < i + 8 + length:
                break
            if cls == want_cls and mid == want_id:
                return buf[i + 6 : i + 6 + length]
            buf = buf[i + 2 :]
            i = buf.find(b"\xb5\x62")
    return None


def poll_svinfo(ser):
    """(채널 수, [(svid, cno, quality), ...]) 반환. 실패 시 None."""
    ser.reset_input_buffer()
    ser.write(ubx_packet(0x01, 0x30))
    p = read_ubx(ser, 0x01, 0x30)
    if p is None:
        return None
    num_ch = p[4]
    svs = []
    for i in range(num_ch):
        off = 8 + 12 * i
        svid, qual, cno = p[off + 1], p[off + 3], p[off + 4]
        svs.append((svid, cno, qual))
    return num_ch, svs


ANT_STATUS = {0: "INIT", 1: "DONTKNOW", 2: "OK", 3: "SHORT(단락!)", 4: "OPEN(단선!)"}
ANT_POWER = {0: "OFF", 1: "ON", 2: "DONTKNOW"}


def main():
    ser = serial.Serial(PORT, 9600, timeout=0.5)
    print(f"== u-blox 진단: {PORT} ==\n")

    # 1) MON-HW: RF 프론트엔드 / 안테나 / 재밍
    ser.reset_input_buffer()
    ser.write(ubx_packet(0x0A, 0x09))
    p = read_ubx(ser, 0x0A, 0x09)
    if p is None:
        print("MON-HW 응답 없음 (UBX 통신 실패)")
    else:
        noise, agc = struct.unpack_from("<HH", p, 16)
        a_status, a_power, flags = p[20], p[21], p[22]
        jam_ind = p[45]
        print("[RF/안테나 상태 - MON-HW]")
        print(f"  안테나 상태   : {ANT_STATUS.get(a_status, a_status)}")
        print(f"  안테나 전원   : {ANT_POWER.get(a_power, a_power)}")
        print(f"  노이즈 레벨   : {noise} (정상 대략 50~150)")
        print(f"  AGC 카운트    : {agc} (0~8191, 극단값이면 RF 이상)")
        print(f"  재밍 지표     : {jam_ind}/255 (높을수록 간섭 심함)")
        print(f"  RTC/재밍플래그: 0x{flags:02x}")

    # 2) NAV-SVINFO: 채널별 위성 추적 상태
    info = poll_svinfo(ser)
    print("\n[위성 추적 - NAV-SVINFO]")
    if info is None:
        print("  응답 없음")
    else:
        num_ch, svs = info
        tracked = [(s, c, q) for s, c, q in svs if c > 0 or q > 0]
        print(f"  탐색 채널 수: {num_ch}")
        if tracked:
            print(f"  신호 감지된 위성 {len(tracked)}개:")
            for svid, cno, qual in sorted(tracked, key=lambda t: -t[1]):
                print(f"    SV{svid:3d}  C/N0={cno:2d} dBHz  quality={qual}")
        else:
            print("  신호가 감지된 위성 없음 (C/N0 전부 0)")

    ser.close()


if __name__ == "__main__":
    main()
