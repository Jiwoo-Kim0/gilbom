"""주행 상태 정의 — FSM 상태 열거형, 주행 명령 데이터 타입, 전이 테이블(코드판).

이 패키지(driving/)는 순수 계산만 한다: I/O·time.sleep·시계 접근 금지.
전이 테이블의 mermaid 다이어그램판은 README.md에 있다 — 수정 시 둘을 함께 고칠 것.
"""

from dataclasses import dataclass
from enum import Enum, unique
from typing import Tuple


@unique
class DriveState(Enum):
    """FSM 상태 (임베디드 명세서의 상태 정의와 1:1)."""

    IDLE = "IDLE"            # 대기 — 시작 요청·수동 폴백 대기
    PAUSED = "PAUSED"        # 원격 일시정지 — 수동 재개/취소 대기
    CRUISE = "CRUISE"        # 인도 중심선 추종 (코스트맵 방향 선택, 없으면 비례 조향)
    TRACE = "TRACE"          # 점자블록 추종 — 검사 카메라로 상시 탐지 (미션 본체)
    INSPECT = "INSPECT"      # 파손 후보 확정: 정차 → 정밀 판정 → 추종 재개(정차 쿨다운)
    TURN = "TURN"            # 좌/우회전 — 감속 + 깊은 조향 (yaw 있으면 실측, 없으면 시간)
    AVOID = "AVOID"          # 장애물: 정지 없이 좌/우 회피 선회 (IMU 불필요)
    ESCAPE = "ESCAPE"        # 차도 이탈 선회 — 차도가 코리도에 들어옴 → 인도 쪽으로 선회
    SLOPE = "SLOPE"          # 경사 보정 주행 — 오르막 증속·내리막 감속 (IMU 게이트)
    DEAD_END = "DEAD_END"    # 막다른 길 회차 — 좌조향 K턴 반복, yaw 누적 판정 (IMU 게이트)
    ESTOP = "ESTOP"          # 비상 정지 — 수동 해제만 허용


@unique
class AvoidPhase(Enum):
    """AVOID 하위 국면 — 회피 선회의 진행 단계.

    상태 전이가 아니다: DriveState는 내내 AVOID로 유지되고 on_event도 발화하지 않는다.
    **IMU를 요구하지 않는다** — 국면 종료 판정이 전부 시각(장애물 소멸·중심 재정렬)
    + 시간이다. 종전에는 yaw 기반이라 IMU 게이트 뒤였고, IMU 배선이 해제된 상태에서
    모든 장애물이 HOLD(정지 대기)로 떨어졌다 (0803 실외 '자꾸 정지만 함'의 직접 원인).
    """

    HOLD = "HOLD"        # 정지 대기 — **비껴갈 방향 자체가 없을 때**(코스트맵 전 슬롯 ∞)
                         # 와 경사발만. recheck_sec마다 재확인 → 막다른 길 승격 후보
    SWERVE = "SWERVE"    # 벗어나기 — 경로 이득 큰 쪽으로 조향, 장애물이 진행 경로에서
                         # 빠질 때까지. CRUISE에서 장애물을 보면 여기로 바로 들어온다
    PASS = "PASS"        # 통과 — 조향 중립 직진 (전방 카메라는 옆을 못 보므로 시간 판정)
    RETURN = "RETURN"    # 복귀 — 반대 조향으로 인도 중심 오차가 좁혀질 때까지


@unique
class DeadEndPhase(Enum):
    """DEAD_END 회차 하위 국면 — AvoidPhase와 같은 원리(상태 전이 아님).

    큰 좌조향 전진 호와 정렬 후진을 교대하며 yaw를 누적한다 (K턴).
    """

    FORWARD_ARC = "FORWARD_ARC"      # 좌 최대급 조향 전진 — yaw가 실제로 늘어나는 국면
    REVERSE_ALIGN = "REVERSE_ALIGN"  # 조향 정렬 + 후진 — 다음 호를 위한 공간 확보


@dataclass(frozen=True)
class DriveCommand:
    """step()이 내놓는 주행 명령. 실행(장치 쓰기)은 main 루프 한 곳만 담당한다.

    throttle_pct: +전진 / -후진 / 0 정지 (%).
    steering_offset_deg: 조향 중심 기준 오프셋(도). 음수=좌, 양수=우.
        소프트 리밋 클램프는 hardware 계층이 강제한다.
    brake: True면 무동력 정지 대신 숏브레이크 유지(경사 밀림 방지).
    """

    throttle_pct: float
    steering_offset_deg: float
    brake: bool = False


STOP_COMMAND = DriveCommand(throttle_pct=0.0, steering_offset_deg=0.0)
BRAKE_COMMAND = DriveCommand(throttle_pct=0.0, steering_offset_deg=0.0, brake=True)


@dataclass(frozen=True)
class Transition:
    """전이 테이블 한 줄. implemented=False는 골격만 있는 TODO 전이."""

    source: DriveState
    target: DriveState
    trigger: str
    implemented: bool


# 전이 테이블 (코드판). 공통 가드 전이는 대표 source(CRUISE)로 한 행씩 적고,
# 실제 적용 범위는 trigger 문구의 '(모든 상태 공통)'/'(이동 상태 공통)'이 명시한다
# — 이동·검사 상태의 범위가 GUARDED_STATES다. (INSPECT는 정지 상태지만 임무 중이므로
#   인지 두절·경계 소실 시 IDLE 폴백 가드를 똑같이 받는다)
GUARDED_STATES: Tuple[DriveState, ...] = (
    DriveState.CRUISE, DriveState.TRACE, DriveState.INSPECT, DriveState.TURN,
    DriveState.AVOID, DriveState.SLOPE, DriveState.DEAD_END,
)

# 차도 이탈 선회(ESCAPE)를 유발할 수 있는 상태 — 움직이거나 임무 중인 상태 전부.
# ESCAPE 자신은 빠져 있다: 들어 있으면 매 틱 재진입해 entered_at이 리셋되고
# timeout_sec(선회로 못 벗어나면 ESTOP)이 영원히 걸리지 않는다.
# ESCAPE는 GUARDED_STATES에도 없다 — 차도를 벗어나는 중에는 인도 경계가 실제로 안
# 보이는 것이 정상이므로(그래서 벗어나는 것이다) 경계 소실 IDLE 폴백을 걸면 선회가
# 시작하자마자 끊긴다. ESCAPE의 실패 출구는 자신의 timeout_sec → ESTOP이다.
ESCAPE_SOURCE_STATES: Tuple[DriveState, ...] = GUARDED_STATES

# 원격 일시정지(PAUSED)를 받을 수 있는 상태 — 이동·임무 상태 전부 + IDLE.
# ESTOP은 래치라 어떤 입력도 받지 않고, **ESCAPE는 일부러 빠져 있다**: 차도를
# 벗어나는 중에 브레이크를 걸면 로봇이 차도 위에 선 채로 멈춘다. 선회를 끝내고
# CRUISE로 돌아온 뒤에 일시정지하는 것이 맞다. 선회 중 즉시 세워야 하는 상황의
# 출구는 ESTOP이다 — 그쪽은 가드 1이라 상태와 무관하게 항상 먹는다.
# 원격 취소(→IDLE)는 이 목록을 쓰지 않는다: 취소는 임무를 끝내는 것이라
# ESCAPE 중에도 즉시 받는다.
PAUSABLE_STATES: Tuple[DriveState, ...] = GUARDED_STATES + (DriveState.IDLE,)

TRANSITION_TABLE: Tuple[Transition, ...] = (
    # --- 공통 가드 (모든 비ESTOP 상태 → ESTOP/ESCAPE, 이동 상태 → IDLE) ---
    Transition(DriveState.CRUISE, DriveState.ESTOP,
               "코리도가 차도로 덮임(roadway_critical) / 수동 ESTOP 요청 (모든 상태 공통)",
               True),
    Transition(DriveState.CRUISE, DriveState.ESCAPE,
               "차도·횡단보도가 코리도에 진입(roadway_encroachment_risk) → **정지가 아니라** "
               "인도 쪽으로 선회 이탈 (이동·임무 상태 공통)", True),
    Transition(DriveState.ESCAPE, DriveState.CRUISE,
               "차도가 코리도에서 빠진 뒤 clear_sec 유지 → 인도 추종 복귀", True),
    Transition(DriveState.ESCAPE, DriveState.ESTOP,
               "선회 timeout_sec 초과(선회로 벗어나지 못함) → 정지 + 수동 대기", True),
    Transition(DriveState.CRUISE, DriveState.IDLE,
               "인도 경계 소실·인지 두절이 유예창 이상 지속 → 정지 + 수동 폴백 대기 "
               "(이동 상태 공통. 유예 중에는 복구 주행 — 저속 + 소실 시점 조향 유지)", True),
    # --- 원격 미션 제어 (통신 계층) ---
    # 대표 source는 CRUISE지만 실제 범위는 PAUSABLE_STATES다 — ESCAPE·ESTOP은 빠진다.
    Transition(DriveState.CRUISE, DriveState.PAUSED,
               "원격 일시정지 요청(임무 이동 상태 공통) → 브레이크", True),
    Transition(DriveState.IDLE, DriveState.PAUSED,
               "활성 임무의 원격 일시정지(경계 소실 폴백 포함)", True),
    Transition(DriveState.PAUSED, DriveState.CRUISE, "원격 재개 요청 + 인도 확인", True),
    Transition(DriveState.PAUSED, DriveState.IDLE, "원격 임무 취소", True),
    Transition(DriveState.CRUISE, DriveState.IDLE,
               "원격 임무 취소 (IDLE 외 모든 상태 공통)", True),
    # --- 정상 흐름 ---
    Transition(DriveState.IDLE, DriveState.CRUISE, "시작 요청 + 인도 확인", True),
    Transition(DriveState.CRUISE, DriveState.AVOID,
               "진행 경로를 막는 장애물 인식 → **정지 없이** 경로 이득 큰 쪽으로 회피 선회 "
               "(IMU 불필요)", True),
    Transition(DriveState.CRUISE, DriveState.AVOID,
               "코스트맵 전 방향 통행 불가(∞) → 정지 후 재확인 (비껴갈 방향이 없다)", True),
    Transition(DriveState.AVOID, DriveState.CRUISE, "재확인 대기 후 장애물 소멸 → 직진 복귀", True),
    Transition(DriveState.AVOID, DriveState.CRUISE,
               "회피 선회(벗어나기→통과→중심 재정렬 복귀) 완료 / 국면 타임아웃", True),
    Transition(DriveState.CRUISE, DriveState.TRACE,
               "점자블록 발견 확정(confirm_sec 디바운스) → 블록 추종 + 검사 카메라 "
               "상시 탐지(감속 없음). 근접도·정렬 조건 없음 (직전 추종이 타임아웃으로 "
               "끝났을 때만 재추종 쿨다운에 막힌다)", True),
    Transition(DriveState.TRACE, DriveState.CRUISE,
               "블록 소실 확정 → 순찰 복귀 (쿨다운 없음 — 다시 보이면 다시 간다)", True),
    Transition(DriveState.TRACE, DriveState.CRUISE,
               "추종 세션 타임아웃 → 순찰 복귀 + 재추종 쿨다운 (저속 추종 고착 방지 — "
               "추종을 막는 유일한 경우)", True),
    Transition(DriveState.TRACE, DriveState.INSPECT,
               "탐지 모델이 파손 후보 보고 + 정차 쿨다운 밖 → 정차 후 정밀 판정 "
               "(쿨다운 안의 보고는 무시하고 추종을 계속한다)", True),
    Transition(DriveState.INSPECT, DriveState.TRACE,
               "판정 완료 통지 / 타임아웃 + 블록 발견 유지 → **블록 추종 재개** + 정차 "
               "쿨다운 (유도선 하나에 파손이 여럿일 수 있다)", True),
    Transition(DriveState.INSPECT, DriveState.CRUISE,
               "판정 완료 통지 / 타임아웃 + 블록 소실 → 순찰 복귀 + 정차 쿨다운", True),
    Transition(DriveState.CRUISE, DriveState.SLOPE,
               "|pitch| ≥ 진입 임계 (IMU 게이트) → 경사 보정 주행(오르막 증속·내리막 감속)", True),
    Transition(DriveState.SLOPE, DriveState.CRUISE, "|pitch| < 해제 임계 / IMU 소실(게이트 닫힘)", True),
    Transition(DriveState.SLOPE, DriveState.AVOID,
               "경사 중 장애물 인식 / 전 방향 통행 불가 → 즉시 정지(브레이크)", True),
    Transition(DriveState.CRUISE, DriveState.TURN,
               "조향 수요 포화 지속(cruise 상한 초과 confirm_sec) → 감속 회전 (IMU 불필요)", True),
    Transition(DriveState.TURN, DriveState.CRUISE,
               "회전 완료(최소 진행 + 재정렬 — 진행은 yaw 있으면 실측, 없으면 시간) / "
               "yaw 상한·타임아웃", True),
    Transition(DriveState.TURN, DriveState.AVOID, "회전 중 장애물 인식 → 회피 선회", True),
    Transition(DriveState.AVOID, DriveState.DEAD_END,
               "재확인 후에도 전 방향 통행 불가 유지(막다른 길) → 좌조향 K턴 회차 시작 "
               "(IMU 게이트 — 없으면 정지 대기 유지)", True),
    Transition(DriveState.DEAD_END, DriveState.CRUISE,
               "yaw 누적 target−tolerance 도달(회차 완료) / IMU 소실 (IMU 게이트)", True),
    Transition(DriveState.DEAD_END, DriveState.IDLE,
               "회차 타임아웃(물리적으로 갇힘) → 정지 + 수동 대기", True),
    Transition(DriveState.ESTOP, DriveState.IDLE, "수동 해제 요청 (해제만 허용)", True),
)
