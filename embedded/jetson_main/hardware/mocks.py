"""하드웨어 mock — 실물 없이 도는 가짜 장치들 (dry-run 기본 모드와 테스트가 사용).

명령을 로그로만 남기고 마지막 상태를 필드로 노출해 테스트가 검증할 수 있게 한다.
같은 값이 반복되면 로그를 생략해 20Hz 루프에서도 출력이 읽힌다.
"""

import logging
import time
from typing import Callable, Optional

from hardware.interfaces import DriveMotor, Imu, ImuReading, SteeringServo

logger = logging.getLogger(__name__)


class MockDriveMotor(DriveMotor):
    """구동 모터 mock — 스로틀/정지/브레이크를 기록·로그."""

    def __init__(self) -> None:
        self.last_throttle_pct: float = 0.0
        self.braking: bool = False

    def set_throttle_pct(self, throttle_pct: float) -> None:
        value = float(throttle_pct)
        if value != self.last_throttle_pct or self.braking:
            logger.info("[모터(모의)] 스로틀 %+.1f%%", value)
        self.last_throttle_pct = value
        self.braking = False

    def stop(self) -> None:
        if self.last_throttle_pct != 0.0 or self.braking:
            logger.info("[모터(모의)] 정지(무동력)")
        self.last_throttle_pct = 0.0
        self.braking = False

    def brake(self) -> None:
        if not self.braking:
            logger.info("[모터(모의)] 숏브레이크 유지")
        self.last_throttle_pct = 0.0
        self.braking = True


class MockSteeringServo(SteeringServo):
    """조향 서보 mock — 클램프는 부모 템플릿 메서드가 이미 강제한 뒤 들어온다."""

    def __init__(self, center_deg: float,
                 left_offset_limit_deg: float, right_offset_limit_deg: float):
        super().__init__(center_deg, left_offset_limit_deg, right_offset_limit_deg)
        self.last_offset_deg: Optional[float] = None

    def _apply_offset_deg(self, clamped_offset_deg: float) -> None:
        if clamped_offset_deg != self.last_offset_deg:
            logger.info("[조향(모의)] 오프셋 %+.1f° (절대 %.1f°)",
                        clamped_offset_deg, self.absolute_angle_deg(clamped_offset_deg))
        self.last_offset_deg = clamped_offset_deg


class MockImu(Imu):
    """IMU mock — 지정한 pitch/yaw를 계속 보고한다.

    필드를 직접 바꿔 시나리오를 만들 수 있다. 이 mock을 꽂는 것만으로
    (read()가 None이 아니게 되는 것만으로) SLOPE 등 IMU 게이트 경로가 열린다 —
    실물 IMU 도착 시의 활성화 방식과 동일함을 테스트가 이걸로 증명한다.
    """

    def __init__(self, pitch_deg: float = 0.0, yaw_deg: float = 0.0,
                 clock: Callable[[], float] = time.monotonic):
        self.pitch_deg = pitch_deg
        self.yaw_deg = yaw_deg
        self._clock = clock

    def read(self) -> Optional[ImuReading]:
        return ImuReading(timestamp=self._clock(),
                          pitch_deg=self.pitch_deg, yaw_deg=self.yaw_deg)
