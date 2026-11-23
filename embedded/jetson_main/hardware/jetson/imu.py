"""IMU 실물 구현 — MPU6050 클론 (버스 1, 0x68). 장착·축 방향은 실측으로 확정됨.

칩 특성 (방향 검증에서 확인된 사실):
  - WHO_AM_I가 0x68이 아닌 0x72를 돌려주는 클론 칩. 레지스터 맵·데이터는 정품과
    동일하게 동작 — WHO_AM_I 불일치는 경고 로그만 남기고 진행한다.
  - 클론의 z축 자이로가 오른손 규칙과 반대로 돈다(좌회전이 음수) — config의
    yaw_sign(-1)으로 보정한다. x·y축(pitch 경로)은 정방향 확인됨.
  - 정지 자이로 바이어스가 커서 보정 없이는 yaw를 못 쓴다. 첫 read()에서
    gyro_bias_samples 만큼 정지 샘플링으로 영점을 잡고(~0.4초 블록, 주행은 IDLE에서
    시작하므로 정지 전제가 성립), **출발 직전 터치 영점**에서 다시 잡는다
    (begin_zeroing/finish_zeroing — 온도로 변하는 값이라 자주 잴수록 좋다).

값의 성격이 셋으로 갈린다 — 이 구분이 이 파일의 핵심이다 (docs/IMU-ZERO-REFERENCE.md):
  · **장착 기울기**  IMU가 차체에 삐뚤게 붙은 각도. 차체 기준이라 상수다 →
    config 의 mount_pitch_offset_deg 로 **받는다. 측정하지 않는다.**
  · **자이로 바이어스** 기준점이 아니라 센서 오차. 정지 중 참값이 0인 것이 물리적으로
    확실하므로 **어느 경사에서 재도 유효**하고, 온도로 변하니 매 시작 다시 잰다.
  · **yaw**  외부 기준이 없다(6축, 지자기 없음) → 소비처가 진입 시점 값과의 차이로만 쓴다.

좌표 규약 (config.yaml hardware.imu의 forward_axis/up_axis가 단일 출처):
  - pitch_deg: 기수 들림(오르막) 양수 — 가속도의 중력 성분과 자이로(전방×상방 축)
    상보필터. 인터페이스 규약(ImuReading)과 일치. **중력 기준 절대값**이다:
    빼는 것은 장착 기울기(상수)뿐이라 어디서 켜든 같은 지면이면 같은 값이 나온다.
    출발 자세를 0으로 잡지 **않는다** — 그러면 출발선 경사가 매번 지워져, 15° 경사에서
    출발한 로봇이 '나는 평지에 있다'고 읽고 등판 보정도 하강 안전 정지도 걸리지 않는다
    (docs/IMU-ZERO-REFERENCE.md §5). config driving.slope 의 임계는 전부 중력 기준
    물리량이므로 기준도 중력이어야 한다.
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


class _GyroZeroing:
    """정지 자이로 표본 누적기 — 평균(바이어스)과 함께 **흔들렸는지**도 본다.

    진폭(peak-to-peak)을 보는 이유는 바이어스가 아직 미확정이라 절대 크기로는
    정지 여부를 못 가리기 때문이다: 바이어스가 3 dps 인 센서는 가만히 있어도 3 dps 를
    읽는다. 반면 흔들리면 값이 **변한다** — 정지의 증거는 크기가 아니라 일정함이다.
    """

    def __init__(self) -> None:
        self.count = 0
        self._sum = [0.0, 0.0, 0.0]
        self._min = [float("inf")] * 3
        self._max = [float("-inf")] * 3

    def add(self, angular_rate_dps: Vector) -> None:
        self.count += 1
        for axis in range(3):
            value = angular_rate_dps[axis]
            self._sum[axis] += value
            self._min[axis] = min(self._min[axis], value)
            self._max[axis] = max(self._max[axis], value)

    def mean(self) -> Vector:
        count = float(max(1, self.count))
        return (self._sum[0] / count, self._sum[1] / count, self._sum[2] / count)

    def peak_to_peak(self) -> float:
        return max(self._max[axis] - self._min[axis] for axis in range(3))


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
                 mount_pitch_offset_deg: float = 0.0,
                 zero_max_rate_dps: float = 2.0,
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
        # **상수다.** 장착 기울기는 차체 기준이라 로봇을 어디에 놓든 변하지 않는다 —
        # 그래서 재지 않고 config 에서 받는다 (scripts/imu_calibrate.py 로 1회 실측).
        self._pitch_offset_deg = float(mount_pitch_offset_deg)
        self._zero_max_rate_dps = float(zero_max_rate_dps)
        self._bus_factory = bus_factory
        self._clock = clock

        self._bus = None
        self._setup_failed = False
        self._gyro_bias_dps: Vector = (0.0, 0.0, 0.0)
        self._pitch_deg = 0.0
        self._yaw_deg = 0.0
        self._last_timestamp: Optional[float] = None
        self._zeroing: Optional[_GyroZeroing] = None

    # ---------- 공개 인터페이스 ----------

    def read(self) -> Optional[ImuReading]:
        if not self._ensure_ready():
            return None
        try:
            acceleration_g, raw_rate_dps = self._read_scaled()
        except OSError as error:
            logger.warning("[IMU] 측정 읽기 실패 — 이번 주기만 건너뛰고 재시도: %s", error)
            self._last_timestamp = None    # 공백 후 낡은 적분에 이어 붙지 않도록
            return None

        if self._zeroing is not None:
            # 영점 중에도 주행 명령은 계속 나가야 하므로(원격 정지가 살아 있어야 한다)
            # 블록하지 않고 매 틱 한 표본씩 모은다.
            self._zeroing.add(raw_rate_dps)
        angular_rate_dps = (raw_rate_dps[0] - self._gyro_bias_dps[0],
                            raw_rate_dps[1] - self._gyro_bias_dps[1],
                            raw_rate_dps[2] - self._gyro_bias_dps[2])

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

    def begin_zeroing(self) -> bool:
        """정지 자이로 영점 시작 (논블로킹). 센서가 없으면 False — 호출부가 건너뛴다.

        출발 직전에 다시 재는 이유는 바이어스가 **온도로 변하기** 때문이다. 기동 때 잰
        값은 창고에서 잰 값일 수 있고, 그 오차가 그대로 yaw 적분 드리프트가 된다.
        여기서 재는 것은 바이어스뿐이다 — pitch 기준은 상수라 손대지 않는다.
        """
        if not self._ensure_ready():
            return False
        self._zeroing = _GyroZeroing()
        return True

    def finish_zeroing(self) -> bool:
        """모은 표본으로 바이어스를 확정한다. **정지가 아니었으면 False** (버린다).

        움직이는 동안 모은 평균을 바이어스로 박으면 그 회전량이 통째로 '정지 시 오차'가
        되어, 이후 가만히 서 있는 로봇의 yaw 가 계속 흐른다 — 영점을 안 잡느니만 못하다.
        그래서 표본의 축별 진폭(peak-to-peak)을 보고 흔들렸으면 버린다.
        """
        zeroing, self._zeroing = self._zeroing, None
        if zeroing is None or zeroing.count == 0:
            return False
        swing = zeroing.peak_to_peak()
        if swing > self._zero_max_rate_dps:
            logger.warning("[IMU] 영점 실패 — 정지 상태가 아니다 (진폭 %.2f dps > %.2f). "
                           "바이어스를 갱신하지 않는다", swing, self._zero_max_rate_dps)
            return False
        self._gyro_bias_dps = zeroing.mean()
        self._yaw_deg = 0.0     # 소비처는 상대각만 쓰지만 기준을 새로 잡아 둔다
        logger.info("[IMU] 출발 영점 완료 — 자이로 바이어스 (%.2f, %.2f, %.2f) dps "
                    "(표본 %d개, 진폭 %.2f dps)", *self._gyro_bias_dps,
                    zeroing.count, swing)
        return True

    @property
    def zeroing(self) -> bool:
        """영점 표본을 모으는 중인가 (표시·로그용)."""
        return self._zeroing is not None

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
        """기동 영점 — **자이로 바이어스만** 잡는다. pitch 기준면은 잡지 않는다.

        첫 read()를 ~0.4초 블록시키는 유일한 구간이고, 그 첫 read 는 부팅 직후
        IDLE(브레이크)에서 일어나므로 정지 전제가 성립한다. 출발 직전에 터치 영점이
        같은 값을 다시 잡는다 — 여기 값은 그때까지 쓰는 잠정치다.

        pitch 기준면을 여기서 잡지 않는 이유가 이 파일에서 가장 중요한 결정이다:
        그 순간의 자세를 0으로 놓으면 출발 지점의 경사가 통째로 0점에 박혀,
        평지에 나왔을 때 반대 부호의 경사로 읽힌다 (docs/IMU-ZERO-REFERENCE.md §1).
        """
        gyro_sum = [0.0, 0.0, 0.0]
        for _ in range(self._gyro_bias_samples):
            _, angular_rate_dps = self._read_scaled()
            for axis_index in range(3):
                gyro_sum[axis_index] += angular_rate_dps[axis_index]
        count = float(self._gyro_bias_samples)
        self._gyro_bias_dps = (gyro_sum[0] / count, gyro_sum[1] / count,
                               gyro_sum[2] / count)
        logger.info("[IMU] 기동 영점 — 자이로 바이어스 (%.2f, %.2f, %.2f) dps "
                    "(장착 기울기 %.2f° 는 config 상수)",
                    *self._gyro_bias_dps, self._pitch_offset_deg)

    def _read_scaled(self) -> Tuple[Vector, Vector]:
        """가속도(g)·각속도(dps) 1회 측정 — **원시값**이다 (바이어스 보정 전).

        보정을 호출부(read)로 미루는 이유는 영점 때문이다: 바이어스를 재는 구간은
        보정 전 값을 봐야 하고, 그 두 경로가 같은 함수를 쓰면 '무엇을 재는지'가 흐려진다.
        """
        raw = self._bus.read_i2c_block_data(self._address, _REGISTER_DATA_START, 14)
        values = struct.unpack(">7h", bytes(raw))
        acceleration_g = (values[0] / _ACCEL_LSB_PER_G,
                          values[1] / _ACCEL_LSB_PER_G,
                          values[2] / _ACCEL_LSB_PER_G)
        angular_rate_dps = (values[4] / _GYRO_LSB_PER_DPS,
                            values[5] / _GYRO_LSB_PER_DPS,
                            values[6] / _GYRO_LSB_PER_DPS)
        return acceleration_g, angular_rate_dps
