"""FSM 자료 정의 — 파라미터·외부 요청·컨텍스트, 그리고 전이 헬퍼.

여기 있는 것은 **값과 그 값을 바꾸는 한 가지 방법**뿐이다: 상태 전이는 _enter 하나로만
일어나고, 그래서 '전이할 때마다 지워야 하는 필드'(디바운스 타이머 등)를 한 곳에서
보장할 수 있다. 판단은 전부 handlers·machine 이 한다.
"""

from dataclasses import dataclass, replace
from typing import Optional, Tuple

from driving.states import AvoidPhase, DeadEndPhase, DriveCommand, DriveState



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
    recovery_reverse_throttle_pct: float  # 막혀서 안 보일 때의 **후진** 복구 스로틀
    recovery_reverse_debounce_sec: float  # 막힘+소실이 이만큼 지속돼야 후진을 시작한다
                                    # (깜빡임 한 프레임으로 후진을 걸지 않는다)
    recovery_reverse_min_sec: float # 후진 복구 **최소** 시간 — 한 틱 물러나 바닥이
                                    # 보이는 것으로 끊기지 않게 하는 래치
    recovery_reverse_sec: float     # 후진 복구 상한 — 후방은 카메라 사각이라 짧게
    recovery_backtrack_sec: float   # 유예가 다한 뒤 IDLE 직전에 한 번 하는 **되짚기 후진**.
                                    # 막힘이 아니라 '벗어나서' 안 보이는 경우의 마지막 시도다
    avoid_recheck_sec: float
    avoid_bypass_throttle_pct: float    # 회피 선회 스로틀
    avoid_swerve_min_sec: float         # 벗어나기 **최소 커밋** 시간 — 이 전에는 장애물이
                                        # 사라져도 국면을 끝내지 않는다(찔끔 선회 방지)
    avoid_pass_sec: float               # 통과 직진 시간 (옆은 카메라 사각 — 시간 판정)
    avoid_return_center_abs: float      # 복귀 종료: |목표 오차| ≤ 이 값 (시각 판정).
                                        # 블록이 확정 상태면 목표는 인도 중심이 아니라
                                        # 추종과 같은 혼합 목표다 (블록 최우선)
    avoid_phase_timeout_sec: float      # 국면별 진행 상한 — 초과 시 다음 국면/CRUISE
    avoid_timeout_sec: float            # 회피 **정체** 상한 — 마지막 국면 전진 시각부터 잰다.
                                        # 진입 시각 기준이면 정상 회피 한 바퀴가 잘린다
    avoid_hard_timeout_sec: float       # 회피 **절대** 상한 — 진행이 계속 세어져도 진입
                                        # 이후 이만큼이면 나간다(SWERVE↔PASS 무한 루프 차단)
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
    slope_downhill_hold_pitch_deg: float    # 이보다 급한 내리막에서는 정지 명령이
                                        # **경사 비례 역스로틀 유지**가 된다 (위치 고수)
    slope_downhill_hold_gain_pct_per_deg: float  # 유지 역스로틀 = 이득 × |pitch|
    slope_downhill_hold_max_pct: float  # 유지 역스로틀 상한 (개루프라 후진 폭주를 막는다)
    slope_downhill_brake_pitch_deg: float   # 이보다 급한 내리막은 숏브레이크 정지 유지
    slope_downhill_brake_timeout_sec: float  # 그 정지가 이만큼 이어지면 ESTOP 으로 승격
                                        # (스스로 못 푸는 정지는 관제에 보여야 한다)
    slope_downhill_avoid_brake_pitch_deg: float  # 이보다 급한 **내리막**에서만 장애물에
                                        # '브레이크 대기'. 완만한 경사와 오르막 전체는
                                        # 평지처럼 좌/우로 회피한다 (오르막에서 서는 것은
                                        # 기다림이 아니라 못 올라가게 되는 것이다)
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
    avoid_thrash_limit: int             # 이 횟수만큼 회피가 연달아 반복되면 재정향으로
                                        # 승격 — 인지가 '한쪽은 열렸다'고 우겨도 무시한다
    avoid_thrash_window_sec: float      # 연속으로 치는 판정 창(직전 회피와의 간격)
    dead_end_max_yaw_deg: float         # |회전| 상한 — 초과 시 갇힘으로 보고 IDLE.
                                        # 완료 기준이 아니다(완료는 시각이다)
    dead_end_attempt_window_sec: float  # 탈출 후 이 시간을 못 달리고 다시 막히면
                                        # 시도 번호를 이어서 센다(같은 자리로 본다)
    dead_end_uturn_after_cycles: int    # 이 번째 시도에서 U턴 (1 유리한 쪽 / 2 반대쪽 / 3 U턴)
    dead_end_uturn_target_yaw_deg: float   # U턴 종료 yaw 누적각
    dead_end_uturn_tolerance_deg: float    # U턴 완료 여유
    dead_end_uturn_sec: float           # IMU가 없을 때 U턴을 시간으로 대신하는 길이
    dead_end_straighten_deg: float      # 이 이내로 조향이 돌아오면 정렬 완료로 본다
    dead_end_straighten_max_sec: float  # 정렬 상한 — 서보가 안 따라와도 영원히 안 선다
    dead_end_uturn_min_yaw_deg: float   # 이만큼은 돌고 나서야 '경로 확보' 이탈을 인정한다
    dead_end_uturn_min_sec: float       # 위의 시간판(IMU 없을 때)
    dead_end_uturn_escape_limit: int    # '정지 + 수동 대기' 대신 U턴으로 탈출을 시도할 수
                                        # 있는 연속 횟수. 넘기면 그때 IDLE — 무한 배회를
                                        # 막는 유일한 장치다
    dead_end_uturn_escape_window_sec: float  # 위 카운터의 연속 판정 창 (넘겨 달렸으면 리셋)
    dead_end_uturn_direction: int       # U턴 회전 방향 고정 (-1=좌, +1=우).
                                        # 탈출 시도처럼 교대하지 않는다 — 회차는 한 방향
                                        # 으로 끝까지 도는 기동이라 중간에 바뀌면 이미 번
                                        # 각도가 상쇄된다
    dead_end_steer_left_deg: float      # 선회 전진 좌조향 크기
    dead_end_steer_right_deg: float     # 선회 전진 우조향 크기 (우 한계가 더 작다)
    dead_end_throttle_pct: float        # 전진 호·후진 공통 스로틀 크기
    dead_end_forward_sec: float         # 선회 전진 호 1회 시간
    dead_end_retrace_sec: float         # 되짚기 후진 시간 — 실패한 호를 같은 조향각으로
                                        # 되감아 원점으로 돌아간다 (forward_sec 과 짝이다)
    dead_end_reverse_min_sec: float     # 후진 최소 시간 — 이만큼은 무조건 뺀다
    dead_end_reverse_max_sec: float     # 후진 상한 (후방은 카메라 사각 — 무한정 못 뺀다)
    dead_end_timeout_sec: float         # 회차 전체 상한 — 초과 시 IDLE(수동 대기)
    inspect_confirm_sec: float          # 블록 **발견** 확정에 필요한 지속 시간
    inspect_release_sec: float          # 블록 **소실** 확정에 필요한 지속 시간 (비대칭 —
                                        # 세그 재현율이 낮아 중간에 자주 끊긴다)
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
        절을 지역 변수로 먼저 풀어 두므로 각 줄은 '어느 절의 어느 키'만 남는다.
        """
        cruise, avoid, escape = driving["cruise"], driving["avoid"], driving["escape"]
        slope, turn, dead_end = driving["slope"], driving["turn"], driving["dead_end"]
        inspect, trace = driving["inspect"], driving["trace"]
        return cls(
            cruise_throttle_pct=cruise["throttle_pct"],
            cruise_steer_gain_deg=cruise["steer_gain_deg"],
            cruise_steer_left_max_deg=cruise["steer_left_max_deg"],
            cruise_steer_right_max_deg=cruise["steer_right_max_deg"],
            costmap_target_weight=cruise["costmap"]["target_weight"],
            costmap_commit_weight=cruise["costmap"]["commit_weight"],
            steer_rate_deg_per_s=driving["steer_rate_deg_per_s"],
            perception_grace_sec=driving["perception_grace_sec"],
            recovery_throttle_pct=driving["recovery_throttle_pct"],
            recovery_reverse_throttle_pct=driving["recovery_reverse_throttle_pct"],
            recovery_reverse_min_sec=driving["recovery_reverse_min_sec"],
            recovery_reverse_debounce_sec=driving["recovery_reverse_debounce_sec"],
            recovery_reverse_sec=driving["recovery_reverse_sec"],
            recovery_backtrack_sec=driving["recovery_backtrack_sec"],
            avoid_recheck_sec=avoid["recheck_sec"],
            avoid_bypass_throttle_pct=avoid["bypass_throttle_pct"],
            avoid_swerve_min_sec=avoid["swerve_min_sec"],
            avoid_pass_sec=avoid["pass_sec"],
            avoid_return_center_abs=avoid["return_center_abs"],
            avoid_phase_timeout_sec=avoid["phase_timeout_sec"],
            avoid_timeout_sec=avoid["timeout_sec"],
            avoid_hard_timeout_sec=avoid["hard_timeout_sec"],
            escape_throttle_pct=escape["throttle_pct"],
            escape_steer_left_deg=escape["steer_left_deg"],
            escape_steer_right_deg=escape["steer_right_deg"],
            escape_clear_sec=escape["clear_sec"],
            escape_timeout_sec=escape["timeout_sec"],
            slope_enter_pitch_deg=slope["enter_pitch_deg"],
            slope_exit_pitch_deg=slope["exit_pitch_deg"],
            slope_uphill_throttle_gain_pct_per_deg=(
                slope["uphill_throttle_gain_pct_per_deg"]),
            slope_uphill_throttle_max_pct=slope["uphill_throttle_max_pct"],
            slope_downhill_throttle_gain_pct_per_deg=(
                slope["downhill_throttle_gain_pct_per_deg"]),
            slope_downhill_throttle_min_pct=slope["downhill_throttle_min_pct"],
            slope_downhill_hold_pitch_deg=slope["downhill_hold_pitch_deg"],
            slope_downhill_hold_gain_pct_per_deg=(
                slope["downhill_hold_gain_pct_per_deg"]),
            slope_downhill_hold_max_pct=slope["downhill_hold_max_pct"],
            slope_downhill_brake_pitch_deg=slope["downhill_brake_pitch_deg"],
            slope_downhill_brake_timeout_sec=(
                slope["downhill_brake_timeout_sec"]),
            slope_downhill_avoid_brake_pitch_deg=(
                slope["downhill_avoid_brake_pitch_deg"]),
            turn_enter_steer_demand_deg=turn["enter_steer_demand_deg"],
            turn_confirm_sec=turn["confirm_sec"],
            turn_throttle_pct=turn["throttle_pct"],
            turn_steer_left_deg=turn["steer_left_deg"],
            turn_steer_right_deg=turn["steer_right_deg"],
            turn_min_progress_yaw_deg=turn["min_progress_yaw_deg"],
            turn_min_progress_sec=turn["min_progress_sec"],
            turn_max_yaw_deg=turn["max_yaw_deg"],
            turn_exit_center_abs=turn["exit_center_abs"],
            turn_timeout_sec=turn["timeout_sec"],
            avoid_thrash_limit=avoid["thrash_limit"],
            avoid_thrash_window_sec=avoid["thrash_window_sec"],
            dead_end_max_yaw_deg=dead_end["max_yaw_deg"],
            dead_end_attempt_window_sec=dead_end["attempt_window_sec"],
            dead_end_uturn_after_cycles=dead_end["uturn_after_cycles"],
            dead_end_uturn_target_yaw_deg=dead_end["uturn_target_yaw_deg"],
            dead_end_uturn_tolerance_deg=dead_end["uturn_tolerance_deg"],
            dead_end_uturn_sec=dead_end["uturn_sec"],
            dead_end_straighten_deg=dead_end["straighten_deg"],
            dead_end_straighten_max_sec=dead_end["straighten_max_sec"],
            dead_end_uturn_min_yaw_deg=dead_end["uturn_min_yaw_deg"],
            dead_end_uturn_min_sec=dead_end["uturn_min_sec"],
            dead_end_uturn_escape_limit=dead_end["uturn_escape_limit"],
            dead_end_uturn_escape_window_sec=dead_end["uturn_escape_window_sec"],
            dead_end_uturn_direction=(-1 if str(
                dead_end["uturn_direction"]).lower().startswith("l") else 1),
            dead_end_steer_left_deg=dead_end["steer_left_deg"],
            dead_end_steer_right_deg=dead_end["steer_right_deg"],
            dead_end_throttle_pct=dead_end["throttle_pct"],
            dead_end_forward_sec=dead_end["forward_sec"],
            dead_end_retrace_sec=dead_end["retrace_sec"],
            dead_end_reverse_min_sec=dead_end["reverse_min_sec"],
            dead_end_reverse_max_sec=dead_end["reverse_max_sec"],
            dead_end_timeout_sec=dead_end["timeout_sec"],
            inspect_confirm_sec=inspect["confirm_sec"],
            inspect_release_sec=inspect["release_sec"],
            inspect_enter_proximity=inspect["enter_proximity"],
            inspect_timeout_sec=inspect["timeout_sec"],
            inspect_cooldown_sec=inspect["cooldown_sec"],
            trace_approach_throttle_pct=trace["approach_throttle_pct"],
            trace_throttle_pct=trace["throttle_pct"],
            trace_timeout_sec=trace["timeout_sec"],
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
    avoid_progress_at: 이 회피에서 **마지막으로 국면이 앞으로 넘어간** 시각
        (SWERVE→PASS, PASS→RETURN). 정체 상한(avoid_timeout_sec)의 기준점이다 —
        진입 시각에서 재면 '오래 걸린 정상 회피'와 '제자리 맴돌기'를 구분하지 못하고,
        0805 실기에서 회피 52회 중 21회가 그 구분 없이 후진 재정향으로 갔다.
        SWERVE 재시작(_swerve)은 진행이 아니다 — 그 방향으로 세면 SWERVE↔PASS 루프가
        영원히 '진행 중'이 된다. 그쪽은 avoid_hard_timeout_sec 이 자른다.
    avoid_trace_started_at: 이 회피가 **추종(TRACE) 도중에** 시작됐다면 그 추종 세션의
        시작 시각, 아니면 None. 회피가 끝났을 때 블록이 아직 확정 상태면 CRUISE 가
        아니라 TRACE 로 직접 돌아가는데, 그때 이 값을 되돌려 추종 상한이 회피마다
        리셋되지 않게 한다 — 상한이 리셋되면 '유도선 오탐에 영구히 갇히지 않는다'는
        그 상한의 목적이 사라진다.
    uturn_escape_count / uturn_escape_last: '정지 + 수동 대기' 대신 U턴으로 탈출을
        시도한 연속 횟수와 마지막 시각. 0806부터 후진 복구 상한·재정향 상한·경계 소실
        유예 만료가 전부 U턴으로 승격되는데, 그 승격에 한도가 없으면 로봇이 영영
        사람을 부르지 않고 배회한다. 창(uturn_escape_window_sec)을 정상 주행으로
        넘겼으면 실제로 벗어난 것이니 1부터 다시 센다.
    escape_direction / escape_clear_since: 차도 이탈 선회 방향과, 차도 위험이 해소된
        시각. clear_since가 escape_clear_sec을 넘기면 CRUISE 복귀다 — 즉시 복귀하면
        코리도 경계에서 위험 판정이 켜졌다 꺼지며 ESCAPE↔CRUISE를 왕복한다.
    avoid_thrash_count / avoid_thrash_last: 회피가 연달아 반복된 횟수와 마지막 회피
        시각. 인지가 '한쪽은 열려 있다'고 잘못 판단하면 SWERVE→복귀→재막힘이
        무한 반복되고(0804 실내 실측: 90초에 14회, 그동안 all_directions_blocked는
        **한 번도** True가 되지 않았다) 재정향은 그 위에 있어 영원히 발동하지 않는다.
        그래서 '막혔다'를 인지에만 맡기지 않고 **FSM 자신의 이력**으로도 판단한다 —
        창 안에서 limit번 반복되면 인지 판정과 무관하게 재정향으로 승격한다.
    reverse_recovery_count / reverse_recovery_last: 후진 복구가 연달아 걸린 횟수와
        마지막 시각. 후진 중 인도가 한 프레임만 보여도 perception_bad_since가
        해제되므로, 벽 앞 깜빡임에서는 '2.5초 후진 → 잠깐 보임 → 전진 → 다시 벽'이
        무한 반복되며 상한이 무의미해진다. 게다가 이 가드는 상태 핸들러보다 앞이라
        후진이 걸려 있는 동안 AVOID가 실행되지 않아 반복 회피 카운터도 오르지 않는다
        — 즉 후진 복구가 재정향 승격을 막는다. 그래서 후진 복구 반복 자체를
        승격 신호로 쓴다(0804).
    reverse_recovery_since: 후진 복구 에피소드가 시작된 시각(진행 중이 아니면 None).
        최소/최대 시간을 **이 한 기준점**에서 잰다 — 틱마다 갱신되는 래치로 만들면
        재무장되며 상한이 무의미해진다.
        후진은 '인도가 안 보이는 동안'만 하는데, 벽에서 조금만 물러나도 바닥이 한
        프레임 보이고 그 순간 가드가 풀려 CRUISE가 다시 전진해 벽에 박는다 —
        0804 실기 관찰 '후진 찔끔하고 벽에 계속 쳐박는다'가 정확히 이것이다.
        상한(recovery_reverse_sec)만 있고 하한이 없어 실제 후진은 한두 틱이었다.
        한 번 시작하면 이 시각까지는 프레임이 좋아 보여도 계속 뺀다.
    dead_end_phase / dead_end_phase_since: DEAD_END 재정향의 하위 국면
        (후진 → 선회 전진)과 그 국면의 시작 시각.
    dead_end_direction: 재정향 선회 방향(-1=좌, +1=우). 진입 시 코스트맵으로
        한 번 고르고 유지한다 — AVOID의 SWERVE와 달리 매 틱 다시 고르지 않는다.
        막다른 길에서는 어느 쪽도 '열려' 보이지 않아 재선택이 좌우 진동만 낳는다.
    dead_end_attempt / dead_end_attempt_at: 몇 번째 탈출 시도인지와 그 시각.
        시도마다 **방향을 바꾼다**: 1회 유리한 쪽 → 2회 반대쪽 → 3회 U턴.
        한 방향으로만 반복하면 그쪽이 막힌 자리에서는 몇 번을 해도 같은 결과다.
        시각을 함께 들고 있는 이유: 재정향은 CRUISE를 거쳐 재진입하는 일이 잦아
        (후진 복구 → 선회 → 잠깐 풀림 → 다시 막힘) 진입할 때마다 0으로 리셋하면
        시도가 영영 쌓이지 않는다 — 0804 실기 '3회 실패가 안 쌓인다'가 그것이다.
        창 안에 다시 들어오면 이어서 센다.
    dead_end_uturn_since: U턴 국면 시작 시각(IMU 없을 때 시간으로 완료를 재기 위함).
    dead_end_entry_yaw_deg: 재정향 시작 방위. **완료 판정에는 쓰지 않는다** —
        완료는 시각(새 경로 확보)이고, 이 값은 무한 회전을 막는 상한 판정용이다.
        IMU가 없으면(=진입 시 None) 상한만 비활성이고 재정향 자체는 돈다.
    backtrack_since: 되짚기 후진이 시작된 시각(진행 중이 아니면 None). 경계 소실
        유예가 다한 뒤 IDLE 로 떨어지기 **직전에 한 번만** 하는 마지막 시도이고,
        시작 시각 하나로 길이를 재므로 틱마다 재무장되지 않는다.
    slope_brake_since: 급경사 하강 안전 정지가 시작된 시각. 그 정지는 스스로 푸는
        출구가 없어(경사는 로봇이 서 있는 한 그대로다) 오래 끌면 ESTOP 으로 승격해
        관제에 드러낸다 — 종전에는 SLOPE 에 머문 채 무한 브레이크였고, 서버 투영은
        내내 PATROLLING(순찰 중)이라 아무도 그 정지를 알 수 없었다.
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
    avoid_progress_at: Optional[float] = None
    avoid_trace_started_at: Optional[float] = None
    avoid_thrash_count: int = 0
    avoid_thrash_last: Optional[float] = None
    uturn_escape_count: int = 0
    uturn_escape_last: Optional[float] = None
    reverse_recovery_count: int = 0
    reverse_recovery_last: Optional[float] = None
    reverse_recovery_since: Optional[float] = None
    escape_direction: int = 0
    escape_clear_since: Optional[float] = None
    dead_end_phase: DeadEndPhase = DeadEndPhase.REVERSE
    dead_end_phase_since: float = 0.0
    dead_end_direction: int = 0
    dead_end_attempt: int = 0
    dead_end_attempt_at: Optional[float] = None
    dead_end_uturn_since: Optional[float] = None
    dead_end_entry_yaw_deg: Optional[float] = None
    slope_brake_since: Optional[float] = None
    backtrack_since: Optional[float] = None


def initial_context(now: float) -> StateMachineContext:
    return StateMachineContext(state=DriveState.IDLE, entered_at=now, reason="초기화")


#: 가드가 그 자리에서 판단을 끝냈을 때의 결과. None 이면 다음 가드로, 전부 None 이면
#: 상태 디스패치로 간다. 가드가 컨텍스트만 갱신하고 넘길 수도 있어(인지 가드)
#: 그쪽은 (컨텍스트, 결과) 짝을 돌려준다.
GuardResult = Optional[Tuple[StateMachineContext, DriveCommand]]


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
    # 회피 진행 시각과 '이 회피가 물고 온 추종 세션'도 회피 한 번 안에서만 의미가 있다.
    # 남겨 두면 다음 회피가 지난 회피의 진행 시각으로 시작해 정체 상한이 어긋나고,
    # 장애물 회피와 무관한 상태 전이 뒤에 엉뚱한 추종 세션이 되살아난다.
    fields.setdefault("avoid_progress_at", None)
    fields.setdefault("avoid_trace_started_at", None)
    # 급경사 하강 정지 타이머도 전이마다 지운다 — turn_pending_since 와 같은 이유로
    # '끊기지 않은 체류' 안에서만 의미가 있는 값이다. 남겨 두면 지난 경사에서 찍힌
    # 시각이 살아 있다가, 한참 뒤 다시 급경사에 들어선 첫 틱에 곧바로 ESTOP 이 된다.
    fields.setdefault("slope_brake_since", None)
    # 되짚기 후진도 '끊기지 않은 소실 에피소드' 안에서만 의미가 있다.
    fields.setdefault("backtrack_since", None)
    return replace(context, state=state, entered_at=now, reason=reason, **fields)
