"""IMU 실물 구현 — MPU6050 클론 (버스 1, 0x68). 장착·축 방향은 실측으로 확정됨.

칩 특성 (방향 검증에서 확인된 사실):
  - WHO_AM_I가 0x68이 아닌 0x72를 돌려주는 클론 칩. 레지스터 맵·데이터는 정품과
    동일하게 동작 — WHO_AM_I 불일치는 경고 로그만 남기고 진행한다.
  - 클론의 z축 자이로가 오른손 규칙과 반대로 돈다(좌회전이 음수) — config의
    yaw_sign(-1)으로 보정한다. x·y축(pitch 경로)은 정방향 확인됨.
  - 정지 자이로 바이어스가 커서 보정 없이는 yaw를 못 쓴다. 첫 read()에서
    gyro_bias_samples 만큼 정지 샘플링으로 영점을 잡는다 (~0.4초 블록,
    주행은 IDLE에서 시작하므로 정지 전제가 성립).

좌표 규약 (config.yaml hardware.imu의 forward_axis/up_axis가 단일 출처):
  - pitch_deg: 기수 들림(오르막) 양수 — 가속도의 중력 성분과 자이로(전방×상방 축)
    상보필터. 인터페이스 규약(ImuReading)과 일치. 절대 수평이 아니라 **출발 자세
    대비 상대값**이다: 영점 구간에서 가속도 평균으로 기준면을 잡아 장착 기울기와
    출발 지점 바닥 기울기를 함께 흡수한다. 출발지가 경사면이면 SLOPE 판정 기준도
    그만큼 밀린다는 뜻이니 시연 출발은 평지에서 할 것.
  - yaw_deg: 위에서 봤을 때 반시계(좌회전) 양수 — 상방 축 자이로 적분 누적.
    적분 드리프트가 쌓이므로 소비처(AVOID 복귀·DEAD_END 회차)는 기동 시작 시점에
    zero_yaw()로 재영점을 잡고 상대각으로만 쓸 것.

센서 미배선이면 첫 read()의 셋업 실패를 한 번만 로그하고 이후 항상 None을 돌려
IMU 게이트를 닫힌 채 유지한다 — 종전 골격과 동일한 안전 동작. 주행 중 일시적인
버스 오류는 그 주기만 None이고 다음 주기에 재시도한다.
"""

import logging
import struct
import time
from math import atan2, degrees
from typing import Callable, Optional, Tuple

from hardware.interfaces import Imu, ImuReading

logger = logging.getLogger(__name__)

# MPU6050 레지스터·스케일 (프로토콜 상수, 튜닝값 아님)
_REGISTER_POWER_MANAGEMENT_1 = 0x6B
_REGISTER_WHO_AM_I = 0x75
_REGISTER_DATA_START = 0x3B       # accel x,y,z / 온도 / gyro x,y,z — 16비트 빅엔디언 14바이트
_CLOCK_SOURCE_GYRO_X_PLL = 0x01   # 슬립 해제 + 데이터시트 권장 클럭 소스
_ACCEL_LSB_PER_G = 16384.0        # 기본 풀스케일 ±2g
_GYRO_LSB_PER_DPS = 131.0         # 기본 풀스케일 ±250dps
_GENUINE_WHO_AM_I = 0x68

# read() 공백이 이보다 길면 자이로 적분을 버리고 가속도로 pitch를 재초기화한다
# (일시 오류·루프 정지 후 낡은 적분값에 이어 붙는 것을 막는 안전 경계, 튜닝값 아님)
_FILTER_RESET_GAP_SEC = 0.5

_AXIS_VECTORS = {
    "+x": (1.0, 0.0, 0.0), "-x": (-1.0, 0.0, 0.0),
    "+y": (0.0, 1.0, 0.0), "-y": (0.0, -1.0, 0.0),
    "+z": (0.0, 0.0, 1.0), "-z": (0.0, 0.0, -1.0),
}

Vector = Tuple[float, float, float]


def _dot(left: Vector, right: Vector) -> float:
    return left[0] * right[0] + left[1] * right[1] + left[2] * right[2]


def _cross(left: Vector, right: Vector) -> Vector:
    return (left[1] * right[2] - left[2] * right[1],
            left[2] * right[0] - left[0] * right[2],
            left[0] * right[1] - left[1] * right[0])


class JetsonImu(Imu):
    """MPU6050 상보필터 IMU. 셋업·영점은 첫 read()에서 지연 수행한다.

    지연 수행인 이유: main.build_devices는 dry-run에서도 이 클래스를 만들므로
    (미도착 센서의 게이트 닫힘을 그대로 반영하는 설계), 생성 시점에 버스를 열면
    센서 없는 환경에서 조립 자체가 죽는다. bus_factory·clock 주입은 테스트용이며
    실주행은 기본값(smbus2·time.monotonic)을 쓴다.
    """

    def __init__(self, bus: int, address: int,
                 forward_axis: str, up_axis: str,
                 gyro_bias_samples: int, complementary_alpha: float,
                 yaw_sign: int,
                 bus_factory: Optional[Callable[[int], object]] = None,
                 clock: Callable[[], float] = time.monotonic):
        if yaw_sign not in (1, -1):
            raise ValueError(f"yaw_sign은 1 또는 -1이어야 한다: {yaw_sign!r}")
        if forward_axis not in _AXIS_VECTORS or up_axis not in _AXIS_VECTORS:
            raise ValueError(f"IMU 축 지정이 잘못됨: forward={forward_axis!r} up={up_axis!r} "
                             f"(허용: {sorted(_AXIS_VECTORS)})")
        self._forward: Vector = _AXIS_VECTORS[forward_axis]
        self._up: Vector = _AXIS_VECTORS[up_axis]
        if _dot(self._forward, self._up) != 0.0:
            raise ValueError(f"forward_axis({forward_axis})와 up_axis({up_axis})는 "
                             "서로 다른 직교 축이어야 한다")
        # 기수 들림(+pitch) 회전축은 전방×상방 — 오른손 규칙으로 유도되므로
        # 장착 방향이 바뀌어도 config의 두 축만 고치면 부호가 함께 맞는다.
        self._pitch_rate_axis: Vector = _cross(self._forward, self._up)

        self._bus_number = bus
        self._address = address
        self._gyro_bias_samples = gyro_bias_samples
        self._complementary_alpha = complementary_alpha
        self._yaw_sign = yaw_sign
        self._pitch_offset_deg = 0.0    # 영점 구간에서 출발 자세로 확정된다
        self._bus_factory = bus_factory
        self._clock = clock

        self._bus = None
        self._setup_failed = False
        self._gyro_bias_dps: Vector = (0.0, 0.0, 0.0)
        self._pitch_deg = 0.0
        self._yaw_deg = 0.0
        self._last_timestamp: Optional[float] = None

    # ---------- 공개 인터페이스 ----------

    def read(self) -> Optional[ImuReading]:
        if not self._ensure_ready():
            return None
        try:
            acceleration_g, angular_rate_dps = self._read_scaled()
        except OSError as error:
            logger.warning("[IMU] 측정 읽기 실패 — 이번 주기만 건너뛰고 재시도: %s", error)
            self._last_timestamp = None    # 공백 후 낡은 적분에 이어 붙지 않도록
            return None

        now = self._clock()
        accel_pitch_deg = degrees(atan2(_dot(acceleration_g, self._forward),
                                        _dot(acceleration_g, self._up))) - self._pitch_offset_deg

        if self._last_timestamp is None or now - self._last_timestamp > _FILTER_RESET_GAP_SEC:
            self._pitch_deg = accel_pitch_deg    # 필터 재초기화 (yaw 적분은 그 공백만 유실)
        else:
            interval_sec = now - self._last_timestamp
            pitch_rate_dps = _dot(angular_rate_dps, self._pitch_rate_axis)
            yaw_rate_dps = self._yaw_sign * _dot(angular_rate_dps, self._up)
            alpha = self._complementary_alpha
            self._pitch_deg = (alpha * (self._pitch_deg + pitch_rate_dps * interval_sec)
                               + (1.0 - alpha) * accel_pitch_deg)
            self._yaw_deg += yaw_rate_dps * interval_sec
        self._last_timestamp = now

        return ImuReading(timestamp=now, pitch_deg=self._pitch_deg, yaw_deg=self._yaw_deg)

    def zero_yaw(self) -> None:
        """yaw 누적 재영점 — 기동(AVOID 우회·DEAD_END 회차) 시작 시점에 호출한다."""
        self._yaw_deg = 0.0

    # ---------- 내부 ----------

    def _ensure_ready(self) -> bool:
        if self._bus is not None:
            return True
        if self._setup_failed:
            return False
        try:
            self._setup()
            return True
        except (OSError, ImportError) as error:
            # 미배선/미설치 환경의 정상 경로 — 한 번만 알리고 게이트를 닫힌 채 둔다.
            self._setup_failed = True
            logger.info("[IMU] 셋업 실패, IMU 게이트 닫힘 유지 (배선 후 재기동 필요): %s", error)
            return False

    def _setup(self) -> None:
        if self._bus_factory is not None:
            bus = self._bus_factory(self._bus_number)
        else:
            from smbus2 import SMBus
            bus = SMBus(self._bus_number)
        try:
            who_am_i = bus.read_byte_data(self._address, _REGISTER_WHO_AM_I)
            if who_am_i != _GENUINE_WHO_AM_I:
                logger.warning("[IMU] WHO_AM_I=0x%02x (정품 0x68 아님 — 우리 칩은 0x72 클론, "
                               "동작에는 지장 없어 계속 진행)", who_am_i)
            bus.write_byte_data(self._address, _REGISTER_POWER_MANAGEMENT_1,
                                _CLOCK_SOURCE_GYRO_X_PLL)
            self._bus = bus
            self._calibrate()
        except Exception:
            self._bus = None
            close = getattr(bus, "close", None)
            if close is not None:
                close()
            raise

    def _calibrate(self) -> None:
        """정지 상태 영점 — 자이로 바이어스와 pitch 기준면(출발 자세)을 함께 잡는다.

        첫 read()를 ~0.4초 블록시키는 유일한 구간. 매 주행 세션(프로세스)마다
        다시 수행되므로 장착 기울기·출발 지점 바닥 기울기가 자동으로 0점이 된다.
        """
        gyro_sum = [0.0, 0.0, 0.0]
        accel_forward_sum = 0.0
        accel_up_sum = 0.0
        for _ in range(self._gyro_bias_samples):
            acceleration_g, angular_rate_dps = self._read_scaled(apply_bias=False)
            for axis_index in range(3):
                gyro_sum[axis_index] += angular_rate_dps[axis_index]
            accel_forward_sum += _dot(acceleration_g, self._forward)
            accel_up_sum += _dot(acceleration_g, self._up)
        count = float(self._gyro_bias_samples)
        self._gyro_bias_dps = (gyro_sum[0] / count, gyro_sum[1] / count, gyro_sum[2] / count)
        self._pitch_offset_deg = degrees(atan2(accel_forward_sum / count,
                                               accel_up_sum / count))
        logger.info("[IMU] 영점 완료 — 자이로 바이어스 (%.2f, %.2f, %.2f) dps, "
                    "pitch 기준면 %.2f°", *self._gyro_bias_dps, self._pitch_offset_deg)

    def _read_scaled(self, apply_bias: bool = True) -> Tuple[Vector, Vector]:
        """가속도(g)·각속도(dps) 1회 측정. 축은 센서 좌표계 그대로."""
        raw = self._bus.read_i2c_block_data(self._address, _REGISTER_DATA_START, 14)
        values = struct.unpack(">7h", bytes(raw))
        acceleration_g = (values[0] / _ACCEL_LSB_PER_G,
                          values[1] / _ACCEL_LSB_PER_G,
                          values[2] / _ACCEL_LSB_PER_G)
        angular_rate_dps = (values[4] / _GYRO_LSB_PER_DPS,
                            values[5] / _GYRO_LSB_PER_DPS,
                            values[6] / _GYRO_LSB_PER_DPS)
        if apply_bias:
            angular_rate_dps = (angular_rate_dps[0] - self._gyro_bias_dps[0],
                                angular_rate_dps[1] - self._gyro_bias_dps[1],
                                angular_rate_dps[2] - self._gyro_bias_dps[2])
        return acceleration_g, angular_rate_dps
