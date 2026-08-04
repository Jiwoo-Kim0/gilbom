"""JetsonImu(MPU6050) 단위 테스트 — 가짜 I2C 버스로 필터·축 매핑·게이트를 검증.

숫자 방침: 스케일(16384, 131)은 MPU6050 프로토콜 상수라 테스트에 직접 쓰고,
튜닝값(alpha, 영점 샘플 수)은 config.yaml에서 읽는다.
"""

import struct
from typing import List

import pytest

from hardware.jetson.imu import JetsonImu

_ACCEL_LSB_PER_G = 16384.0
_GYRO_LSB_PER_DPS = 131.0
_REGISTER_WHO_AM_I = 0x75
_CLONE_WHO_AM_I = 0x72   # 0731 실측 — 우리 칩이 실제로 돌려주는 값

# 기수 30° 들림의 중력 성분: 전방축 +sin30, 상방축 +cos30
_NOSE_UP_30_G = (0.5, 0.0, 0.8660254)


def frame(acceleration_g=(0.0, 0.0, 1.0), angular_rate_dps=(0.0, 0.0, 0.0)) -> bytes:
    """물리량 → MPU6050 14바이트 데이터 블록 (온도는 0으로 채움)."""
    values = [round(component * _ACCEL_LSB_PER_G) for component in acceleration_g]
    values.append(0)
    values.extend(round(component * _GYRO_LSB_PER_DPS) for component in angular_rate_dps)
    return struct.pack(">7h", *values)


class FakeSmbus:
    """스크립트된 데이터 블록을 차례로 돌려주는 가짜 SMBus. 마지막 블록은 반복."""

    def __init__(self, frames: List[bytes], who_am_i: int = _CLONE_WHO_AM_I):
        self.frames = list(frames)
        self.who_am_i = who_am_i
        self.written = []
        self.read_failures_remaining = 0

    def read_byte_data(self, address: int, register: int) -> int:
        assert register == _REGISTER_WHO_AM_I
        return self.who_am_i

    def write_byte_data(self, address: int, register: int, value: int) -> None:
        self.written.append((register, value))

    def read_i2c_block_data(self, address: int, register: int, length: int) -> List[int]:
        if self.read_failures_remaining > 0:
            self.read_failures_remaining -= 1
            raise OSError(121, "Remote I/O error")
        block = self.frames.pop(0) if len(self.frames) > 1 else self.frames[0]
        return list(block)


class FakeClock:
    def __init__(self, start: float = 100.0):
        self.now = start

    def __call__(self) -> float:
        return self.now


def sequence_bus(config: dict, motion_frame: bytes) -> FakeSmbus:
    """영점 구간(정지)과 첫 read(초기화)까지 정지 프레임, 그 뒤로 motion_frame 반복."""
    samples = config["hardware"]["imu"]["gyro_bias_samples"]
    return FakeSmbus([frame()] * (samples + 1) + [motion_frame])


def build_imu(config: dict, fake_bus: FakeSmbus, clock: FakeClock,
              forward_axis: str = None, up_axis: str = None,
              yaw_sign: int = 1) -> JetsonImu:
    """물리 검증이 목적이라 실기 보정(클론 yaw 반전)은 기본 +1로 끄고 만든다."""
    imu_config = config["hardware"]["imu"]
    return JetsonImu(
        bus=imu_config["bus"], address=imu_config["address"],
        forward_axis=forward_axis or imu_config["forward_axis"],
        up_axis=up_axis or imu_config["up_axis"],
        gyro_bias_samples=imu_config["gyro_bias_samples"],
        complementary_alpha=imu_config["complementary_alpha"],
        yaw_sign=yaw_sign,
        bus_factory=lambda bus_number: fake_bus, clock=clock,
    )


class TestGate:
    def test_no_device_reads_none_and_gives_up_after_one_attempt(self, config):
        attempts = []

        def failing_factory(bus_number: int):
            attempts.append(bus_number)
            raise OSError(2, "No such device")

        imu_config = config["hardware"]["imu"]
        imu = JetsonImu(
            bus=imu_config["bus"], address=imu_config["address"],
            forward_axis=imu_config["forward_axis"], up_axis=imu_config["up_axis"],
            gyro_bias_samples=imu_config["gyro_bias_samples"],
            complementary_alpha=imu_config["complementary_alpha"],
            yaw_sign=imu_config["yaw_sign"],
            bus_factory=failing_factory, clock=FakeClock(),
        )
        assert imu.read() is None
        assert imu.read() is None
        assert len(attempts) == 1   # 실패 후 매 주기 버스를 두드리지 않는다

    def test_clone_who_am_i_is_accepted(self, config):
        fake_bus = FakeSmbus([frame()], who_am_i=_CLONE_WHO_AM_I)
        imu = build_imu(config, fake_bus, FakeClock())
        assert imu.read() is not None

    def test_transient_read_error_skips_one_cycle_then_recovers(self, config):
        fake_bus = FakeSmbus([frame()])
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock)
        assert imu.read() is not None
        fake_bus.read_failures_remaining = 1
        clock.now += 0.1
        assert imu.read() is None       # 이번 주기만 게이트 닫힘
        clock.now += 0.1
        assert imu.read() is not None   # 다음 주기 복구


class TestPitch:
    def test_flat_and_stationary_reads_near_zero_pitch(self, config):
        imu = build_imu(config, FakeSmbus([frame()]), FakeClock())
        reading = imu.read()
        assert reading.pitch_deg == pytest.approx(0.0, abs=0.01)
        assert reading.yaw_deg == 0.0

    def test_nose_up_gravity_gives_positive_pitch(self, config):
        # 평지에서 영점 후 기수 30° 들림 — 리셋 갭을 넘겨 가속도 재초기화 경로로 확인
        fake_bus = sequence_bus(config, frame(acceleration_g=_NOSE_UP_30_G))
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock)
        imu.read()
        clock.now += 1.0
        assert imu.read().pitch_deg == pytest.approx(30.0, abs=0.01)

    def test_nose_down_gives_negative_pitch(self, config):
        fake_bus = sequence_bus(config, frame(acceleration_g=(-0.5, 0.0, 0.8660254)))
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock)
        imu.read()
        clock.now += 1.0
        assert imu.read().pitch_deg == pytest.approx(-30.0, abs=0.01)

    def test_remapped_forward_axis_reads_same_pitch(self, config):
        # 장착을 90° 돌려 전방이 -y가 되면, 같은 기울기가 -y축 중력으로 나타난다
        fake_bus = sequence_bus(config, frame(acceleration_g=(0.0, -0.5, 0.8660254)))
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock, forward_axis="-y")
        imu.read()
        clock.now += 1.0
        assert imu.read().pitch_deg == pytest.approx(30.0, abs=0.01)

    def test_gyro_and_accel_disagreement_follows_alpha(self, config):
        # 가속도는 0°를 주장, 자이로는 +10dps 기수 들림 회전을 주장.
        # +pitch 회전축 = 전방(+x)×상방(+z) = -y → 자이로 y에 -10dps.
        fake_bus = sequence_bus(config, frame(angular_rate_dps=(0.0, -10.0, 0.0)))
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock)
        imu.read()                       # pitch=0으로 초기화
        clock.now += 0.1
        reading = imu.read()             # 자이로만 0.1초 × 10dps = 1° 주장
        alpha = config["hardware"]["imu"]["complementary_alpha"]
        assert reading.pitch_deg == pytest.approx(alpha * 1.0, abs=0.01)


class TestYaw:
    def test_left_turn_positive_and_integrates_rate_over_time(self, config):
        # 상방축(+z) 자이로 +20dps = 좌회전. 0.5초 → +10°
        fake_bus = sequence_bus(config, frame(angular_rate_dps=(0.0, 0.0, 20.0)))
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock)
        imu.read()
        clock.now += 0.25
        imu.read()
        clock.now += 0.25
        assert imu.read().yaw_deg == pytest.approx(10.0, abs=0.1)

    def test_stationary_bias_is_calibrated_out_of_yaw(self, config):
        # 영점 구간과 이후 모두 같은 바이어스(-0.5dps, 0731 실측 수준) → yaw는 0 유지
        fake_bus = FakeSmbus([frame(angular_rate_dps=(0.0, 0.0, -0.5))])
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock)
        reading = imu.read()
        for _ in range(10):
            clock.now += 0.1
            reading = imu.read()
        assert reading.yaw_deg == pytest.approx(0.0, abs=1e-9)

    def test_zero_yaw_resets_accumulation(self, config):
        fake_bus = sequence_bus(config, frame(angular_rate_dps=(0.0, 0.0, 20.0)))
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock)
        imu.read()
        clock.now += 0.25
        assert imu.read().yaw_deg != 0.0
        imu.zero_yaw()
        clock.now += 0.001
        assert imu.read().yaw_deg == pytest.approx(0.0, abs=0.1)

    def test_long_gap_resets_filter_instead_of_stale_integration(self, config):
        motion = frame(acceleration_g=_NOSE_UP_30_G,
                       angular_rate_dps=(0.0, 0.0, 20.0))
        fake_bus = sequence_bus(config, motion)
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock)
        imu.read()
        clock.now += 5.0                 # 루프가 5초 멈췄다 재개된 상황
        reading = imu.read()
        # 공백 구간의 자이로 적분(20dps×5s=100°)을 yaw에 이어 붙이면 안 된다
        assert reading.yaw_deg == pytest.approx(0.0, abs=1e-9)
        # pitch는 가속도로 재초기화 (30° 기울기)
        assert reading.pitch_deg == pytest.approx(30.0, abs=0.01)


class TestStartupReference:
    def test_startup_tilt_becomes_zero_reference(self, config):
        # 출발 자세(기수 30° 들림 = 장착 기울기+바닥)가 영점이 된다.
        # 이후 진짜 평지로 내려오면 상대값 -30°로 보고돼야 한다.
        samples = config["hardware"]["imu"]["gyro_bias_samples"]
        fake_bus = FakeSmbus(
            [frame(acceleration_g=_NOSE_UP_30_G)] * (samples + 1) + [frame()])
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock)
        assert imu.read().pitch_deg == pytest.approx(0.0, abs=0.01)
        clock.now += 1.0
        assert imu.read().pitch_deg == pytest.approx(-30.0, abs=0.01)

    def test_yaw_sign_flip_corrects_inverted_clone_gyro(self, config):
        # 클론 칩 실측: 좌회전(반시계) 때 자이로 z가 -로 나온다 → yaw_sign=-1이면
        # 규약(좌회전 +)대로 +가 되어야 한다
        fake_bus = sequence_bus(config, frame(angular_rate_dps=(0.0, 0.0, -20.0)))
        clock = FakeClock()
        imu = build_imu(config, fake_bus, clock, yaw_sign=-1)
        imu.read()
        clock.now += 0.5
        assert imu.read().yaw_deg == pytest.approx(10.0, abs=0.1)


class TestConstruction:
    def test_invalid_axis_string_rejected(self, config):
        with pytest.raises(ValueError):
            build_imu(config, FakeSmbus([frame()]), FakeClock(), forward_axis="x")

    def test_parallel_axes_rejected(self, config):
        with pytest.raises(ValueError):
            build_imu(config, FakeSmbus([frame()]), FakeClock(),
                      forward_axis="+z", up_axis="+z")

    def test_invalid_yaw_sign_rejected(self, config):
        with pytest.raises(ValueError):
            build_imu(config, FakeSmbus([frame()]), FakeClock(), yaw_sign=0)
