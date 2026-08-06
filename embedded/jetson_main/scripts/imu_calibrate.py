#!/usr/bin/env python3
"""장착 기울기 1회 실측 — `config.yaml hardware.imu.mount_pitch_offset_deg` 를 정한다.

**평평한 바닥이 필요 없다.** 같은 자리에서 로봇을 180° 돌려 두 번 재고 평균을 낸다:

    ① 정방향   m1 = 장착 + 지면
    ② 180° 회전 m2 = 장착 − 지면      (지면 기여만 부호가 뒤집힌다)
    장착 기울기 = (m1 + m2) / 2       지면 기울기 = (m1 − m2) / 2

장착 오차는 **차체 기준**이라 로봇을 돌려도 안 변하고, 지면 경사는 **진행 방향 기준**
이라 뒤집힌다. 그래서 평균에서 지면이 상쇄된다 — 강의실 바닥이면 충분하다.

돌리는 동안의 yaw 적분도 함께 본다. 사용자가 어차피 180° 돌리므로 공짜로 두 가지가
검증된다:
  · **부호** — 좌(반시계)로 돌렸는데 음수면 config 의 yaw_sign 이 틀린 것이다
  · **크기** — 180° 를 돌렸는데 적분이 크게 다르면 축 매핑·스케일을 의심해야 한다

이 값은 **차체를 다시 뜯기 전까지 유효하다.** 매 시작 다시 재는 값(자이로 바이어스)은
여기서 정하지 않는다 — 그건 출발 직전 터치 영점이 자동으로 한다
(docs/IMU-ZERO-REFERENCE.md).

    python3 scripts/imu_calibrate.py            # 대화형 2단계 측정
    python3 scripts/imu_calibrate.py --samples 400
"""

import argparse
import os
import sys
import time
from math import atan2, degrees

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from hardware.jetson.imu import _AXIS_VECTORS, _dot  # noqa: E402
from main import load_config  # noqa: E402


def _open_bus(imu_config: dict):
    from smbus2 import SMBus

    bus = SMBus(int(imu_config["bus"]))
    bus.write_byte_data(int(imu_config["address"]), 0x6B, 0x01)  # 슬립 해제
    return bus


def _read_raw(bus, address: int):
    """가속도(g)·각속도(dps) 1회 — imu.py 와 같은 스케일(프로토콜 상수)."""
    import struct

    raw = bus.read_i2c_block_data(address, 0x3B, 14)
    values = struct.unpack(">7h", bytes(raw))
    return ((values[0] / 16384.0, values[1] / 16384.0, values[2] / 16384.0),
            (values[4] / 131.0, values[5] / 131.0, values[6] / 131.0))


def measure_pose(bus, address: int, forward, up, samples: int) -> tuple:
    """정지 자세 1회 측정 → (pitch_deg, 자이로 평균 dps, 진폭 dps).

    pitch 는 **보정 없는 raw** 값이다 — 여기서 정하려는 것이 바로 그 보정값이므로.
    """
    pitch_sum = [0.0, 0.0]
    gyro_sum = [0.0, 0.0, 0.0]
    gyro_min = [float("inf")] * 3
    gyro_max = [float("-inf")] * 3
    for _ in range(samples):
        acceleration_g, rate_dps = _read_raw(bus, address)
        pitch_sum[0] += _dot(acceleration_g, forward)
        pitch_sum[1] += _dot(acceleration_g, up)
        for axis in range(3):
            gyro_sum[axis] += rate_dps[axis]
            gyro_min[axis] = min(gyro_min[axis], rate_dps[axis])
            gyro_max[axis] = max(gyro_max[axis], rate_dps[axis])
        time.sleep(0.002)
    pitch_deg = degrees(atan2(pitch_sum[0] / samples, pitch_sum[1] / samples))
    mean = tuple(value / samples for value in gyro_sum)
    swing = max(gyro_max[axis] - gyro_min[axis] for axis in range(3))
    return pitch_deg, mean, swing


def track_yaw(bus, address: int, up, bias, yaw_sign: int, stop_prompt: str) -> float:
    """엔터를 누를 때까지 yaw 를 적분한다 — 180° 회전의 부호·크기 검증용."""
    import select

    yaw_deg = 0.0
    last = time.monotonic()
    print(stop_prompt, end="", flush=True)
    while True:
        _, rate_dps = _read_raw(bus, address)
        now = time.monotonic()
        yaw_deg += yaw_sign * (_dot(rate_dps, up) - _dot(bias, up)) * (now - last)
        last = now
        if select.select([sys.stdin], [], [], 0.01)[0]:
            sys.stdin.readline()
            return yaw_deg


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", default=None, help="config.yaml 경로")
    parser.add_argument("--samples", type=int, default=300,
                        help="자세 1회당 표본 수 (기본 300 ≈ 1초)")
    args = parser.parse_args()

    config = load_config(args.config) if args.config else load_config()
    imu_config = config["hardware"]["imu"]
    address = int(imu_config["address"])
    forward = _AXIS_VECTORS[imu_config["forward_axis"]]
    up = _AXIS_VECTORS[imu_config["up_axis"]]
    yaw_sign = int(imu_config["yaw_sign"])

    try:
        bus = _open_bus(imu_config)
    except Exception as error:                      # noqa: BLE001 — 사용자에게 그대로 보인다
        print(f"IMU 버스를 열지 못했습니다: {error}")
        print("배선(헤더 27/28·17·25)과 config 의 bus/address 를 확인하세요.")
        raise SystemExit(1)

    print("장착 기울기 측정 — 같은 자리에서 180° 돌려 두 번 잰다 (바닥은 평평하지 않아도 된다)\n")
    input("① 로봇을 바닥에 **정면**으로 놓고 손을 뗀 뒤 엔터 > ")
    m1, bias1, swing1 = measure_pose(bus, address, forward, up, args.samples)
    print(f"   측정 1: pitch {m1:+.2f}°  (자이로 진폭 {swing1:.2f} dps)")
    if swing1 > 4.0:
        print("   ⚠ 흔들렸습니다 — 손을 떼고 다시 하세요")

    turned = track_yaw(bus, address, up, bias1, yaw_sign,
                       "② 제자리에서 **약 180° 돌려** 놓은 뒤 엔터 > ")
    m2, _, swing2 = measure_pose(bus, address, forward, up, args.samples)
    print(f"   측정 2: pitch {m2:+.2f}°  (자이로 진폭 {swing2:.2f} dps)")

    mount = (m1 + m2) / 2.0
    ground = (m1 - m2) / 2.0
    print("\n" + "=" * 64)
    print(f"  장착 기울기 (config 에 넣을 값)   {mount:+.2f}°")
    print(f"  그 바닥의 기울기 (참고)           {ground:+.2f}°")
    print("=" * 64)
    print(f"\n  config.yaml → hardware.imu.mount_pitch_offset_deg: {mount:.2f}\n")

    print(f"  회전 검증: yaw 적분 {turned:+.0f}° (기대: 좌회전이면 +180 근처)")
    if abs(turned) < 90.0:
        print("    ⚠ 크기가 너무 작다 — 축 매핑(up_axis)이나 스케일을 의심하세요")
    elif turned < 0:
        print(f"    ⚠ 좌(반시계)로 돌렸다면 yaw_sign 을 {-yaw_sign} 로 뒤집어야 합니다")
    else:
        print("    부호·크기 모두 정상입니다")
    print(f"\n  참고: 정지 자이로 바이어스 ({', '.join(f'{v:+.2f}' for v in bias1)}) dps")
    print("        — 이 값은 config 에 넣지 않는다. 온도로 변하므로 출발 직전")
    print("          터치 영점이 매번 다시 잰다 (hardware.imu.zero_sec).")


if __name__ == "__main__":
    main()
