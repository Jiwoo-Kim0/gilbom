"""주행 상태머신 — 순수 step() + 공통 가드 + on_event 훅.

순수성 계약 (스레드 확장의 전제):
  - step()은 입력(컨텍스트, 세그멘테이션 프레임, IMU 측정, 현재 시각)만으로
    출력(다음 컨텍스트, 주행 명령)을 계산한다.
  - 이 모듈 안에서 I/O·로깅·time.sleep·시계 접근을 절대 하지 않는다.
    모든 대기(재확인 3초 등)는 '현재 시각 - 기준 시각' 비교로만 구현한다.
  - 상태 전이를 외부에 알리는 유일한 출구는 StateMachine의 on_event 훅이다.
    (로깅은 main.py가, 이후 통신(MQTT)은 3단계에서 이 훅을 구독한다)
"""

import math
from dataclasses import dataclass, replace
from typing import Callable, Optional, Tuple

from driving.states import (BRAKE_COMMAND, STOP_COMMAND, AvoidPhase,
                            DeadEndPhase, DriveCommand, DriveState,
                            ESCAPE_SOURCE_STATES, GUARDED_STATES,
                            PAUSABLE_STATES)
from driving.steering_costmap import select_direction
from hardware.interfaces import ImuReading
from perception.interfaces import SegmentationFrame


@dataclass(frozen=True)
class StateMachineParams:
    """주행 튜닝값 묶음 — 전 필드 필수, 값은 config.yaml에서만 온다."""

    cruise_throttle_pct: float
    cruise_steer_gain_deg: float
    cruise_steer_left_max_deg: float    # 좌(음수 오프셋) 상한 크기 — 하드웨어가 비대칭이다
    cruise_steer_right_max_deg: float   # 우(양수 오프셋) 상한 크기 (지속 조향 권장 ≤25°)
    costmap_target_weight: float    # 코스트맵 조향: 목표 방향 이탈 페널티 (비용/ratio)
    costmap_commit_weight: float    # 코스트맵 조향: 직전 선택 이탈 페널티 (채터링 방지)
    steer_rate_deg_per_s: float     # 조향 슬루율 상한 — 전 상태 공통(_limit_steer_rate)
    perception_grace_sec: float     # 인지 두절·경계 소실 유예창 (0이면 즉시 IDLE 폴백)
    recovery_throttle_pct: float    # 경계 소실 유예 중의 복구 주행 스로틀 (정지가 아니다)
    avoid_recheck_sec: float
    avoid_bypass_throttle_pct: float    # 회피 선회 스로틀
    avoid_pass_sec: float               # 통과 직진 시간 (옆은 카메라 사각 — 시간 판정)
    avoid_return_center_abs: float      # 복귀 종료: |인도 중심 오차| ≤ 이 값 (시각 판정)
    avoid_phase_timeout_sec: float      # 국면별 진행 상한 — 초과 시 다음 국면/CRUISE
    escape_throttle_pct: float          # 차도 이탈 선회 스로틀
    escape_steer_left_deg: float        # 좌 이탈 조향 크기 (조향 비대칭이라 따로 든다)
    escape_steer_right_deg: float
    escape_clear_sec: float             # 차도 해소 후 이 시간 유지되면 CRUISE 복귀
    escape_timeout_sec: float           # 선회로 못 벗어나면 이 시간 뒤 ESTOP
    slope_enter_pitch_deg: float
    slope_exit_pitch_deg: float
    slope_uphill_throttle_gain_pct_per_deg: float   # 오르막: cruise + 이득×pitch
    slope_uphill_throttle_max_pct: float
    slope_downhill_throttle_gain_pct_per_deg: float # 내리막: cruise − 이득×|pitch|
    slope_downhill_throttle_min_pct: float
    slope_downhill_brake_pitch_deg: float   # 이보다 급한 내리막은 숏브레이크 정지 유지
    turn_enter_steer_demand_deg: float  # CRUISE 조향 수요(클램프 전) 포화 임계
    turn_confirm_sec: float             # 포화 지속 확정 시간 (노이즈 디바운스)
    turn_throttle_pct: float            # 회전 중 감속 스로틀
    turn_steer_left_deg: float          # 좌회전 조향 크기 — 조향이 비대칭이라 따로 든다
    turn_steer_right_deg: float
    turn_min_progress_yaw_deg: float    # 완료 판정에 요구하는 최소 yaw 진행(회전 방향)
    turn_min_progress_sec: float        # IMU가 없을 때 위 yaw 조건을 대신하는 최소 시간
    turn_max_yaw_deg: float             # |yaw 변화| 강제 종료 상한 (IMU 있을 때만)
    turn_exit_center_abs: float         # 완료 판정: |인도 중심 오차| ≤ 이 값 (재정렬)
    turn_timeout_sec: float             # 무한 회전 방지 안전판
    dead_end_target_yaw_deg: float      # 회차 종료 yaw 누적각 (180)
    dead_end_yaw_tolerance_deg: float   # 완료 판정 여유 — 나머지는 CRUISE 추종이 수렴
    dead_end_steer_deg: float           # 전진 호 좌조향 크기 (좌회전 고정 — config 근거)
    dead_end_throttle_pct: float        # 전진 호·후진 공통 스로틀 크기
    dead_end_forward_sec: float         # 전진 호 1회 시간
    dead_end_reverse_sec: float         # 후진 1회 시간 (후방은 카메라 사각 — 짧게)
    dead_end_timeout_sec: float         # 회차 전체 상한 — 초과 시 IDLE(수동 대기)
    inspect_confirm_sec: float          # 블록 발견/소실 확정에 필요한 지속 시간(양방향 디바운스)
    inspect_enter_proximity: float      # 이 근접도에서 블록 중심 추종 비중이 1이 된다
    inspect_timeout_sec: float          # 판정 통지가 없어도 이 시간 뒤 주행 재개(무한 정지 방지)
    inspect_cooldown_sec: float         # 두 타이머가 공유하는 재시도 금지 간격:
                                        # ① INSPECT 종료 후 재정차 금지 (같은 지점 반복 정차 방지)
                                        # ② 추종 타임아웃 후 재추종 금지 (저속 추종 고착 방지)
    trace_approach_throttle_pct: float  # 블록에 **가는 중**(근접도 < enter_proximity)의 스로틀
    trace_throttle_pct: float           # 블록에 붙었을 때의 탐지 저속 스로틀
    trace_timeout_sec: float            # 한 번의 추종 상한 — 초과 시 CRUISE 복귀(쿨다운 시작)
    start_request_hold_sec: float       # 시작 요청 유지창 — IDLE 탈출은 '인도 확인'도
                                        # 요구하므로, 누른 틱에 인도가 안 보이면 요청이
                                        # 그냥 소멸했다. 이 창 동안 요청을 들고 재시도한다
                                        # (0이면 종전 동작 = 1틱 소비)

    @classmethod
    def from_config(cls, driving: dict) -> "StateMachineParams":
        """config.yaml의 driving 절 → 파라미터 묶음.

        인지 계층의 파라미터 dataclass(PostParams·CenterlineParams·BrailleParams)와
        같은 관례로 변환기를 dataclass 안에 둔다 — 필드를 하나 늘릴 때 정의와 config
        키 매핑이 같은 파일에 붙어 있어야 두 패키지를 오가며 짝을 맞추지 않는다.
        """
        return cls(
            cruise_throttle_pct=driving["cruise"]["throttle_pct"],
            cruise_steer_gain_deg=driving["cruise"]["steer_gain_deg"],
            cruise_steer_left_max_deg=driving["cruise"]["steer_left_max_deg"],
            cruise_steer_right_max_deg=driving["cruise"]["steer_right_max_deg"],
            costmap_target_weight=driving["cruise"]["costmap"]["target_weight"],
            costmap_commit_weight=driving["cruise"]["costmap"]["commit_weight"],
            steer_rate_deg_per_s=driving["steer_rate_deg_per_s"],
            perception_grace_sec=driving["perception_grace_sec"],
            recovery_throttle_pct=driving["recovery_throttle_pct"],
            avoid_recheck_sec=driving["avoid"]["recheck_sec"],
            avoid_bypass_throttle_pct=driving["avoid"]["bypass_throttle_pct"],
            avoid_pass_sec=driving["avoid"]["pass_sec"],
            avoid_return_center_abs=driving["avoid"]["return_center_abs"],
            avoid_phase_timeout_sec=driving["avoid"]["phase_timeout_sec"],
            escape_throttle_pct=driving["escape"]["throttle_pct"],
            escape_steer_left_deg=driving["escape"]["steer_left_deg"],
            escape_steer_right_deg=driving["escape"]["steer_right_deg"],
            escape_clear_sec=driving["escape"]["clear_sec"],
            escape_timeout_sec=driving["escape"]["timeout_sec"],
            slope_enter_pitch_deg=driving["slope"]["enter_pitch_deg"],
            slope_exit_pitch_deg=driving["slope"]["exit_pitch_deg"],
            slope_uphill_throttle_gain_pct_per_deg=(
                driving["slope"]["uphill_throttle_gain_pct_per_deg"]),
            slope_uphill_throttle_max_pct=driving["slope"]["uphill_throttle_max_pct"],
            slope_downhill_throttle_gain_pct_per_deg=(
                driving["slope"]["downhill_throttle_gain_pct_per_deg"]),
            slope_downhill_throttle_min_pct=driving["slope"]["downhill_throttle_min_pct"],
            slope_downhill_brake_pitch_deg=driving["slope"]["downhill_brake_pitch_deg"],
            turn_enter_steer_demand_deg=driving["turn"]["enter_steer_demand_deg"],
            turn_confirm_sec=driving["turn"]["confirm_sec"],
            turn_throttle_pct=driving["turn"]["throttle_pct"],
            turn_steer_left_deg=driving["turn"]["steer_left_deg"],
            turn_steer_right_deg=driving["turn"]["steer_right_deg"],
            turn_min_progress_yaw_deg=driving["turn"]["min_progress_yaw_deg"],
            turn_min_progress_sec=driving["turn"]["min_progress_sec"],
            turn_max_yaw_deg=driving["turn"]["max_yaw_deg"],
            turn_exit_center_abs=driving["turn"]["exit_center_abs"],
            turn_timeout_sec=driving["turn"]["timeout_sec"],
            dead_end_target_yaw_deg=driving["dead_end"]["target_yaw_deg"],
            dead_end_yaw_tolerance_deg=driving["dead_end"]["yaw_tolerance_deg"],
            dead_end_steer_deg=driving["dead_end"]["steer_deg"],
            dead_end_throttle_pct=driving["dead_end"]["throttle_pct"],
            dead_end_forward_sec=driving["dead_end"]["forward_sec"],
            dead_end_reverse_sec=driving["dead_end"]["reverse_sec"],
            dead_end_timeout_sec=driving["dead_end"]["timeout_sec"],
            inspect_confirm_sec=driving["inspect"]["confirm_sec"],
            inspect_enter_proximity=driving["inspect"]["enter_proximity"],
            inspect_timeout_sec=driving["inspect"]["timeout_sec"],
            inspect_cooldown_sec=driving["inspect"]["cooldown_sec"],
            trace_approach_throttle_pct=driving["trace"]["approach_throttle_pct"],
            trace_throttle_pct=driving["trace"]["throttle_pct"],
            trace_timeout_sec=driving["trace"]["timeout_sec"],
            start_request_hold_sec=driving["start_request_hold_sec"],
        )


@dataclass(frozen=True)
class ExternalRequests:
    """외부(조작자·main 루프)발 요청 — step() 입력의 일부로만 소비된다.

    inspection_done: main 루프가 정밀 판정을 마쳤다는 통지 —
        INSPECT의 정상 출구. (검사 실행은 I/O이므로 FSM 밖, main 루프 담당)
    defect_found: TRACE 중 상시 탐지가 파손 후보를 봤다는 통지 —
        TRACE → INSPECT(정차 정밀 판정)의 유일한 트리거. 추론이 I/O라 같은 이유로
        FSM 밖에서 온다.
    pause / resume / cancel: 원격 미션 제어(통신 계층의 commands 소비자가 넣는다).
        조작자 입력과 같은 통로를 쓴다 — FSM은 요청의 출처를 구분하지 않는다.
    """

    start: bool = False
    pause: bool = False
    resume: bool = False
    cancel: bool = False
    estop: bool = False
    estop_release: bool = False
    inspection_done: bool = False
    defect_found: bool = False


@dataclass(frozen=True)
class StateMachineContext:
    """FSM의 전체 가변 상태 스냅샷. step()은 이걸 받아 새 스냅샷을 돌려준다.

    reason: 마지막 전이 사유 (on_event로 외부에 전달됨).
    avoid_brake: AVOID 정지 대기를 브레이크로 유지할지 — SLOPE에서 넘어온 경우 True
        (경사 위 무동력 대기는 후진 활강이 된다). 홀드 명령이 매 틱 소비한다.
    last_inspect_done_at: 마지막 INSPECT 종료 시각 — **정차 재시도** 금지 타이머의
        기준점이다. 이 창 안에서는 탐지가 파손 후보를 또 보고해도 정차하지 않고 추종을
        계속한다(같은 지점에서 정차→판정→정차가 무한 반복되는 것을 막는 유일한 수단.
        거리 기준으로 바꾸려면 오도메트리가 필요하다 — docs/ALGORITHMS.md N1).
        **추종 진입을 막지는 않는다** — 그것이 아래 last_trace_timeout_at의 역할이다.
    last_trace_timeout_at: 추종이 **타임아웃으로** 끝난 시각 — 추종 재진입 금지
        타이머의 기준점. 블록 소실로 끝난 경우에는 기록하지 않는다: 소실은 '블록이
        화면에 없다'는 뜻이고, 그 뒤 블록이 다시 보이면 최우선으로 다시 가야 한다.
        타임아웃만 막는 이유는 유도선이 끝없이 이어질 수 있어서다 — 그 경우 순찰이
        저속 추종에 영구히 갇히고, 그것을 막는 유일한 장치가 이 타이머다.
    trace_started_at: 지금 추종 세션이 시작된 시각 — 추종 타임아웃의 기준점.
        entered_at을 쓰지 않는 이유는 INSPECT를 다녀와도 이 시각이 보존돼야 하기
        때문이다: 정차마다 타임아웃이 리셋되면 파손이 잦은 구간에서 추종 상한이
        사실상 사라진다(INSPECT→TRACE 복귀가 상한을 무한정 미룬다).
    perception_bad_since: 인지 두절·경계 소실이 시작된 시각 — 유예창 타이머의 기준점.
        정상 프레임이 오면 None으로 되돌아간다(깜빡임이 누적되지 않는다).
    braille_stable / braille_pending_since: 디바운스를 통과한 점자블록 발견 판정과,
        아직 확정되지 않은 반대 값이 시작된 시각. 접근·INSPECT 게이트는 raw 값이
        아니라 braille_stable만 본다.
    prev_steer_deg / last_step_at: 직전 틱에 실제로 낸 조향 명령과 그 시각 —
        조향 슬루율 제한의 기준점. 시계를 읽지 않고 now 인자만 쓰므로 순수성 유지.
    last_steer_ratio: 코스트맵 조향의 직전 선택 **방향**(-1~+1) — 히스테리시스
        기준점이다. prev_steer_deg(실제로 낸 각도, 슬루율 제한 후)와는 다른 양이다:
        이쪽은 '어느 슬롯에 커밋했는가'를 기억해 슬롯 간 채터링을 누른다.
        상태 전이를 넘어 보존한다: 정지 후 재출발 시 '마지막으로 가던 방향'을
        약하게 선호하는 것은 무해하고, 전이마다 지우는 배선이 더 위험하다.
    turn_pending_since: CRUISE 조향 수요 포화(회전 필요 후보)가 시작된 시각 —
        TURN 진입 디바운스 기준점. '끊기지 않은 CRUISE 체류' 안에서만 의미가
        있으므로 어떤 상태 전이든 일어나면 _enter가 지운다 (AVOID를 다녀온 뒤
        낡은 시각으로 확정 시간 없이 곧바로 TURN에 드는 것을 막는다).
    turn_direction / turn_entry_yaw_deg: TURN 진입 시 확정한 회전 방향
        (-1=좌, +1=우 — 조향 부호 관례와 동일)과 그 시점의 yaw. 회전 진행량은
        매 틱 imu.yaw_deg와의 차이로 계산한다 (zero_yaw 재영점 불필요).
        yaw가 **None**이면 진입 시 IMU가 없었다는 뜻이고, 그 경우 진행 판정은 시간
        (turn_min_progress_sec)으로 간다 — 0.0을 기준값으로 쓰면 도중에 IMU가 살아날 때
        엉뚱한 진행량이 계산된다(기준 없는 절대 yaw와의 차이가 된다).
    avoid_phase / avoid_phase_since: AVOID 하위 국면(states.AvoidPhase)과 그
        시작 시각. HOLD의 재확인 대기(recheck_sec)도 이 시각 기준이다 — 우회
        도중 재정지로 HOLD에 되돌아오면 대기가 새로 시작돼야 하기 때문
        (entered_at은 AVOID '상태' 진입 시각이라 그 용도로 못 쓴다).
    avoid_direction: 회피 선회 방향(-1=좌, +1=우). 진입 방위(yaw)는 보관하지 않는다 —
        국면 종료 판정이 시각(장애물 소멸·중심 재정렬)이라 필요가 없고, 그래서 회피가
        IMU 없이 돈다.
    escape_direction / escape_clear_since: 차도 이탈 선회 방향과, 차도 위험이 해소된
        시각. clear_since가 escape_clear_sec을 넘기면 CRUISE 복귀다 — 즉시 복귀하면
        코리도 경계에서 위험 판정이 켜졌다 꺼지며 ESCAPE↔CRUISE를 왕복한다.
    dead_end_phase / dead_end_phase_since / dead_end_entry_yaw_deg: DEAD_END
        회차의 하위 국면(전진 호 ↔ 정렬 후진)·국면 시작 시각·회차 시작 방위.
        yaw 누적(현재 yaw − 시작 방위)이 target에 닿으면 회차 완료다.
    """

    state: DriveState
    entered_at: float
    reason: str = ""
    avoid_brake: bool = False
    last_inspect_done_at: Optional[float] = None
    last_trace_timeout_at: Optional[float] = None
    trace_started_at: Optional[float] = None
    perception_bad_since: Optional[float] = None
    braille_stable: bool = False
    braille_pending_since: Optional[float] = None
    prev_steer_deg: float = 0.0
    last_step_at: Optional[float] = None
    last_steer_ratio: Optional[float] = None
    turn_pending_since: Optional[float] = None
    turn_direction: int = 0
    turn_entry_yaw_deg: Optional[float] = None
    avoid_phase: AvoidPhase = AvoidPhase.HOLD
    avoid_phase_since: float = 0.0
    avoid_direction: int = 0
    escape_direction: int = 0
    escape_clear_since: Optional[float] = None
    dead_end_phase: DeadEndPhase = DeadEndPhase.FORWARD_ARC
    dead_end_phase_since: float = 0.0
    dead_end_entry_yaw_deg: float = 0.0


def initial_context(now: float) -> StateMachineContext:
    return StateMachineContext(state=DriveState.IDLE, entered_at=now, reason="초기화")


def _enter(context: StateMachineContext, state: DriveState, now: float, reason: str,
           **fields) -> StateMachineContext:
    """상태 전이 헬퍼 — 새 컨텍스트 스냅샷을 만든다.

    turn_pending_since는 명시하지 않는 한 전이마다 지운다 — 포화 디바운스는
    끊기지 않은 CRUISE 체류 안에서만 유효한 타이머다 (컨텍스트 독스트링 참조).
    avoid_phase/avoid_phase_since도 전이마다 초기화한다 — AVOID에 새로 들어올
    때마다 정지 대기(HOLD)부터, 재확인 타이머는 지금부터 시작해야 한다.
    """
    fields.setdefault("turn_pending_since", None)
    fields.setdefault("avoid_phase", AvoidPhase.HOLD)
    fields.setdefault("avoid_phase_since", now)
    return replace(context, state=state, entered_at=now, reason=reason, **fields)


def _clamp_steer(offset_deg: float, left_limit: float, right_limit: float) -> float:
    """조향 오프셋을 좌우 개별 상한으로 자른다 (부호 관례: 음수=좌, 양수=우).

    대칭 헬퍼는 남기지 않는다 — 유일한 소비처가 조향이고, 이 로봇의 조향은 물리적으로
    비대칭이라 대칭 클램프를 남겨두면 그쪽을 쓰는 코드가 다시 생긴다.
    """
    return max(-abs(left_limit), min(abs(right_limit), offset_deg))


def _resolve_direction(context: StateMachineContext, params: StateMachineParams,
                       frame: SegmentationFrame, target_ratio: float
                       ) -> Tuple[StateMachineContext, Optional[float]]:
    """목표 방향(-1~+1) → 실제 진행 방향. CRUISE와 SLOPE가 같은 규칙을 쓴다.

    프레임에 cost_profile이 있으면 코스트맵 방향 선택(비용 + 목표 이탈 + 직전 이탈
    최소화 — 차도 ∞ 제외, 히스테리시스 포함)이고, 없으면 목표를 그대로 쓰는
    비례 조향 폴백이다. None을 돌려주면 전 방향 통행 불가(전 슬롯 ∞) — 정지
    여부 판단은 호출자 몫이다 (steering_costmap의 계약 그대로)."""
    if frame.cost_profile is None:
        return context, target_ratio
    chosen = select_direction(frame.cost_profile, target_ratio,
                              context.last_steer_ratio,
                              params.costmap_target_weight,
                              params.costmap_commit_weight)
    if chosen is None:
        return context, None
    return replace(context, last_steer_ratio=chosen), chosen


# ======================================================================
# 상태 핸들러 — 각각 (새 컨텍스트, 명령)을 돌려준다. 골격 + 의도 설명이 원칙.
# ======================================================================

def _handle_idle(context: StateMachineContext, frame: SegmentationFrame,
                 requests: ExternalRequests, now: float
                 ) -> Tuple[StateMachineContext, DriveCommand]:
    """IDLE: 대기. 시작 요청이 있고 인도가 보이면 CRUISE로.

    경계 소실로 떨어져 들어온 경우에도 자동 재출발하지 않는다 —
    수동(조이스틱/대시보드) 재시작 요청이 유일한 출구다.
    대기 명령은 파킹 브레이크다: IMU 없이는 평지를 확신할 수 없고, 경사 위
    무동력(stop)은 활강이 된다 — hardware/interfaces.py의 stop/brake 구분 참조.
    """
    if requests.start and frame.sidewalk_detected:
        return _enter(context, DriveState.CRUISE, now, "시작 요청 + 인도 확인"), STOP_COMMAND
    return context, BRAKE_COMMAND


def _handle_paused(context: StateMachineContext, frame: SegmentationFrame,
                   requests: ExternalRequests, now: float
                   ) -> Tuple[StateMachineContext, DriveCommand]:
    """PAUSED: 원격 재개나 취소 전까지 숏브레이크로 정지 유지.

    일시정지 중 카메라가 끊겨도 경계 소실 IDLE 폴백으로 바꾸지 않는다(PAUSED는
    GUARDED_STATES에 없다). 이미 사람이 세운 상태이며, 재개 시점의 실제 프레임에서
    인도가 확인되어야만 CRUISE로 나간다. 취소는 인지와 무관하게 IDLE로 간다.
    """
    if requests.resume and frame.sidewalk_detected:
        return _enter(context, DriveState.CRUISE, now, "원격 재개 + 인도 확인"), STOP_COMMAND
    return context, BRAKE_COMMAND


def _inspect_cooldown_over(context: StateMachineContext, params: StateMachineParams,
                           now: float) -> bool:
    """**정차** 재시도 쿨다운 종료 여부 — TRACE → INSPECT 게이트가 이것만 본다.

    점자블록은 낱개 물체가 아니라 **연속된 유도선**이라, 전방 카메라의 근접도 proxy로는
    '이 블록에 도착했다'가 관측되지 않는다: 유도선을 따라 달리는 동안 블록 마스크는 항상
    화면 최하단까지 이어져 있어 braille_proximity가 상시 포화(≈1)이고 중심 오차도 ≈0이다.
    즉 '도착 판정'은 원리적으로 불가능하다. 그래서 미션을 '도착해서 멈춰 검사'가 아니라
    **'따라가며 상시 탐지'**(TRACE)로 풀고, 정차는 탐지 모델이 파손 후보를 본 순간에만 한다.

    그 구조에서 이 쿨다운이 막는 것은 하나뿐이다: **같은 지점에서 정차가 무한 반복되는
    것.** INSPECT를 마치고 추종으로 돌아가면 로봇은 아직 같은 파손 위에 있으므로 다음
    탐지 주기에 같은 후보가 또 나온다 — 쿨다운이 없으면 그 자리에서 정차→판정→정차가
    끝나지 않는다. 거리 기준으로 바꾸려면 오도메트리가 필요하다(docs/ALGORITHMS.md N1).

    **추종(TRACE) 진입은 막지 않는다.** 블록을 최우선으로 따라가는 것과, 같은 파손을
    두 번 정차 판정하지 않는 것은 다른 문제다 — 전자는 미션이고 후자는 고착 방지다.
    쿨다운 중에도 추종·탐지는 계속 돌고, 정차만 미뤄진다.
    """
    return (context.last_inspect_done_at is None
            or now - context.last_inspect_done_at >= params.inspect_cooldown_sec)


def _trace_cooldown_over(context: StateMachineContext, params: StateMachineParams,
                         now: float) -> bool:
    """**추종** 재진입 쿨다운 종료 여부 — CRUISE → TRACE 게이트가 이것만 본다.

    타임아웃으로 끝난 추종만 이 창을 건다(last_trace_timeout_at 참조). 유도선은 끝없이
    이어질 수 있어 상한이 없으면 순찰이 저속 추종에 영구히 갇히고, 그 상한을 실효화하는
    것이 이 쿨다운이다 — 없으면 타임아웃으로 나온 그 틱에 같은 선을 다시 물어 상한이
    아무 일도 하지 않는다.

    블록 소실로 끝난 추종과 INSPECT 정차는 이 창을 걸지 않는다: 소실은 '블록이 화면에
    없다'는 뜻이라 애초에 재진입이 일어나지 않고(다시 보이면 그때는 가야 한다),
    정차 반복은 위 _inspect_cooldown_over가 따로 막는다.
    """
    return (context.last_trace_timeout_at is None
            or now - context.last_trace_timeout_at >= params.inspect_cooldown_sec)


def _debounce_braille(context: StateMachineContext, params: StateMachineParams,
                      raw: bool, now: float) -> Tuple[bool, Optional[float]]:
    """점자블록 발견 판정의 양방향 디바운스 → (확정 값, 대기 시작 시각).

    확정 값과 다른 raw가 inspect_confirm_sec 이상 유지돼야 뒤집힌다. 발견과 소실에
    같은 창을 쓴다: 접근 중 한 프레임 놓쳤다고 인도 중심으로 튕겨 돌아가면 목표가
    진동하고, 원거리 오탐 한 프레임에 조향이 반응해도 마찬가지다.
    """
    if raw == context.braille_stable:
        return context.braille_stable, None
    since = (context.braille_pending_since
             if context.braille_pending_since is not None else now)
    if now - since >= params.inspect_confirm_sec:
        return raw, None
    return context.braille_stable, since


def _approach_weight(params: StateMachineParams, proximity: float) -> float:
    """접근 혼합 가중치 — 0(인도 중심 그대로) ~ 1(블록 중심 추종).

    근접도 0에서 0으로 시작해 enter_proximity에서 1이 된다. 원거리 블록에도 약한 유인이
    붙는다는 것이 이 산식의 요점이다: 하한을 두면 '탐지됐지만 아직 멀다'는 구간에서
    조향이 블록을 완전히 무시하므로, 블록을 최우선으로 삼겠다는 결정과 어긋난다.

    전환이 아니라 혼합인 이유는 그대로다 — 목표를 통째로 갈아끼우면 화면 가장자리에
    걸린 블록 하나에 조향이 꺾여 인도를 벗어난다. 이 가중치가 '최우선'과 '인도를
    벗어나지 않음'을 동시에 만족시키는 지점이고, 그 위에 코스트맵(차도 ∞)과 좌우
    비대칭 클램프가 한 번 더 얹힌다.
    """
    if params.inspect_enter_proximity <= 0.0:   # 뒤집힌 설정 — 항상 블록 중심 추종
        return 1.0
    return max(0.0, min(1.0, proximity / params.inspect_enter_proximity))


def _enter_bypass(context: StateMachineContext, params: StateMachineParams,
                  frame: SegmentationFrame, now: float, reason: str
                  ) -> Tuple[StateMachineContext, DriveCommand]:
    """AVOID를 **정지 없이** SWERVE 국면으로 진입시킨다 (좌/우 즉시 선회).

    **IMU를 요구하지 않는다.** 종전에는 호출자가 IMU 있음을 보장해야 했다(종료 판정이
    yaw 기반이었다) — 그래서 IMU 배선이 해제된 상태에서 모든 장애물이 정지 대기로
    떨어졌고, 그것이 0803 실외 '좌우로 회피하지 않고 자꾸 정지만 함'의 직접 원인이다.
    지금은 SWERVE 종료가 '장애물이 진행 경로에서 빠졌는가'(시각)이고 RETURN 종료가
    '인도 중심 오차가 좁혀졌는가'(시각)라 yaw가 필요 없다.
    호출자는 평지(avoid_brake=False)만 보장한다 — 경사 위 저속 횡방향 기동은 밀림·
    등판 토크 양쪽으로 나쁘고, 경사에서는 SLOPE가 브레이크 정지로 넘긴다.
    """
    # 비껴갈 방향이 아예 없으면(좌·정면·우 전부 자유공간 없음 = 벽·담장·공사벽) 선회는
    # 무의미하다 — 종전에는 이 장면에서도 SWERVE로 들어가 SWERVE→PASS→RETURN→재막힘이
    # 무한 반복됐고, 회차(DEAD_END)는 그 위에 있어 영원히 발동하지 않았다.
    # 여기서 HOLD로 보내면 재확인 주기 뒤 회차로 승격된다 (_handle_avoid HOLD).
    if frame.all_directions_blocked:
        return (_enter(context, DriveState.AVOID, now,
                       f"{reason} → 좌우 모두 막힘, 정지 후 재확인",
                       avoid_brake=False, avoid_phase=AvoidPhase.HOLD,
                       avoid_phase_since=now),
                STOP_COMMAND)

    direction = _bypass_direction(frame)
    return (_enter(context, DriveState.AVOID, now,
                   f"{reason} → 정지 없이 {'좌' if direction < 0 else '우'} 회피",
                   avoid_brake=False, avoid_phase=AvoidPhase.SWERVE,
                   avoid_phase_since=now, avoid_direction=direction),
            DriveCommand(throttle_pct=params.avoid_bypass_throttle_pct,
                         steering_offset_deg=_turn_steer_deg(params, direction)))


def _handle_cruise(context: StateMachineContext, params: StateMachineParams, frame: SegmentationFrame,
                   imu: Optional[ImuReading], now: float
                   ) -> Tuple[StateMachineContext, DriveCommand]:
    """CRUISE: 인도 중심선 추종 — 코스트맵 방향 선택(N7) 1단계. 순찰 주행 본체.

    **목표(어디로 가고 싶은가)와 조향(어느 방향이 통행 가능한가)을 분리한다.**

    목표는 인도 중심 오차다. 점자블록이 확정되고 근접도가 추종 시작 하한을 넘으면
    TRACE(저속 추종 + 상시 탐지)로 넘긴다 — 블록 중심과의 혼합 추종은 그쪽 담당이다.
    이 상태는 블록을 '찾는' 구간이고 TRACE가 '따라가며 탐지하는' 구간이다.

    조향은 프레임에 cost_profile이 있으면 코스트맵 방향 선택이다(비용 + 목표 이탈 +
    직전 이탈 최소화 — 차도 방향 ∞는 후보에서 제외, 블록 출몰 채터링은 히스테리시스가
    누른다). 없으면(mock 기본) 위 목표 오차 × 이득의 비례 조향으로 폴백한다 —
    종전 동작이 항상 폴백으로 남는다. 어느 쪽이든 상한 클램프는 좌우 비대칭이고,
    최종 명령은 step()의 슬루율 제한을 한 번 더 지난다.
    전 방향이 ∞면 길이 막힌 것이다 → AVOID로 정지하고 재확인 주기마다 다시 본다.
    차도 유인의 최후 방어선은 여전히 공통 가드(roadway_encroachment_risk → ESTOP).
    회피 후 원경로 복귀도 결국 이 추종이 시각 피드백으로 수렴시킨다는 방침.

    TURN 진입(IMU 불필요): **목표 오차**×이득이 cruise 상한을 넘어
    turn_enter_steer_demand_deg 이상으로 turn_confirm_sec 지속 = 완만 추종 상한으로는
    못 도는 굽이 → 감속 + 깊은 조향의 TURN으로 넘긴다. 코스트맵이 고른 슬롯으로
    판정하지 않는 이유는 아래 판정부 주석에 있다(이산 슬롯으로는 임계에 닿지 않는다).
    막다른 길(DEAD_END) 진입은 여기가 아니라 AVOID 재확인이 담당한다 — 전 방향
    통행 불가는 일단 AVOID 정지를 거쳐, 유지될 때만 회차로 승격된다.

    장애물은 정지하지 않고 즉시 좌/우 회피로 넘긴다 (아래 첫 분기).
    """
    # 경사 판정이 장애물보다 앞이다: 경사에서는 장애물을 만나도 회피가 아니라
    # 브레이크 정지가 맞고(SLOPE→AVOID가 avoid_brake=True로 넘긴다), 순서가
    # 뒤바뀌면 경사에 올라선 첫 틱에 저속 횡방향 회피가 시작돼 밀림·등판 토크
    # 양쪽으로 나쁜 기동이 된다. 장애물이 사라지지 않으면 다음 틱에 SLOPE가
    # 곧바로 AVOID로 보내므로 지연은 한 틱뿐이다.
    # IMU 게이트: reading이 있어야만 SLOPE 진입 가능 (없으면 이 블록 자체가 죽어 있다)
    if imu is not None and abs(imu.pitch_deg) >= params.slope_enter_pitch_deg:
        return (_enter(context, DriveState.SLOPE, now,
                       f"경사 감지 (pitch {imu.pitch_deg:+.1f}°)"),
                BRAKE_COMMAND if frame.has_blocking_obstacle() else STOP_COMMAND)

    # 장애물: 정지하지 않고 즉시 좌/우로 비껴간다 (명세 '좌,우로 회피').
    # 정지→재확인은 움직이는 장애물이 지나가길 기다리는 정책이었지만, 인도에서
    # blocking의 대부분은 지나가지 않는 고정물(볼라드·주차·적재물)이고 사람은 오히려
    # 로봇을 피해 준다 — 3초씩 서서 기다리는 값이 통행 자체보다 컸다.
    # **IMU 게이트가 없다** — 회피 종료 판정이 시각+시간이 된 뒤로는 조건이 없다.
    if frame.has_blocking_obstacle():
        return _enter_bypass(context, params, frame, now, "장애물 인식")

    # 점자블록 추종 게이트 — **블록이 보이면 순찰을 접고 그쪽으로 간다(최우선).**
    # 남은 조건은 둘뿐이고 둘 다 미션 조건이 아니라 고착·오탐 방지 장치다:
    #   · braille_stable  — 발견 디바운스(inspect.confirm_sec). 한 프레임 오탐에
    #     주행 상태가 바뀌는 것을 막는다. _decide가 상태와 무관하게 매 틱 갱신한다.
    #   · _trace_cooldown_over — 직전 추종이 **타임아웃으로** 끝났을 때만 닫혀 있다.
    #     유도선이 끝없이 이어지는 경우 순찰이 저속 추종에 영구히 갇히는 것을 막는다.
    #
    # 근접도 하한과 중앙 정렬 조건은 **없다.** 예전에는 둘을 게이트로 걸어 원거리·
    # 비정렬 블록을 무시했는데, 그것은 '탐지되면 최우선으로 이동한다'와 정면으로
    # 어긋난다 — 화면에 잡힌 블록이 곧 검사 대상이고, 멀거나 치우쳐 있다는 것은
    # 가지 말 이유가 아니라 아직 도착하지 않았다는 뜻일 뿐이다. 두 조건이 막아 주던
    # 것은 TRACE 안에서 다른 방식으로 처리한다:
    #   · '원거리 블록에 속도가 통째로 떨어진다' → 추종은 이제 **감속하지 않는다**
    #     (trace.throttle_pct = cruise.throttle_pct — 근거는 config의 그 항목).
    #   · '인도를 벗어나 블록으로 달려든다'    → 목표는 전환이 아니라 근접도 비례
    #     혼합(_approach_weight)이고, 그 위에 코스트맵(차도 ∞)·좌우 비대칭 클램프·
    #     조향 슬루율 제한이 얹히며, 최후 방어선으로 경계 소실 IDLE과 차도 ESTOP이 있다.
    if context.braille_stable and _trace_cooldown_over(context, params, now):
        return (_enter(context, DriveState.TRACE, now,
                       "점자블록 발견 확정 → 추종 + 상시 탐지 시작",
                       trace_started_at=now),
                DriveCommand(throttle_pct=params.trace_throttle_pct,
                             steering_offset_deg=0.0))

    target_ratio = frame.sidewalk_center_offset_ratio
    context, resolved_ratio = _resolve_direction(context, params, frame, target_ratio)
    if resolved_ratio is None:
        # 통행 가능 방향이 하나도 없다(전 슬롯 ∞ — 차도가 전방을 덮음 등).
        # 여기는 비껴갈 방향 자체가 없는 경우이므로 회피가 아니라 정지가 맞다.
        # AVOID 정지로 넘겨 재확인한다 — 막힘이 재확인까지 유지되면 막다른 길로
        # 보고 DEAD_END 회차로 승격되고(IMU 게이트), 풀리면 그 틱에 복귀한다.
        return (_enter(context, DriveState.AVOID, now,
                       "코스트맵: 전 방향 통행 불가 → 정지 후 재확인",
                       avoid_brake=False), STOP_COMMAND)

    # 회전 필요 판단 (IMU 게이트): **목표 오차**(target_ratio)로 본다 — 코스트맵이
    # 고른 슬롯(resolved_ratio)이 아니다. 둘은 단위만 같고 다른 양이다: 목표 오차는
    # '얼마나 어긋났나'(연속값, ±1까지)이고 슬롯은 '어디로 조향하나'(이산값)다.
    # 슬롯으로 판정하면 회전이 사실상 안 걸린다: slot_count=15에서 슬롯 중심의
    # 최대 크기가 0.933이라 임계(18°/이득 20 = 0.9)를 넘는 슬롯이 최외곽 2개뿐이고,
    # 그 최외곽은 화면 좌·우 끝 = 차도(∞)나 미분류일 확률이 가장 높아 애초에 후보에서
    # 빠진다. 그 결과 90° 코너에서 CRUISE 상한(좌 15/우 12)으로 버티다 인도가 화면을
    # 벗어나 경계 소실 IDLE로 끝났다.
    # 클램프 전 수요를 쓰는 이유는 그대로다: 클램프 후에는 상한에 붙어 있다는 사실이
    # 지워져 '상한으로 부족한 굽이'와 '상한 근처 추종'을 구분 못 한다.
    # ※ IMU 게이트가 없다. 종전에는 imu가 없으면 이 판정 자체가 죽어 90° 코너에서
    #   CRUISE 상한(좌 15/우 12)으로 버티다 인도가 화면을 벗어나 경계 소실 IDLE로
    #   끝났다 — IMU 배선이 해제된 상태에서 코너를 돌 수 없었다는 뜻이다.
    #   회전 완료의 본 판정은 원래 시각 재정렬이고 yaw는 교차 검증이었으므로,
    #   yaw가 없으면 그 교차 검증만 시간으로 바꾼다 (_handle_turn).
    demand_deg = target_ratio * params.cruise_steer_gain_deg
    if abs(demand_deg) >= params.turn_enter_steer_demand_deg:
        pending_since = (context.turn_pending_since
                         if context.turn_pending_since is not None else now)
        if now - pending_since >= params.turn_confirm_sec:
            direction = -1 if demand_deg < 0 else 1
            return (_enter(context, DriveState.TURN, now,
                           f"조향 수요 포화 지속 → {'좌' if direction < 0 else '우'}회전",
                           turn_direction=direction,
                           turn_entry_yaw_deg=(imu.yaw_deg if imu is not None else None)),
                    DriveCommand(throttle_pct=params.turn_throttle_pct,
                                 steering_offset_deg=_turn_steer_deg(params, direction)))
        context = replace(context, turn_pending_since=pending_since)
    elif context.turn_pending_since is not None:
        context = replace(context, turn_pending_since=None)   # 포화 해소 → 타이머 해제

    # 조향은 코스트맵이 고른 방향(resolved_ratio)으로 낸다 — 위 demand_deg는 회전
    # 필요 판정 전용이다. 코스트맵이 없는 프레임에서는 둘이 같은 값이 된다.
    steer = _clamp_steer(resolved_ratio * params.cruise_steer_gain_deg,
                         params.cruise_steer_left_max_deg,
                         params.cruise_steer_right_max_deg)
    return context, DriveCommand(throttle_pct=params.cruise_throttle_pct,
                                 steering_offset_deg=steer)


def _handle_trace(context: StateMachineContext, params: StateMachineParams,
                  frame: SegmentationFrame, imu: Optional[ImuReading],
                  requests: ExternalRequests, now: float
                  ) -> Tuple[StateMachineContext, DriveCommand]:
    """TRACE: 발견한 점자블록을 **최우선으로 따라가며** 검사 카메라로 상시 탐지 (미션 본체).

    세그(주행 카메라)가 점자블록을 확정하면 CRUISE가 곧바로 여기로 넘긴다 — 근접도·정렬
    조건은 없다(근거는 _handle_cruise의 게이트 주석). 이 상태의 일은 둘:
      1. 블록으로 간다 — 목표는 인도 중심과 블록 중심의 **근접도 비례 혼합**이다
         (_approach_weight: 근접도 0에서 0 → enter_proximity에서 1). 전환식이면 화면
         가장자리에 걸린 블록 하나에 조향이 통째로 꺾여 인도를 벗어난다. 블록 **위치는
         캐시하지 않는다** — 측정이 없는 틱은 그 틱만 인도 중심을 따르고, 발견/소실
         판정만 디바운스된다.
      2. 탐지를 돌린다 — 하향 검사 카메라의 모델(models/braille_defect.pt, 3클래스)을
         detect_period_sec마다 돈다. 추론 실행은 I/O이므로 FSM 밖(main 루프)이다.
         **감속하지 않는다**: 명세가 '주행하며 탐지'이고, 0.32m/s에서 모션 블러는
         5mm·타일 하나당 추론 2회 이상이라 감속의 근거가 없다(config trace.throttle_pct).
         종전 35% 상시 저속이 '자율주행 속도가 너무 느림'(0803)의 지배적 원인이었다 —
         한국 인도는 유도선이 길어 순찰 대부분이 이 상태 체류다.

    조향 자체는 CRUISE와 같은 코스트맵 방향 선택을 그대로 지난다 — 블록을 따라가더라도
    차도(∞) 방향은 선택되지 않는다. 블록이 차도 위 도색면(crosswalk)으로 이어지는
    구간에서 이 게이트가 유일한 방어선이다.

    출구:
      · 탐지 모델이 파손 후보 보고(defect_found) + 정차 쿨다운 밖 → INSPECT (정차 후
        정밀 판정). 주행 중 판정은 모션 블러로 defect_partial(≈55px)부터 무너지므로
        **후보를 본 순간에만** 정차 비용을 낸다 — 상시 정차가 아니라 상시 탐지다.
        쿨다운 안의 보고는 **무시하고 추종을 계속한다** — 방금 판정한 그 파손을 아직
        지나지 못한 것이므로, 정차하면 같은 자리에서 정차가 무한 반복된다.
      · 블록 소실 확정(braille_stable=False) → CRUISE. **쿨다운을 걸지 않는다** —
        블록이 다시 보이면 최우선으로 다시 가야 한다.
      · 추종 타임아웃(trace_started_at 기준, INSPECT를 다녀와도 리셋되지 않는다) →
        CRUISE + 추종 재진입 쿨다운. 유도선은 끝없이 이어질 수 있어 상한이 없으면
        순찰이 저속 추종에 갇힌다 — 이 한 경우에만 재진입을 막는다.
      · 장애물·경사·전 방향 통행 불가는 CRUISE와 같은 처리(회피/경사 보정/정지).
        블록보다 이쪽이 우선인 것은 의도다 — 블록을 최우선으로 삼는 것은 '순찰보다
        우선'이라는 뜻이고, 장애물·경사·차도 위험은 여전히 그 위에 있다.
        공통 가드(차도 ESTOP·인지 두절 IDLE)도 GUARDED_STATES에 포함돼 그대로 산다.
    """
    if requests.defect_found and _inspect_cooldown_over(context, params, now):
        return (_enter(context, DriveState.INSPECT, now,
                       "탐지: 파손 후보 → 정차 후 정밀 판정",
                       trace_started_at=context.trace_started_at), STOP_COMMAND)

    if not context.braille_stable:
        return (_enter(context, DriveState.CRUISE, now,
                       "블록 소실 확정 → 인도 중심 추종 복귀"), STOP_COMMAND)
    started_at = (context.trace_started_at if context.trace_started_at is not None
                  else context.entered_at)
    if now - started_at >= params.trace_timeout_sec:
        return (_enter(context, DriveState.CRUISE, now,
                       "추종 타임아웃 → 인도 중심 추종 복귀(재추종 쿨다운 시작)",
                       last_trace_timeout_at=now, trace_started_at=None), STOP_COMMAND)

    # 경사·장애물은 CRUISE와 같은 우선순위·같은 처리다 (근거는 _handle_cruise 주석)
    if imu is not None and abs(imu.pitch_deg) >= params.slope_enter_pitch_deg:
        return (_enter(context, DriveState.SLOPE, now,
                       f"추종 중 경사 감지 (pitch {imu.pitch_deg:+.1f}°)"),
                BRAKE_COMMAND if frame.has_blocking_obstacle() else STOP_COMMAND)
    if frame.has_blocking_obstacle():
        return _enter_bypass(context, params, frame, now, "추종 중 장애물 인식")

    weight = _approach_weight(params, frame.braille_proximity)
    target_ratio = ((1.0 - weight) * frame.sidewalk_center_offset_ratio
                    + weight * frame.braille_center_offset_ratio)
    context, resolved_ratio = _resolve_direction(context, params, frame, target_ratio)
    if resolved_ratio is None:
        return (_enter(context, DriveState.AVOID, now,
                       "추종 중 전 방향 통행 불가 → 정지 후 재확인",
                       avoid_brake=False), STOP_COMMAND)

    steer = _clamp_steer(resolved_ratio * params.cruise_steer_gain_deg,
                         params.cruise_steer_left_max_deg,
                         params.cruise_steer_right_max_deg)
    # 접근 구간(weight<1 = 근접도 < enter_proximity)만 살짝 낮춘다. 도착 후 추종은
    # 종전 그대로다 — 감속의 근거가 없다는 판단(위 docstring)은 그 구간 이야기다.
    # 새 임계값을 만들지 않고 조향 혼합이 쓰는 weight를 그대로 재사용한다:
    # '블록 쪽으로 가는 중'의 정의가 두 곳에서 갈리지 않게.
    throttle = (params.trace_throttle_pct if weight >= 1.0
                else params.trace_approach_throttle_pct)
    return context, DriveCommand(throttle_pct=throttle, steering_offset_deg=steer)


def _handle_inspect(context: StateMachineContext, params: StateMachineParams,
                    requests: ExternalRequests, now: float
                    ) -> Tuple[StateMachineContext, DriveCommand]:
    """INSPECT: 정차 유지 → 정밀 판정 완료 통지(또는 타임아웃) → **추종 재개** + 정차 쿨다운.

    TRACE에서 탐지 모델이 파손 후보를 본 순간에만 들어온다 — 정차 비용을 내는 이유는
    주행 중 판정이 모션 블러로 defect_partial(≈55px)부터 무너지기 때문이다.
    판정 실행(I/O)은 main 루프 담당이다: 루프가 이 상태를 보면 settle_sec 만큼 감속을
    기다린 뒤 정지 화면을 캡처해 판정을 돌리고 notify_inspection_done()으로 통지한다.
    타임아웃은 통지 유실 시 무한 정지를 막는 안전판이다.

    **복귀는 블록이 아직 보이면 CRUISE가 아니라 TRACE다.** 유도선 하나에 파손이 여러
    곳일 수 있고, 판정 한 번 했다고 그 선을 놓고 순찰로 돌아가면 나머지를 놓친다 —
    블록을 최우선으로 삼는다는 결정이 여기까지 이어진다. 블록이 그새 소실됐으면
    (INSPECT 체류 중에도 디바운스는 돌아간다) CRUISE로 나간다.
    추종 타임아웃 기준(trace_started_at)은 보존한다 — 정차마다 리셋되면 파손이 잦은
    구간에서 추종 상한이 사실상 사라진다.

    어느 쪽이든 종료 시각을 last_inspect_done_at에 남겨 **정차** 쿨다운을 시작한다:
    복귀 직후 로봇은 아직 같은 파손 위에 있어 다음 탐지 주기에 같은 후보가 또 나오고,
    쿨다운이 없으면 그 자리에서 정차가 무한 반복된다. 추종 자체는 막지 않는다.
    정지 상태이므로 장애물 등장은 전이 사유가 아니다(재출발은 어차피 CRUISE·TRACE의
    장애물 검사를 다시 통과해야 한다).
    """
    if requests.inspection_done or now - context.entered_at >= params.inspect_timeout_sec:
        done = "검사 완료" if requests.inspection_done else "검사 타임아웃"
        if context.braille_stable:
            return (_enter(context, DriveState.TRACE, now,
                           f"{done} → 블록 추종 재개(정차 쿨다운 시작)",
                           last_inspect_done_at=now,
                           trace_started_at=context.trace_started_at), STOP_COMMAND)
        return (_enter(context, DriveState.CRUISE, now,
                       f"{done} + 블록 소실 → 순찰 복귀(정차 쿨다운 시작)",
                       last_inspect_done_at=now, trace_started_at=None), STOP_COMMAND)
    return context, STOP_COMMAND


def _half_score(costs: Tuple[float, ...]) -> Tuple[int, float]:
    """코스트맵 절반의 (통행 불가 슬롯 수, 나머지 비용 합) — 작은 쪽이 좋다.

    ∞를 합에 넣으면 안 된다: 정상적인 인도는 좌우 **양쪽** 끝에 차도·화단이 걸려
    양쪽 합이 모두 ∞가 되고, 그러면 두 절반이 동점 처리돼 코스트맵이 방향 결정에
    아무 기여를 못 한다(실전 인도에서 거의 항상 그렇다). ∞ 슬롯은 개수로 세고
    통행 가능한 슬롯끼리 비용을 비교한다 — '어느 쪽이 더 열려 있나'가 질문이다.
    """
    blocked = sum(1 for cost in costs if math.isinf(cost))
    finite = sum(cost for cost in costs if not math.isinf(cost))
    return blocked, finite


def _bypass_direction(frame: SegmentationFrame) -> int:
    """우회 방향 (-1=좌, +1=우) — 좌/우 중 경로 이득이 큰 쪽.

    코스트맵이 있으면 통행 불가 슬롯이 적은 쪽, 동수면 통행 비용이 낮은 쪽이다
    (_half_score). 코스트맵이 없거나 좌우가 완전 동점이면 가장 가까운 blocking
    장애물의 치우침 반대쪽. 그래도 정중앙(치우침 0)이면 좌 — 좌측 조향 여유가
    더 크다 (docs/HARDWARE.md).

    장애물 요약이 영역 1개(무게중심)라 '왼쪽에 사람 + 오른쪽에 벽'이 중앙 치우침
    0으로 뭉개진다 — 그래서 코스트맵 쪽을 먼저 본다. 요약을 쪼개는 것은 인지 계층
    작업이다 (perception/segmentation.py의 연결성분 주석).
    """
    if frame.cost_profile is not None:
        half = len(frame.cost_profile) // 2
        left = _half_score(tuple(frame.cost_profile[:half]))
        right = _half_score(tuple(frame.cost_profile[-half:]))
        if left != right:
            return -1 if left < right else 1
    blocking = [obstacle for obstacle in frame.obstacles if obstacle.blocking]
    if not blocking:
        return -1   # 방어적: 호출자가 blocking을 보장하지만 좌 기본값으로 닫는다
    nearest = max(blocking, key=lambda obstacle: obstacle.proximity)
    return -1 if nearest.lateral_bias >= 0.0 else 1


def _swerve(context: StateMachineContext, params: StateMachineParams,
            frame: SegmentationFrame, now: float
            ) -> Tuple[StateMachineContext, DriveCommand]:
    """AVOID 안에서 SWERVE 국면을 (재)시작한다 — 방향을 매번 다시 고른다.

    상태 전이가 아니라 국면 변경이므로 _enter를 쓰지 않는다(entered_at·on_event 불변).
    방향을 캐시하지 않는 이유는 회피 도중 장면이 바뀌기 때문이다 — 처음 고른 쪽이
    막히면 그때 열린 쪽으로 다시 고르는 것이 맞다.
    """
    direction = _bypass_direction(frame)
    return (replace(context, avoid_phase=AvoidPhase.SWERVE, avoid_phase_since=now,
                    avoid_direction=direction),
            DriveCommand(throttle_pct=params.avoid_bypass_throttle_pct,
                         steering_offset_deg=_turn_steer_deg(params, direction)))


def _handle_avoid(context: StateMachineContext, params: StateMachineParams, frame: SegmentationFrame,
                  imu: Optional[ImuReading], now: float
                  ) -> Tuple[StateMachineContext, DriveCommand]:
    """AVOID: 좌/우 회피 선회. **IMU를 요구하지 않는다** — 종료 판정이 시각 + 시간이다.

    **정상 경로는 CRUISE·TRACE에서 SWERVE로 직접 들어오는 것이다** (정지 없음 — 근거는
    _handle_cruise의 장애물 분기). 정지 대기(HOLD)로 들어오는 경우는 둘뿐이다:
      · 경사발(avoid_brake) — 경사 위 저속 횡방향 기동은 밀림·등판 토크 양쪽으로 나쁘다
      · 전 방향 통행 불가 — 비껴갈 방향 자체가 없다(막다른 길 후보)
    ※ 종전에는 'IMU 없음'이 세 번째 조건이었다. 종료 판정이 전부 yaw 기반
      (swerve_yaw_deg / return_yaw_tolerance_deg)이라 IMU 없이는 되돌아올 기준이
      없었기 때문인데, IMU 배선이 해제된 상태(config hardware.imu)에서는 그 조건이
      **모든** 장애물에 걸려 회피가 한 번도 일어나지 않았다 — 0803 실외에서 관찰된
      '좌우로 회피하지 않고 자꾸 정지만 함'의 직접 원인이다. 회피의 본질은 '옆으로
      비켜서 지나간다'이고 그것은 카메라로 관측된다: 장애물이 진행 경로에서 빠졌는지,
      인도 중심으로 되돌아왔는지. yaw는 편리한 대리 지표였을 뿐 필수가 아니었다.

    하위 국면(AvoidPhase — 상태 전이가 아니라 AVOID 안의 진행 단계):
      HOLD   정지 대기. recheck_sec 안에 막힘이 풀리면 CRUISE로 경로 진행.
             유지되면 막다른 길로 보고 DEAD_END 회차로 승격한다(IMU 게이트 — 회차만은
             yaw 누적으로 진행을 재므로 대체 수단이 없다). 홀드 명령은 avoid_brake면
             매 틱 브레이크 유지 — 전이 틱 1회 브레이크로는 다음 틱부터 경사에서 밀린다.
      SWERVE 좌/우 중 경로 이득이 큰 쪽(_bypass_direction)으로 조향해 벗어난다.
             **장애물이 진행 경로에서 빠지면** 통과 국면으로 — 이것이 yaw 이탈량을
             대신하는 시각 판정이고, 실제로 재려던 것('충분히 비켜났나')에 더 가깝다.
             빠지지 않아도 phase_timeout_sec에 통과로 넘어간다: 계속 잡히는 장애물을
             이유로 선회를 무한정 이어가면 인도를 벗어난다.
      PASS   조향 중립으로 pass_sec 직진 — 전방 카메라는 옆의 장애물을 못 보므로
             통과 판정은 시간뿐이다.
      RETURN 반대 조향으로 되돌아오다 |인도 중심 오차| ≤ return_center_abs면 완료.
             남은 횡오차 수렴은 CRUISE 중심 추종이 담당한다 — 원래도 그 설계였다.
    PASS·RETURN 중 새 blocking 장애물은 SWERVE 재시작이다(방향을 다시 고른다) —
    종전에는 HOLD 재정지였는데, 그것은 '회피 중에 또 막히면 선다'는 뜻이라 사람이
    많은 인도에서 정지 연쇄가 된다. 회피 경로가 막혔으면 다시 비껴가는 것이 맞다.
    어느 국면이든 phase_timeout_sec을 넘기면 다음 국면으로, RETURN이면 CRUISE로
    나간다 — 여전히 막혀 있으면 CRUISE의 장애물 검사가 즉시 다시 보내므로 고착이 없다.
    """
    if context.avoid_phase is AvoidPhase.HOLD:
        if now - context.avoid_phase_since >= params.avoid_recheck_sec:
            # 막다른 길은 두 가지로 온다. 비용 ∞(차도·횡단보도로만 둘러싸임)는
            # '가면 안 된다'이고, all_directions_blocked(벽·담장)는 '갈 수 없다'다.
            # 후자를 빼면 실외에서 회차가 사실상 발동하지 않는다: 벽은 어느 클래스도
            # ∞가 아니라(미분류 0.9) 전 슬롯 ∞가 성립하지 않고, 좌우 회피만 무한
            # 반복된다 — SWERVE→PASS→RETURN→다시 막힘.
            all_blocked = (frame.all_directions_blocked
                           or (frame.cost_profile is not None
                               and all(math.isinf(cost) for cost in frame.cost_profile)))
            if not frame.has_blocking_obstacle() and not all_blocked:
                return (_enter(context, DriveState.CRUISE, now, "재확인: 장애물 소멸 → 직진 복귀",
                               avoid_brake=False), STOP_COMMAND)
            if all_blocked and imu is not None and not context.avoid_brake:
                return (_enter(context, DriveState.DEAD_END, now,
                               "재확인: 전 방향 통행 불가 유지(막다른 길) → 회차 시작",
                               avoid_brake=False,
                               dead_end_phase=DeadEndPhase.FORWARD_ARC,
                               dead_end_phase_since=now,
                               dead_end_entry_yaw_deg=imu.yaw_deg),
                        DriveCommand(throttle_pct=params.dead_end_throttle_pct,
                                     steering_offset_deg=-params.dead_end_steer_deg))
            if not all_blocked and not context.avoid_brake:
                return _swerve(context, params, frame, now)
        return context, (BRAKE_COMMAND if context.avoid_brake else STOP_COMMAND)

    timed_out = now - context.avoid_phase_since >= params.avoid_phase_timeout_sec

    if context.avoid_phase is AvoidPhase.SWERVE:
        if not frame.has_blocking_obstacle() or timed_out:
            return (replace(context, avoid_phase=AvoidPhase.PASS, avoid_phase_since=now),
                    DriveCommand(throttle_pct=params.avoid_bypass_throttle_pct,
                                 steering_offset_deg=0.0))
        return context, DriveCommand(
            throttle_pct=params.avoid_bypass_throttle_pct,
            steering_offset_deg=_turn_steer_deg(params, context.avoid_direction))

    if frame.has_blocking_obstacle():   # PASS·RETURN 중 새 장애물 → 다시 비껴간다
        return _swerve(context, params, frame, now)

    if context.avoid_phase is AvoidPhase.PASS:
        if now - context.avoid_phase_since >= params.avoid_pass_sec or timed_out:
            return (replace(context, avoid_phase=AvoidPhase.RETURN, avoid_phase_since=now),
                    DriveCommand(throttle_pct=params.avoid_bypass_throttle_pct,
                                 steering_offset_deg=_turn_steer_deg(
                                     params, -context.avoid_direction)))
        return context, DriveCommand(throttle_pct=params.avoid_bypass_throttle_pct,
                                     steering_offset_deg=0.0)

    # RETURN — 인도 중심으로 되돌아오면 완료, 잔여 횡오차는 CRUISE 추종이 수렴시킨다
    if (abs(frame.sidewalk_center_offset_ratio) <= params.avoid_return_center_abs
            or timed_out):
        done = "중심 재정렬" if not timed_out else "복귀 타임아웃"
        return (_enter(context, DriveState.CRUISE, now,
                       f"회피 완료 ({done}) → 원 경로 합류는 중심 추종이 담당"),
                STOP_COMMAND)
    return context, DriveCommand(
        throttle_pct=params.avoid_bypass_throttle_pct,
        steering_offset_deg=_turn_steer_deg(params, -context.avoid_direction))


def _slope_throttle_pct(params: StateMachineParams, pitch_deg: float) -> float:
    """경사 스로틀 프로파일 — 오르막(+pitch)은 증속, 내리막(−pitch)은 감속.

    pitch 부호는 IMU 실측 규약(hardware/jetson/imu.py): 기수 들림(오르막)이 양수.
    """
    if pitch_deg > 0.0:
        return min(params.slope_uphill_throttle_max_pct,
                   params.cruise_throttle_pct
                   + params.slope_uphill_throttle_gain_pct_per_deg * pitch_deg)
    return max(params.slope_downhill_throttle_min_pct,
               params.cruise_throttle_pct
               - params.slope_downhill_throttle_gain_pct_per_deg * -pitch_deg)


def _handle_slope(context: StateMachineContext, params: StateMachineParams, frame: SegmentationFrame,
                  imu: Optional[ImuReading], now: float
                  ) -> Tuple[StateMachineContext, DriveCommand]:
    """SLOPE: 경사 보정 **주행** — 조향은 CRUISE와 같은 인도 중심 추종, 스로틀만 보정.

    상승 경사: cruise 스로틀 + 이득×pitch (상한 클램프) — 등판 토크 확보.
    하강 경사: cruise 스로틀 − 이득×|pitch| (하한 클램프) — 과속 억제. 저듀티
              전진 PWM은 오프 구간이 사실상 모터 브레이크라 무동력 활강보다 느리다.
    급한 하강(pitch ≤ −downhill_brake_pitch_deg): 숏브레이크 정지 유지 —
              명세의 '하강 안전 정지'. 수동 개입 전까지 그 자리에 선다.
    점자블록 접근·검사는 하지 않는다 — 경사 위 정차 검사는 밀림·모션 블러
    양쪽으로 나쁘고, 블록 게이트는 평지(CRUISE) 복귀 후 다시 열린다.
    pitch가 해제 임계 아래로 내려오거나 IMU가 사라지면(게이트 소실) CRUISE 복귀,
    장애물이 인식되면 AVOID(브레이크 유지 — 경사에서 밀리지 않게)로 넘긴다.
    """
    if imu is None:
        return _enter(context, DriveState.CRUISE, now, "IMU 소실 → 경사 보정 해제"), STOP_COMMAND
    if frame.has_blocking_obstacle():
        return (_enter(context, DriveState.AVOID, now, "경사 중 장애물 인식",
                       avoid_brake=True), BRAKE_COMMAND)
    if abs(imu.pitch_deg) < params.slope_exit_pitch_deg:
        return _enter(context, DriveState.CRUISE, now, "경사 해소"), STOP_COMMAND
    if imu.pitch_deg <= -params.slope_downhill_brake_pitch_deg:
        return context, BRAKE_COMMAND   # 급경사 하강 — 안전 정지 유지 (수동 개입 대기)

    context, resolved_ratio = _resolve_direction(
        context, params, frame, frame.sidewalk_center_offset_ratio)
    if resolved_ratio is None:
        return (_enter(context, DriveState.AVOID, now,
                       "코스트맵: 전 방향 통행 불가 → 정지 후 재확인",
                       avoid_brake=True), BRAKE_COMMAND)
    steer = _clamp_steer(resolved_ratio * params.cruise_steer_gain_deg,
                         params.cruise_steer_left_max_deg,
                         params.cruise_steer_right_max_deg)
    return context, DriveCommand(throttle_pct=_slope_throttle_pct(params, imu.pitch_deg),
                                 steering_offset_deg=steer)


def _turn_steer_deg(params: StateMachineParams, direction: int) -> float:
    """회전 방향(-1=좌, +1=우) → 조향 오프셋(도). 조향이 비대칭이라 좌우 크기가 다르다."""
    return (-abs(params.turn_steer_left_deg) if direction < 0
            else abs(params.turn_steer_right_deg))


def _handle_turn(context: StateMachineContext, params: StateMachineParams, frame: SegmentationFrame,
                 imu: Optional[ImuReading], now: float
                 ) -> Tuple[StateMachineContext, DriveCommand]:
    """TURN: 좌/우회전 — 감속 + 진입 시 확정한 방향으로 깊은 조향. **IMU 불필요.**

    코너에서는 세그 중심선 적합이 자주 폴백·요동하므로 시각 목표를 매 틱 따르지
    않는다: 진입 때 확정한 방향(turn_direction)으로 조향을 고정하고(점진 조정은
    공통 슬루율이 담당), 완료는 시각 재정렬로 판정한다.

    완료: 최소 진행(진입 직후 세그 노이즈가 '재정렬'로 보이는 것을 막는 하한) 후
          |인도 중심 오차| ≤ exit_center_abs로 재정렬되면 CRUISE 복귀.
          최소 진행은 yaw가 있으면 min_progress_yaw_deg(실측), 없으면
          min_progress_sec(시간)이다 — 종전에는 IMU가 없으면 이 상태에 **들어오지도
          못했고**, 그래서 90° 코너에서 CRUISE 상한으로 버티다 인도가 화면을 벗어나
          경계 소실 IDLE로 끝났다(0803 실외). 본 판정은 원래 시각 재정렬이고 yaw는
          교차 검증이었으므로, 교차 검증만 시간으로 대체한다.
    안전 출구: |yaw 변화| ≥ max_yaw_deg(과회전 — yaw 있을 때만) /
          timeout_sec(무한 고착) / 장애물 인식 → 회피 선회.
    """
    if frame.has_blocking_obstacle():
        return _enter_bypass(context, params, frame, now, "회전 중 장애물 인식")

    # 진입 시 yaw 기준을 잡았을 때만 yaw로 잰다 — 기준이 없으면(진입 시 IMU 없음)
    # 도중에 IMU가 살아나도 절대 yaw와의 차이는 진행량이 아니다
    if imu is not None and context.turn_entry_yaw_deg is not None:
        yaw_delta_deg = imu.yaw_deg - context.turn_entry_yaw_deg
        # 회전 방향으로의 진행량: 좌회전(direction=-1)은 yaw+가 진행이다
        progressed = (-context.turn_direction * yaw_delta_deg
                      >= params.turn_min_progress_yaw_deg)
        if abs(yaw_delta_deg) >= params.turn_max_yaw_deg:
            return (_enter(context, DriveState.CRUISE, now,
                           f"회전 yaw 상한 도달 ({yaw_delta_deg:+.0f}°) → 추종 복귀"),
                    STOP_COMMAND)
    else:
        progressed = now - context.entered_at >= params.turn_min_progress_sec

    if now - context.entered_at >= params.turn_timeout_sec:
        return _enter(context, DriveState.CRUISE, now, "회전 타임아웃 → 추종 복귀"), STOP_COMMAND
    if progressed and abs(frame.sidewalk_center_offset_ratio) <= params.turn_exit_center_abs:
        return (_enter(context, DriveState.CRUISE, now, "회전 완료 (재정렬)"), STOP_COMMAND)

    return context, DriveCommand(
        throttle_pct=params.turn_throttle_pct,
        steering_offset_deg=_turn_steer_deg(params, context.turn_direction))


def _handle_dead_end(context: StateMachineContext, params: StateMachineParams, frame: SegmentationFrame,
                     imu: Optional[ImuReading], now: float
                     ) -> Tuple[StateMachineContext, DriveCommand]:
    """DEAD_END: 막다른 길 K턴 회차 — 큰 좌조향 전진 호 ↔ 정렬 후진 반복 (IMU 게이트).

    진입은 AVOID 재확인이 담당한다: 좌/우 어느 쪽에도 경로가 없을 때(코스트맵
    전 슬롯 ∞ 유지)만 여기로 온다. 왼쪽 고정인 이유: 좌 조향 여유(45°)가 우(30°)
    보다 커서 회전반경이 최소다 (docs/HARDWARE.md).

    하위 국면(DeadEndPhase):
      FORWARD_ARC   좌 dead_end_steer_deg 조향 전진 — yaw가 실제로 늘어나는 국면.
      REVERSE_ALIGN 조향 정렬(0) + 후진 — 다음 호를 위한 공간 확보. 후방은 카메라
                    사각(완전 무지)이므로 reverse_sec은 짧게 유지한다.
    회차 중 blocking 장애물 검출은 무시한다 — 막다른 길에서는 벽·구석이 상시
    blocking으로 잡히므로 여기서 재정지하면 회차가 영원히 시작을 못 한다.
    (차도 위험 ESTOP·경계 소실 IDLE 폴백 등 공통 가드는 그대로 산다.)

    종료: yaw 누적(시작 방위 대비, 좌회전=+)이 target−tolerance에 닿으면 CRUISE —
          잔여 각도·횡오차는 CRUISE 중심 추종이 수렴시킨다.
    안전 출구: IMU 소실 → CRUISE(진행을 잴 수 없다 — 막혀 있으면 CRUISE 장애물
          검사가 다시 AVOID로 보낸다). timeout_sec 초과 → 물리적으로 갇힌 것으로
          보고 IDLE(정지 + 수동 대기) — yaw가 안 늘어도 무한 반복하지 않는다.
    """
    if imu is None:
        return _enter(context, DriveState.CRUISE, now, "IMU 소실 → 회차 중단"), STOP_COMMAND
    yaw_progress_deg = imu.yaw_deg - context.dead_end_entry_yaw_deg   # 좌회전 회차 = yaw+
    if yaw_progress_deg >= (params.dead_end_target_yaw_deg
                            - params.dead_end_yaw_tolerance_deg):
        return (_enter(context, DriveState.CRUISE, now,
                       f"회차 완료 (yaw {yaw_progress_deg:+.0f}°) → 추종 복귀"),
                STOP_COMMAND)
    if now - context.entered_at >= params.dead_end_timeout_sec:
        return (_enter(context, DriveState.IDLE, now,
                       "회차 실패(타임아웃) → 정지 + 수동 대기"), BRAKE_COMMAND)

    if context.dead_end_phase is DeadEndPhase.FORWARD_ARC:
        if now - context.dead_end_phase_since >= params.dead_end_forward_sec:
            return (replace(context, dead_end_phase=DeadEndPhase.REVERSE_ALIGN,
                            dead_end_phase_since=now),
                    DriveCommand(throttle_pct=-params.dead_end_throttle_pct,
                                 steering_offset_deg=0.0))
        return context, DriveCommand(throttle_pct=params.dead_end_throttle_pct,
                                     steering_offset_deg=-params.dead_end_steer_deg)

    if now - context.dead_end_phase_since >= params.dead_end_reverse_sec:
        return (replace(context, dead_end_phase=DeadEndPhase.FORWARD_ARC,
                        dead_end_phase_since=now),
                DriveCommand(throttle_pct=params.dead_end_throttle_pct,
                             steering_offset_deg=-params.dead_end_steer_deg))
    return context, DriveCommand(throttle_pct=-params.dead_end_throttle_pct,
                                 steering_offset_deg=0.0)


def _escape_steer_deg(params: StateMachineParams, direction: int) -> float:
    """차도 이탈 조향 오프셋(도). 조향이 비대칭이라 좌우 크기가 다르다 (turn과 같은 관례)."""
    return (-abs(params.escape_steer_left_deg) if direction < 0
            else abs(params.escape_steer_right_deg))


def _escape_direction(frame: SegmentationFrame) -> int:
    """차도에서 벗어날 방향 (-1=좌, +1=우) — **인도가 있는 쪽**.

    코스트맵 좌/우 절반 비교(_half_score)를 그대로 쓴다: 차도 슬롯은 비용 ∞라
    통행 불가 개수로 세어지므로, '통행 불가가 적은 절반'이 곧 인도가 남아 있는 쪽이다.
    코스트맵이 없거나 좌우가 동점이면 인도 중심 오차의 반대쪽으로 간다 — 중심이
    화면 오른쪽에 있다는 것은 로봇이 인도의 왼쪽 밖으로 나가는 중이라는 뜻이므로
    오른쪽으로 꺾어야 인도로 돌아온다. 오차가 정확히 0이면 좌(조향 여유가 크다).
    """
    if frame.cost_profile is not None:
        half = len(frame.cost_profile) // 2
        left = _half_score(tuple(frame.cost_profile[:half]))
        right = _half_score(tuple(frame.cost_profile[-half:]))
        if left != right:
            return -1 if left < right else 1
    return 1 if frame.sidewalk_center_offset_ratio > 0.0 else -1


def _handle_escape(context: StateMachineContext, params: StateMachineParams,
                   frame: SegmentationFrame, now: float
                   ) -> Tuple[StateMachineContext, DriveCommand]:
    """ESCAPE: 차도가 코리도에 들어왔다 → **감속 + 인도 쪽으로 깊은 선회**.

    명세: '차도는 절대 접근 금지, 차도를 만나면 좌 or 우로 방향 전환 후 인도로 주행'.
    ※ 종전에는 이 상황이 곧 ESTOP(수동 해제만)이었다. 명세와 정면으로 어긋났고,
      실외에서 가장 무거운 실패였다 — 인도 옆이 차도인 것은 정상 상황인데 판정
      오탐 한 번에 순찰이 끝났고 사람이 가서 재시작해야 했다.

    방향은 진입 시 한 번 확정하고 유지한다(_escape_direction). 매 틱 다시 고르지 않는
    이유는 선회 도중 차도 마스크가 화면에서 이동해 좌우 판정이 뒤집히면 제자리에서
    좌우로 떠는 기동이 되기 때문이다 — TURN이 방향을 고정하는 것과 같은 이유다.

    출구:
      · 차도 위험이 해소되고 escape_clear_sec 유지 → CRUISE. 즉시 복귀하면 코리도
        경계에서 판정이 켜졌다 꺼지며 ESCAPE↔CRUISE를 왕복한다(조향이 떤다).
      · escape_timeout_sec 초과 → ESTOP. 선회로는 벗어나지 못하는 상황이므로
        '차도 절대 접근 금지'를 지키는 유일한 방법이 정지 + 사람 호출이다.
      · 코리도가 차도로 덮이면(roadway_critical) 공통 가드가 먼저 ESTOP으로 보낸다.
    장애물·경사·블록은 여기서 보지 않는다 — 차도가 그 전부보다 우선이고, 이 상태는
    길어도 timeout_sec 안에 끝난다.
    """
    if now - context.entered_at >= params.escape_timeout_sec:
        return (_enter(context, DriveState.ESTOP, now,
                       "차도 이탈 실패(선회 타임아웃) → 정지 + 수동 대기"), BRAKE_COMMAND)

    if frame.roadway_encroachment_risk:
        context = replace(context, escape_clear_since=None)   # 위험 재발 → 타이머 해제
    else:
        clear_since = (context.escape_clear_since
                       if context.escape_clear_since is not None else now)
        if now - clear_since >= params.escape_clear_sec:
            return (_enter(context, DriveState.CRUISE, now,
                           "차도 이탈 완료 → 인도 중심 추종 복귀",
                           escape_clear_since=None), STOP_COMMAND)
        context = replace(context, escape_clear_since=clear_since)

    return context, DriveCommand(
        throttle_pct=params.escape_throttle_pct,
        steering_offset_deg=_escape_steer_deg(params, context.escape_direction))


def _handle_estop(context: StateMachineContext, requests: ExternalRequests, now: float
                  ) -> Tuple[StateMachineContext, DriveCommand]:
    """ESTOP: 즉시 정지 유지. 수동 해제 요청만이 유일한 출구 (IDLE로)."""
    if requests.estop_release:
        return _enter(context, DriveState.IDLE, now, "ESTOP 수동 해제"), STOP_COMMAND
    return context, BRAKE_COMMAND


# ======================================================================
# 순수 step(): 공통 가드 → 상태 디스패치 → 조향 슬루율 제한
# ======================================================================

def _decide(context: StateMachineContext, params: StateMachineParams,
            frame: Optional[SegmentationFrame], imu: Optional[ImuReading],
            now: float, requests: ExternalRequests
            ) -> Tuple[StateMachineContext, DriveCommand]:
    """공통 가드 → 상태 디스패치. step()의 본체 (슬루율 제한 전 단계).

    공통 가드 우선순위 (명세 하드 제약):
      0. ESTOP 상태는 수동 해제 외 어떤 입력도 무시한다 (래치).
      1. 수동 ESTOP 요청 / **코리도가 차도로 덮임**(roadway_critical) → 즉시 ESTOP.
      2. 차도·횡단보도가 코리도에 진입(roadway_encroachment_risk) → **ESCAPE 선회**.
         정지가 아니다 — 명세: '차도를 만나면 좌 or 우로 방향 전환 후 인도로 주행'.
         1과 2를 나눈 이유는 되돌릴 수 있는 상황과 그렇지 않은 상황에 같은 대응을
         하지 않기 위해서다: 인도 옆이 차도인 것은 정상이고, 그 판정 하나로 미션을
         끝내면(종전 ESTOP) 오탐 한 번에 순찰이 끝난다.
      3. 인지 두절(frame 없음)·인도 경계 소실이 perception_grace_sec 이상 지속
         → 정지 + 수동 대기(IDLE). 유예 중에는 **복구 주행**을 한다(정지가 아니다 —
         근거는 아래 유예창 주석). 이동·임무 상태에서만 — IDLE로 떨어진 뒤에는
         수동 재시작이 출구다.
    """
    # 가드 0: ESTOP 래치
    if context.state is DriveState.ESTOP:
        return _handle_estop(context, requests, now)

    # 가드 공통: TRACE·INSPECT를 가드로 떠날 때 **정차** 쿨다운을 기록하고 추종 세션을
    # 닫는다. 정차 쿨다운을 남기는 이유는 중복 판정·중복 보고 방지다 — 사람이 ESTOP을
    # 풀거나 재시작한 직후 같은 파손 위에 서 있을 것이므로, 그 자리에서 다시 정차해
    # 같은 판정을 올리는 것은 의미가 없다.
    # **추종 재진입은 막지 않는다**: 가드 이탈은 차도 덮임(ESTOP)·차도 진입(ESCAPE)·
    # 경계 소실(IDLE) 셋이고, 어느 쪽도 '이 유도선을 다 봤다'는 뜻이 아니다. 사람이 다시
    # 출발시키거나 선회로 차도를 벗어났다면 중단된 검사를 이어가는 것이 맞고, 여기서
    # 추종을 막으면 블록 추종이 복귀 뒤 조용히 꺼진다.
    trace_exit = ({"last_inspect_done_at": now, "trace_started_at": None}
                  if context.state in (DriveState.TRACE, DriveState.INSPECT)
                  else {})

    # 점자블록 발견 디바운스는 **어떤 가드보다 먼저** 매 틱 갱신한다. 아래 가드들은
    # 전부 조기 return이므로, 갱신이 뒤에 있으면 그 상태에 머무는 동안 판정이 얼어붙는다
    # — ESCAPE로 4초 선회하고 나온 틱에 이미 지나친 블록의 braille_stable=True가 그대로
    # 남아, 확정 시간 없이 곧바로 없는 블록을 추종하러 간다(경계 소실 유예도 마찬가지).
    if frame is not None:
        stable, pending_since = _debounce_braille(context, params,
                                                  frame.braille_detected, now)
        context = replace(context, braille_stable=stable,
                          braille_pending_since=pending_since)

    # 가드 1: 즉시 ESTOP (수동 요청 또는 코리도가 차도로 덮임)
    if requests.estop:
        return (_enter(context, DriveState.ESTOP, now, "수동 ESTOP 요청", **trace_exit),
                BRAKE_COMMAND)
    if frame is not None and frame.roadway_critical:
        return (_enter(context, DriveState.ESTOP, now,
                       "코리도가 차도로 덮임 → 즉시 정지 (선회로는 늦음)",
                       **trace_exit), BRAKE_COMMAND)

    # 가드 2: 차도 진입 → 선회 이탈. ESCAPE 자신은 ESCAPE_SOURCE_STATES에 없다 —
    # 있으면 매 틱 재진입해 entered_at이 리셋되고 escape.timeout_sec(선회로 못
    # 벗어나면 ESTOP)이 영원히 걸리지 않는다.
    if (frame is not None and frame.roadway_encroachment_risk
            and context.state in ESCAPE_SOURCE_STATES):
        direction = _escape_direction(frame)
        return (_enter(context, DriveState.ESCAPE, now,
                       f"차도 진입 위험 → {'좌' if direction < 0 else '우'}로 이탈 선회",
                       escape_direction=direction, escape_clear_since=None,
                       avoid_brake=False, **trace_exit),
                DriveCommand(throttle_pct=params.escape_throttle_pct,
                             steering_offset_deg=_escape_steer_deg(params, direction)))

    # 가드 2.5: 원격 미션 제어. 수동 ESTOP·차도 이탈 선회보다는 낮고 인지 폴백보다
    # 먼저다. 취소·일시정지는 카메라가 끊겨도 즉시 정지로 수렴해야 하므로 인지 가드
    # 위에 있어야 하고, 차도 가드 아래인 이유는 PAUSABLE_STATES 주석에 있다
    # (선회 중 브레이크 = 차도 위에 정지).
    if requests.cancel and context.state is not DriveState.IDLE:
        return (_enter(context, DriveState.IDLE, now, "원격 임무 취소",
                       avoid_brake=False, perception_bad_since=None, **trace_exit),
                BRAKE_COMMAND)
    if requests.pause and context.state in PAUSABLE_STATES:
        return (_enter(context, DriveState.PAUSED, now, "원격 일시정지",
                       avoid_brake=False, perception_bad_since=None, **trace_exit),
                BRAKE_COMMAND)

    # 가드 3: 인지 두절·경계 소실 (이동·검사 상태에서만 의미).
    # 최종 폴백 명령은 브레이크다: 경사였을 가능성을 배제할 수 없고(특히 SLOPE에서
    # 인지가 끊긴 경우) 무동력(stop)은 후진 활강이 된다 — interfaces.py의 stop/brake.
    #
    # 유예창(perception_grace_sec): 세그 마스크는 프레임 단위로 깜빡인다. 한 프레임
    # 오탐이 곧바로 IDLE 폴백이 되면 그때부터 사람이 가서 재시작해야 하므로(자동
    # 재출발 없음이 명세다) 실외에서 미션이 계속 끊긴다.
    #
    # 유예 중의 명령은 **복구 주행**이다 — 저속(recovery_throttle_pct) + 소실 시점의
    # 조향(prev_steer_deg) 유지. 종전에는 브레이크 정지였는데, 명세는 '인도를 벗어날
    # 것 같을 때 좌우로 회피'이고 0803 실외 관찰은 '자꾸 정지만 함'이었다 — 정지는
    # 둘 다 어긴다. 조향을 유지하는 것이 최선의 사전 정보인 이유: 인도를 벗어나는
    # 중이라면 중심 오차가 커지며 조향이 이미 되돌리는 방향으로 커진 뒤에 소실되므로,
    # 그 조향을 계속 내면 되돌아온다. 눈먼 주행이 아니다 — 위 차도 가드가 이 위에서
    # 그대로 돌고, frame 자체가 없으면(카메라 장애) 복구하지 않고 브레이크로 간다.
    if context.state in GUARDED_STATES:
        lost = ("인지 두절" if frame is None
                else "인도 경계 소실" if not frame.sidewalk_detected else None)
        if lost is not None:
            bad_since = (context.perception_bad_since
                         if context.perception_bad_since is not None else now)
            if now - bad_since < params.perception_grace_sec:
                context = replace(context, perception_bad_since=bad_since)
                # 복구 주행은 인도가 안 보일 때만 한다. frame 자체가 없으면(카메라 장애)
                # 진짜 눈먼 주행이고, 앞을 막는 장애물이 잡혀 있으면 그쪽으로 기어가는
                # 셈이다 — 둘 다 브레이크가 맞다. 회피 기동으로 넘기지 않는 이유:
                # 인도가 안 보이는 상태에서 횡방향 기동은 인도를 벗어나는 방향일 수 있고,
                # 여기서 필요한 것은 '되돌아오기'다.
                if frame is None or frame.has_blocking_obstacle():
                    return context, BRAKE_COMMAND
                return context, DriveCommand(
                    throttle_pct=params.recovery_throttle_pct,
                    steering_offset_deg=context.prev_steer_deg)
            return (_enter(context, DriveState.IDLE, now, f"{lost} → 정지 + 수동 대기",
                           avoid_brake=False, perception_bad_since=None,
                           **trace_exit),
                    BRAKE_COMMAND)
        if context.perception_bad_since is not None:
            context = replace(context, perception_bad_since=None)  # 정상 프레임 → 타이머 해제
    elif frame is None:
        return context, BRAKE_COMMAND  # IDLE·PAUSED·ESCAPE에서 인지 두절: 파킹 브레이크 유지

    # 상태 디스패치
    if context.state is DriveState.IDLE:
        return _handle_idle(context, frame, requests, now)
    if context.state is DriveState.PAUSED:
        return _handle_paused(context, frame, requests, now)
    if context.state is DriveState.CRUISE:
        return _handle_cruise(context, params, frame, imu, now)
    if context.state is DriveState.TRACE:
        return _handle_trace(context, params, frame, imu, requests, now)
    if context.state is DriveState.INSPECT:
        return _handle_inspect(context, params, requests, now)
    if context.state is DriveState.AVOID:
        return _handle_avoid(context, params, frame, imu, now)
    if context.state is DriveState.ESCAPE:
        return _handle_escape(context, params, frame, now)
    if context.state is DriveState.SLOPE:
        return _handle_slope(context, params, frame, imu, now)
    if context.state is DriveState.TURN:
        return _handle_turn(context, params, frame, imu, now)
    if context.state is DriveState.DEAD_END:
        return _handle_dead_end(context, params, frame, imu, now)
    return context, STOP_COMMAND  # 도달 불가 — 방어적 정지


def _limit_steer_rate(context: StateMachineContext, params: StateMachineParams,
                      command: DriveCommand, now: float
                      ) -> Tuple[StateMachineContext, DriveCommand]:
    """조향 슬루율 제한 — 틱 간 조향 변화량을 steer_rate_deg_per_s × dt로 자른다.

    세그가 내는 중심 목표(중심선 적합값, 실패 시 무게중심)는 프레임마다 흔들린다.
    그 노이즈가 비례 조향을 그대로 통과하면
    서보가 매 틱 반대 방향으로 끌려가고, 주행선이 톱니처럼 떨린다.
    모든 상태의 명령이 이 한 지점을 지나므로(전이 틱·가드 폴백 포함) 컨텍스트의
    prev_steer_deg는 항상 '직전에 실제로 낸 조향'과 일치한다 — CRUISE 안에서만
    걸면 AVOID 정지(조향 0)를 거쳐 돌아올 때 기준값이 어긋난다.
    dt는 config의 loop_hz가 아니라 last_step_at과의 차이로 잰다: 보드에서는 실주기가
    세그 추론 시간만큼 늘어지고 흔들린다. 첫 틱(dt 없음)과 dt<=0은 제한하지 않는다.
    스로틀은 제한하지 않는다 — 정지·ESTOP은 항상 즉시 걸려야 한다.
    """
    target = command.steering_offset_deg
    dt = None if context.last_step_at is None else now - context.last_step_at
    if dt is not None and dt > 0.0:
        max_delta = abs(params.steer_rate_deg_per_s) * dt
        target = max(context.prev_steer_deg - max_delta,
                     min(context.prev_steer_deg + max_delta, target))
    return (replace(context, prev_steer_deg=target, last_step_at=now),
            replace(command, steering_offset_deg=target))


def step(context: StateMachineContext, params: StateMachineParams,
         frame: Optional[SegmentationFrame], imu: Optional[ImuReading],
         now: float, requests: ExternalRequests = ExternalRequests()
         ) -> Tuple[StateMachineContext, DriveCommand]:
    """FSM 한 틱. 입력만으로 (다음 컨텍스트, 주행 명령)을 계산한다 — I/O 없음.

    가드·상태 판단은 _decide()가 하고, 그 결과 명령의 조향에 슬루율 제한을 건다.
    """
    context, command = _decide(context, params, frame, imu, now, requests)
    return _limit_steer_rate(context, params, command, now)


# ======================================================================
# 상태 보관 래퍼 — main 루프가 쓰는 겉면. on_event 훅의 유일한 발화 지점.
# ======================================================================

OnEvent = Callable[[DriveState, DriveState, str, float], None]


def _noop_on_event(prev: DriveState, new: DriveState, reason: str, now: float) -> None:
    """기본 no-op 훅 — 상태 전이 알림의 유일한 출구. 3단계에서 통신이 여길 구독한다."""


class StateMachine:
    """순수 step()에 컨텍스트 보관과 외부 요청 플래그를 씌운 래퍼.

    외부 요청(request_*)은 다음 step 한 틱에서 소비된다 — **시작 요청만 예외로**
    `start_request_hold_sec` 동안 유지된다. IDLE 탈출 조건이 '시작 요청 + 인도 확인'
    둘이라, 누른 틱에 인도가 안 보이면(경계 소실로 IDLE에 떨어진 직후가 정확히 그
    상황이다) 요청이 조용히 소멸하고 조작자는 아무리 눌러도 나가지 못했다.
    유지창을 두는 것이지 무한 래치가 아니다: 눌러 두고 자리를 떠난 뒤 한참 있다
    인도가 화면에 들어와 스스로 출발하는 것은 '수동 재시작만이 IDLE의 출구'라는
    설계가 막으려던 바로 그 사고다.

    on_event(prev, new, reason, now)는 상태가 실제로 바뀐 틱에만 불린다.
    """

    def __init__(self, params: StateMachineParams, now: float,
                 on_event: Optional[OnEvent] = None):
        self._params = params
        self._context = initial_context(now)
        self._on_event: OnEvent = on_event if on_event is not None else _noop_on_event
        self._start_requested = False
        self._start_hold_since = None    # 유지창 기준 시각 (None = 유지 중 아님)
        self._pause_requested = False
        self._resume_requested = False
        self._cancel_requested = False
        self._estop_requested = False
        self._release_requested = False
        self._inspection_done = False
        self._defect_found = False

    @property
    def state(self) -> DriveState:
        return self._context.state

    @property
    def context(self) -> StateMachineContext:
        return self._context

    @property
    def start_pending(self) -> bool:
        """시작 요청이 아직 먹지 않아 유지창 안에서 재시도 중인가 (표시·로깅용)."""
        return self._start_hold_since is not None

    def request_start(self) -> None:
        """수동 출발 요청 (IDLE → CRUISE의 유일한 트리거).

        유지창은 여기서 새로 연다 — 다시 누르면 창이 갱신되고, step()의 재시도는
        이 경로를 지나지 않으므로 창을 늘리지 않는다.
        """
        self._start_requested = True
        self._start_hold_since = None

    def request_pause(self) -> None:
        """원격 일시정지 요청. 다음 step에서 PAUSED + 브레이크로 수렴한다.

        ESCAPE(차도 이탈 선회) 중에는 먹지 않고 그 틱에 버려진다 — PAUSABLE_STATES
        참조. 선회 중 즉시 세워야 하면 ESTOP을 쓴다.
        """
        self._pause_requested = True

    def request_resume(self) -> None:
        """원격 재개 요청. PAUSED에서 인도가 보일 때만 CRUISE로 간다."""
        self._resume_requested = True

    def request_cancel(self) -> None:
        """원격 임무 취소. IDLE 외 모든 상태에서 즉시 IDLE + 브레이크."""
        self._cancel_requested = True

    def request_estop(self) -> None:
        """수동 비상 정지 요청."""
        self._estop_requested = True

    def request_estop_release(self) -> None:
        """ESTOP 수동 해제 요청 (ESTOP의 유일한 출구)."""
        self._release_requested = True

    def notify_inspection_done(self) -> None:
        """정밀 판정 완료 통지 (INSPECT의 정상 출구) — main 루프가 부른다."""
        self._inspection_done = True

    def notify_defect_found(self) -> None:
        """TRACE 중 상시 탐지가 파손 후보를 봤다는 통지 (TRACE → INSPECT의 트리거)."""
        self._defect_found = True

    def step(self, frame: Optional[SegmentationFrame],
             imu: Optional[ImuReading], now: float) -> DriveCommand:
        requests = ExternalRequests(start=self._start_requested,
                                    pause=self._pause_requested,
                                    resume=self._resume_requested,
                                    cancel=self._cancel_requested,
                                    estop=self._estop_requested,
                                    estop_release=self._release_requested,
                                    inspection_done=self._inspection_done,
                                    defect_found=self._defect_found)
        self._start_requested = self._estop_requested = self._release_requested = False
        self._pause_requested = self._resume_requested = self._cancel_requested = False
        self._inspection_done = self._defect_found = False

        prev = self._context
        self._context, command = step(prev, self._params, frame, imu, now, requests)

        # 시작 요청이 먹지 않았으면(IDLE 유지 = 인도 미확인·인지 두절) 유지창 안에서
        # 다음 틱에 재시도한다. 창을 넘기면 요청을 버린다 — 다시 눌러야 한다.
        if requests.start and self._context.state is DriveState.IDLE:
            if self._start_hold_since is None:
                self._start_hold_since = now
            if now - self._start_hold_since < self._params.start_request_hold_sec:
                self._start_requested = True
            else:
                self._start_hold_since = None
        elif requests.start:
            self._start_hold_since = None   # 출발 성공 — 유지창 종료

        if self._context.state is not prev.state:
            self._on_event(prev.state, self._context.state, self._context.reason, now)
        return command
