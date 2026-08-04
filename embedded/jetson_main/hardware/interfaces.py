"""물리 장치 추상 — 구동 모터 / 조향 서보 / IMU. 상위 계층은 이 모듈만 import 한다.

실물 구현은 hardware/jetson/ 아래에만, 가짜 구현은 hardware/mocks.py에만 둔다.
조향 소프트 리밋 클램프는 이 계층(SteeringServo 템플릿 메서드)에서 강제되므로
상위 로직의 버그가 서보를 물리 한계 밖으로 보낼 수 없다.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class ImuReading:
    """IMU 측정 1회분. 값이 없으면 Imu.read()가 None을 돌려준다 (IMU 게이트의 근거).

    pitch_deg: 전후 기울기. +는 오르막(기수 들림), -는 내리막.
        → SLOPE 진입·해제 판정과 경사 스로틀 프로파일이 소비
    yaw_deg: 기준 대비 방위각 누적, 좌회전(위에서 반시계) 양수.
        → TURN 회전 진행, AVOID 우회의 이탈·원방위 복귀, DEAD_END 회차 누적이
        소비 — 셋 다 절대값이 아니라 '진입 시점 yaw와의 차이'로만 쓴다
        (적분 드리프트가 쌓여도 수 초 스케일의 상대각은 유효하다)
    """

    timestamp: float
    pitch_deg: float
    yaw_deg: float


class DriveMotor(ABC):
    """구동 모터 추상 — 전·후진 스로틀과 정지/숏브레이크."""

    @abstractmethod
    def set_throttle_pct(self, throttle_pct: float) -> None:
        """스로틀 지정. +는 전진, -는 후진, 0은 무동력(코스트 정지)."""

    @abstractmethod
    def stop(self) -> None:
        """무동력 정지 (모터 플로팅 — 평지용)."""

    @abstractmethod
    def brake(self) -> None:
        """숏브레이크 정지 유지 (경사에서 밀림 방지 — SLOPE 하강 처리가 소비)."""


class SteeringServo(ABC):
    """조향 서보 추상. 명령 단위는 '중심 기준 오프셋(도)' — 음수=좌, 양수=우.

    set_offset_deg()가 소프트 리밋 클램프를 강제한 뒤 _apply_offset_deg()를
    호출하는 템플릿 메서드이므로, 모든 구현체(실물·mock)가 클램프를 우회할 수 없다.
    리밋 값 자체는 config.yaml에서 주입된다.
    """

    def __init__(self, center_deg: float,
                 left_offset_limit_deg: float, right_offset_limit_deg: float):
        self._center_deg = float(center_deg)
        self._left_limit = abs(float(left_offset_limit_deg))
        self._right_limit = abs(float(right_offset_limit_deg))

    def clamp_offset_deg(self, offset_deg: float) -> float:
        """소프트 리밋 [-왼쪽한계, +오른쪽한계]로 오프셋을 자른다."""
        return max(-self._left_limit, min(self._right_limit, float(offset_deg)))

    def absolute_angle_deg(self, offset_deg: float) -> float:
        """오프셋 → 서보 절대각. 여기서도 클램프한다 — set_offset_deg를 거치지 않고
        이 메서드만 부른 코드도 물리 한계를 못 넘는다 (관례: 좌=중심보다 작은 각)."""
        return self._center_deg + self.clamp_offset_deg(offset_deg)

    def set_offset_deg(self, offset_deg: float) -> float:
        """조향 명령 진입점 — 클램프를 강제하고 실제 적용값을 돌려준다."""
        clamped = self.clamp_offset_deg(offset_deg)
        self._apply_offset_deg(clamped)
        return clamped

    def center(self) -> None:
        """조향 중립."""
        self.set_offset_deg(0.0)

    @abstractmethod
    def _apply_offset_deg(self, clamped_offset_deg: float) -> None:
        """클램프 완료된 오프셋을 장치에 반영한다 (구현체 전용)."""


class Imu(ABC):
    """IMU 추상. 센서 미장착/미구현이면 read()가 None을 돌려준다.

    주행 계층은 'reading이 None인가'만으로 IMU 게이트를 판정한다. 실물 구현은
    hardware/jetson/imu.py(MPU6050) — 배선이 없으면 None을 돌려 게이트가 닫힌 채
    유지되고, 게이트가 열리면 SLOPE 경사 보정 주행·TURN 회전·AVOID 우회·DEAD_END
    회차가 전부 활성화된다 (FSM 쪽 구현 완료).
    """

    @abstractmethod
    def read(self) -> Optional[ImuReading]:
        """최신 측정값. 데이터가 없으면 None."""
