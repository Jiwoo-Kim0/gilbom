"""IMU 장착 방향 실기 검증 — 실물 JetsonImu 드라이버로 pitch/yaw를 실시간 출력한다.

사용법 (저장소 루트에서):
    python scripts/imu_check.py [지속시간초]

검증 절차 (config.yaml의 forward_axis/up_axis가 맞는지 사람 눈으로 확인):
  1. 시작 후 ~1초는 손대지 말 것 — 자이로 영점(정지 필수) 구간
  2. 차 앞쪽을 들어올린다        → pitch가 +로 커져야 함 (오르막 규약)
  3. 차를 위에서 봐서 반시계(좌회전)로 돌린다 → yaw가 +로 커져야 함
부호가 반대면 config.yaml hardware.imu의 forward_axis/up_axis 부호를 뒤집는다.
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import logging

from hardware.jetson.imu import JetsonImu
from main import load_config

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")


def main() -> None:
    duration_sec = float(sys.argv[1]) if len(sys.argv) > 1 else 30.0
    imu_config = load_config()["hardware"]["imu"]
    imu = JetsonImu(
        bus=imu_config["bus"], address=imu_config["address"],
        forward_axis=imu_config["forward_axis"], up_axis=imu_config["up_axis"],
        gyro_bias_samples=imu_config["gyro_bias_samples"],
        complementary_alpha=imu_config["complementary_alpha"],
        yaw_sign=imu_config["yaw_sign"],
        # 실주행과 **같은 pitch** 를 보여야 한다 — 장착 보정을 빼먹으면 이 화면만
        # 오프셋만큼 다른 값을 내고, 경사 임계를 그 숫자로 맞추게 된다.
        mount_pitch_offset_deg=imu_config["mount_pitch_offset_deg"],
        zero_max_rate_dps=imu_config["zero_max_rate_dps"],
    )
    print(__doc__)
    deadline = time.monotonic() + duration_sec
    while time.monotonic() < deadline:
        reading = imu.read()
        if reading is None:
            print("reading=None — 셋업 실패 또는 일시 오류 (로그 확인)")
            time.sleep(1.0)
            continue
        print(f"\rpitch {reading.pitch_deg:+7.2f}°   yaw {reading.yaw_deg:+8.2f}°   ",
              end="", flush=True)
        time.sleep(0.05)
    print()


if __name__ == "__main__":
    main()
