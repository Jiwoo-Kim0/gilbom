"""가드 체인 → 상태 디스패치 → 슬루율 제한, 그리고 상태 보관 래퍼.

**가드의 순서가 곧 안전 우선순위다.** 어떤 상태에 있든 위쪽 가드가 먼저 이긴다:
수동/차도 정지 → 차도 이탈 선회 → 원격 제어 → 인지 폴백. 그 뒤에야 상태 핸들러가
자기 판단을 한다 (_decide).
"""

from dataclasses import replace
from typing import Callable, Optional, Tuple

from driving.states import (BRAKE_COMMAND, STOP_COMMAND, AvoidPhase, DeadEndPhase,
                            DriveCommand, DriveState, ESCAPE_SOURCE_STATES,
                            GUARDED_STATES, PAUSABLE_STATES)
from hardware.interfaces import ImuReading
from perception.interfaces import SegmentationFrame

from driving.state_machine.data import (ExternalRequests, GuardResult,
                                        StateMachineContext, StateMachineParams,
                                        _enter, initial_context)
from driving.state_machine.handlers import (
    _attempt_direction, _debounce_braille, _enter_dead_end, _handle_avoid,
    _handle_cruise, _handle_dead_end, _handle_escape, _handle_estop, _handle_idle,
    _handle_inspect, _handle_paused, _handle_slope, _handle_trace, _handle_turn,
    _next_attempt, _uturn_escape)
from driving.state_machine.steering import _bypass_direction, _escape_direction, \
    _escape_steer_deg



# ======================================================================
# 순수 step(): 공통 가드 → 상태 디스패치 → 조향 슬루율 제한
# ======================================================================

def _trace_exit_fields(context: StateMachineContext, now: float) -> dict:
    """가드로 TRACE·INSPECT 를 떠날 때 함께 적을 필드 — **정차** 쿨다운 + 추종 세션 종료.

    정차 쿨다운을 남기는 이유는 중복 판정·중복 보고 방지다 — 사람이 ESTOP을 풀거나
    재시작한 직후 같은 파손 위에 서 있을 것이므로, 그 자리에서 다시 정차해 같은 판정을
    올리는 것은 의미가 없다.
    **추종 재진입은 막지 않는다**: 가드 이탈은 차도 덮임(ESTOP)·차도 진입(ESCAPE)·
    경계 소실(IDLE) 셋이고, 어느 쪽도 '이 유도선을 다 봤다'는 뜻이 아니다. 사람이 다시
    출발시키거나 선회로 차도를 벗어났다면 중단된 검사를 이어가는 것이 맞고, 여기서
    추종을 막으면 블록 추종이 복귀 뒤 조용히 꺼진다.
    """
    if context.state in (DriveState.TRACE, DriveState.INSPECT):
        return {"last_inspect_done_at": now, "trace_started_at": None}
    return {}


def _debounce_step(context: StateMachineContext, params: StateMachineParams,
                   frame: Optional[SegmentationFrame], now: float
                   ) -> StateMachineContext:
    """점자블록 발견 디바운스 — **어떤 가드보다 먼저** 매 틱 갱신한다.

    가드들은 전부 조기 return 이므로, 갱신이 뒤에 있으면 그 상태에 머무는 동안 판정이
    얼어붙는다 — ESCAPE로 4초 선회하고 나온 틱에 이미 지나친 블록의 braille_stable=True
    가 그대로 남아, 확정 시간 없이 곧바로 없는 블록을 추종하러 간다(경계 소실 유예도
    마찬가지).
    """
    if frame is None:
        return context
    stable, pending_since = _debounce_braille(context, params,
                                              frame.braille_detected, now)
    return replace(context, braille_stable=stable, braille_pending_since=pending_since)


def _guard_estop(context: StateMachineContext, frame: Optional[SegmentationFrame],
                 now: float, requests: ExternalRequests, trace_exit: dict
                 ) -> GuardResult:
    """가드 1 — 수동 ESTOP 요청 / **코리도가 차도로 덮임**. 되돌릴 수 없는 두 경우다."""
    if requests.estop:
        return (_enter(context, DriveState.ESTOP, now, "수동 ESTOP 요청", **trace_exit),
                BRAKE_COMMAND)
    if frame is not None and frame.roadway_critical:
        return (_enter(context, DriveState.ESTOP, now,
                       "코리도가 차도로 덮임 → 즉시 정지 (선회로는 늦음)",
                       **trace_exit), BRAKE_COMMAND)
    return None


def _guard_roadway_escape(context: StateMachineContext, params: StateMachineParams,
                          frame: Optional[SegmentationFrame], now: float,
                          trace_exit: dict) -> GuardResult:
    """가드 2 — 차도·횡단보도가 코리도에 진입 → **선회 이탈**. 정지가 아니다.

    명세: '차도를 만나면 좌 or 우로 방향 전환 후 인도로 주행'. 가드 1과 나눈 이유는
    되돌릴 수 있는 상황과 그렇지 않은 상황에 같은 대응을 하지 않기 위해서다 — 인도 옆이
    차도인 것은 정상이고, 그 판정 하나로 미션을 끝내면(종전 ESTOP) 오탐 한 번에 순찰이
    끝난다. ESCAPE 자신은 ESCAPE_SOURCE_STATES 에 없다 — 있으면 매 틱 재진입해
    entered_at 이 리셋되고 escape.timeout_sec(선회로 못 벗어나면 ESTOP)이 영원히
    걸리지 않는다.
    """
    if not (frame is not None and frame.roadway_encroachment_risk
            and context.state in ESCAPE_SOURCE_STATES):
        return None
    direction = _escape_direction(frame)
    return (_enter(context, DriveState.ESCAPE, now,
                   f"차도 진입 위험 → {'좌' if direction < 0 else '우'}로 이탈 선회",
                   escape_direction=direction, escape_clear_since=None,
                   avoid_brake=False, **trace_exit),
            DriveCommand(throttle_pct=params.escape_throttle_pct,
                         steering_offset_deg=_escape_steer_deg(params, direction)))


def _guard_remote_control(context: StateMachineContext, now: float,
                          requests: ExternalRequests, trace_exit: dict) -> GuardResult:
    """가드 2.5 — 원격 미션 제어(취소·일시정지).

    수동 ESTOP·차도 이탈 선회보다는 낮고 인지 폴백보다 먼저다. 취소·일시정지는 카메라가
    끊겨도 즉시 정지로 수렴해야 하므로 인지 가드 위에 있어야 하고, 차도 가드 아래인
    이유는 PAUSABLE_STATES 주석에 있다 (선회 중 브레이크 = 차도 위에 정지).
    """
    if requests.cancel and context.state is not DriveState.IDLE:
        return (_enter(context, DriveState.IDLE, now, "원격 임무 취소",
                       avoid_brake=False, perception_bad_since=None, **trace_exit),
                BRAKE_COMMAND)
    if requests.pause and context.state in PAUSABLE_STATES:
        return (_enter(context, DriveState.PAUSED, now, "원격 일시정지",
                       avoid_brake=False, perception_bad_since=None, **trace_exit),
                BRAKE_COMMAND)
    return None


def _reverse_recovery_step(context: StateMachineContext, params: StateMachineParams,
                           frame: Optional[SegmentationFrame],
                           imu: Optional[ImuReading], now: float) -> GuardResult:
    """진행 중인 **후진 복구**를 한 틱 민다 (막혀서 인도가 안 보이는 경우).

    최소 시간까지는 프레임이 좋아 보여도 계속 뺀다 — 벽에서 한 틱 물러나 바닥이 보이는
    것은 '풀렸다'가 아니라 '이제 겨우 보인다'다. 시작 시각 하나로 최소·최대를 다 재므로
    재무장으로 늘어나지 않는다.
    """
    reversed_for = now - context.reverse_recovery_since
    if frame is None:                       # 카메라 장애 — 눈먼 후진은 금물
        return (replace(context, reverse_recovery_since=None), BRAKE_COMMAND)
    if reversed_for >= params.recovery_reverse_sec:
        # 상한까지 뺐는데도 '경로 인지'가 안 됐다. 그런데 **갈 방향이 아예 없는
        # 것과, 정면 장애물이 안 사라지는 것은 다르다** — 좁은 실내에서 벽·가구는
        # 미분류라 1m를 물러나도 밴드에 계속 남는다(0804 실기: sidewalk=True·
        # blocked_all=False 인데 blocking=True 라 IDLE 로 떨어졌다).
        # 갈 방향이 남아 있으면 사람을 부르지 말고 꺾어서 시도한다.
        # 정말 전 방향이 막혔을 때만 수동 대기로 간다.
        if not frame.all_directions_blocked:
            attempt = _next_attempt(context, params, now)
            direction = _attempt_direction(frame, attempt, context.dead_end_direction)
            return _enter_dead_end(
                context, params, now,
                f"후진 상한 · 갈 방향은 있음 → {attempt}번째 시도"
                f" ({'좌' if direction < 0 else '우'} 선회)",
                phase=DeadEndPhase.TURN_ARC, attempt=attempt, direction=direction,
                entry_yaw=(None if imu is None else imu.yaw_deg),
                perception_bad_since=None, reverse_recovery_since=None,
                dead_end_uturn_since=None)
        # 전 방향이 막혔다. 종전에는 여기가 곧 미션의 끝(IDLE)이었고, 0805 세션 7의
        # 미션 종료 33회 중 11회가 이 한 줄이었다 — 가장 큰 단일 사유다.
        # **앞으로 못 간다는 것이 곧 서야 한다는 뜻은 아니다**: 뒤로는 방금 지나온,
        # 통과가 확인된 길이 있다. 방향을 되돌리고 계속 간다 (0806 실기 지시).
        return _uturn_escape(context, params, frame, imu, now,
                             "후진 복구 상한 — 전 방향 막힘")
    # 여기서도 종료 조건은 '경로 인지'다. 인도가 보이기만 하고 정면이
    # 여전히 막혀 있으면 나갈 수 없다 — 그대로 풀면 선회 국면이 벽을 마주한
    # 채 시작한다.
    path_seen = (frame.sidewalk_detected
                 and not frame.all_directions_blocked
                 and not frame.has_blocking_obstacle())
    if reversed_for < params.recovery_reverse_min_sec or not path_seen:
        return context, DriveCommand(
            throttle_pct=-params.recovery_reverse_throttle_pct,
            steering_offset_deg=0.0)
    # 최소 시간을 채웠고 인도도 보인다 — 뒤로는 충분히 뺐다.
    # **CRUISE로 그냥 돌려보내지 않는다.** 여기서 풀어주면 CRUISE가 직진하고,
    # 방금 물러난 그 벽에 그대로 다시 박는다(0804 실기: '후진만 하다가 경로
    # 나와도 직진 쳐박아버린다'). 뒤로 뺀 목적은 **꺾을 공간을 만든 것**이므로,
    # 유리한 쪽으로 꺾는 국면에 인계한다.
    attempt = _next_attempt(context, params, now)
    direction = _attempt_direction(frame, attempt, context.dead_end_direction)
    if attempt >= params.dead_end_uturn_after_cycles:
        return _enter_dead_end(
            context, params, now,
            f"후진 복구 완료 · {attempt}번째 시도 → U턴",
            phase=DeadEndPhase.UTURN, attempt=attempt,
            direction=params.dead_end_uturn_direction,
            entry_yaw=(None if imu is None else imu.yaw_deg),
            perception_bad_since=None, reverse_recovery_since=None,
            dead_end_uturn_since=now)
    return _enter_dead_end(
        context, params, now,
        f"후진 복구 완료 → {attempt}번째 시도"
        f" ({'좌' if direction < 0 else '우'} 선회)",
        phase=DeadEndPhase.TURN_ARC, attempt=attempt, direction=direction,
        entry_yaw=(None if imu is None else imu.yaw_deg),
        perception_bad_since=None, reverse_recovery_since=None,
        dead_end_uturn_since=None)


def _start_reverse_recovery(context: StateMachineContext, params: StateMachineParams,
                            frame: SegmentationFrame, imu: Optional[ImuReading],
                            now: float) -> Tuple[StateMachineContext, DriveCommand]:
    """막혀서 안 보이는 경우의 **후진 시작** (디바운스는 호출부가 이미 통과시켰다).

    후진 복구가 창 안에서 반복되면 뒤로 빼는 것만으로는 상황이 풀리지 않는다는
    뜻이다 — 방향을 바꿔야 한다. 여기서 직접 재정향으로 보낸다: 이 가드가 핸들러보다
    앞이라 AVOID를 거쳐 승격되기를 기다리면 영영 오지 않는다.
    """
    episodes = (context.reverse_recovery_count + 1
                if (context.reverse_recovery_last is not None
                    and now - context.reverse_recovery_last
                    <= params.avoid_thrash_window_sec)
                else 1)
    if episodes >= params.avoid_thrash_limit:
        return _enter_dead_end(
            context, params, now,
            f"후진 복구 {episodes}회 반복(안 풀림) → 재정향 시작",
            phase=DeadEndPhase.REVERSE, direction=_bypass_direction(frame),
            entry_yaw=(None if imu is None else imu.yaw_deg),
            perception_bad_since=None,
            reverse_recovery_count=0, reverse_recovery_last=None)
    # 조향은 중립이다. 후방은 카메라 사각이라 곡선 후진은 어디로 가는지
    # 모르는 채로 횡방향까지 움직이는 것이 된다 — 곧게 빼는 것만 한다.
    context = replace(context, reverse_recovery_count=episodes,
                      reverse_recovery_last=now, reverse_recovery_since=now)
    return context, DriveCommand(
        throttle_pct=-params.recovery_reverse_throttle_pct, steering_offset_deg=0.0)


def _perception_lost_step(context: StateMachineContext, params: StateMachineParams,
                          frame: Optional[SegmentationFrame],
                          imu: Optional[ImuReading], now: float, lost: str,
                          trace_exit: dict
                          ) -> Tuple[StateMachineContext, DriveCommand]:
    """인도가 안 보이는 동안의 처리 — 복구 주행 → (막혔으면 후진) → 되짚기 → IDLE.

    '인도가 안 보인다'의 원인을 둘로 나눈다. 종전에는 구분 없이 브레이크였고,
    그래서 벽에 코가 닿으면 1.5초 정지 후 IDLE로 떨어지는 것이 전부였다
    (0804 실내: 재정향 승격에 3초가 필요한데 이 가드가 2초에 끊어, 벽 앞에서는
     재정향에 **구조적으로 도달할 수 없었다**).
      막혀서 안 보인다 → 뒤로 빼면 보인다. 후진 복구.
      벗어나서 안 보인다 → 조향 유지 전진 복구(종전 그대로).
    구분 신호는 정면 blocking 장애물이다. 길을 잃은 것이 아니라 너무 가까이
    붙은 것이므로 대응이 반대여야 한다.
    """
    bad_since = (context.perception_bad_since
                 if context.perception_bad_since is not None else now)
    blocked_close = frame is not None and frame.has_blocking_obstacle()
    grace_sec = (params.recovery_reverse_sec if blocked_close
                 else params.perception_grace_sec)
    if now - bad_since < grace_sec:
        context = replace(context, perception_bad_since=bad_since)
        # frame 자체가 없으면(카메라 장애) 원인을 가릴 수 없다 — 눈먼 후진은
        # 눈먼 전진만큼 나쁘므로 브레이크가 맞다.
        if frame is None:
            return context, BRAKE_COMMAND
        # **회피 기동 중에는 그 기동이 이긴다.** 선회하는 동안 카메라가
        # 장애물·경계 쪽을 향해 인도가 잠깐 안 보이는 것은 회피의 정상적인
        # 부수효과다. 여기서 복구 주행(저속 직진)이나 후진으로 덮어쓰면 이미
        # 시작한 선회가 끝까지 가지 못하고, 빠져나오지 못한 채 같은 장애물에
        # 다시 걸린다 — 이 가드가 핸들러보다 앞이라 조용히 그렇게 된다.
        # 유예 타이머(bad_since)는 그대로 흐르므로 유예가 다하면 아래 IDLE
        # 폴백은 이 상태에도 그대로 걸린다. 정지 대기(HOLD)는 기동이 아니므로
        # 종전대로 복구·후진 판단을 받는다.
        if (context.state is DriveState.AVOID
                and context.avoid_phase is not AvoidPhase.HOLD):
            return _handle_avoid(context, params, frame, imu, now)
        # **디바운스** — 깜빡임 한 프레임으로 후진을 걸지 않는다.
        # 세그 마스크는 프레임 단위로 흔들리고(0804 실기: SLOPE 82ms 플랩,
        # AVOID 2~3초 주기), 후진은 되돌리기 어려운 기동이다. 진입에
        # 디바운스가 없으면 깜빡임 하나가 곧바로 min_sec(1.2초) 강제 후진이
        # 되고, 그 뒤 인계 규칙에 따라 재정향까지 간다 — 실제로 코스트맵이
        # 거의 전부 0인(완전히 트인) 장면에서 후진이 걸렸다.
        # 이미 후진 중이면(래치) 디바운스를 다시 요구하지 않는다.
        if blocked_close and (context.reverse_recovery_since is not None
                              or now - bad_since
                              >= params.recovery_reverse_debounce_sec):
            return _start_reverse_recovery(context, params, frame, imu, now)
        return context, DriveCommand(
            throttle_pct=params.recovery_throttle_pct,
            steering_offset_deg=context.prev_steer_deg)

    # **IDLE 로 떨어지기 직전의 마지막 시도 — 들어온 호를 되짚는 짧은 후진.**
    #
    # 로봇은 직전까지 인도 위에 있었다(그래서 여기까지 왔다). 시간을 되감으면
    # 인도로 돌아간다 — 오도메트리가 없어도 근사할 수 있다: 차량 기구학상
    # **전진 때와 같은 조향각을 유지하며 후진하면 그 호를 되짚는다**(회전중심이
    # 같다). 그래서 여기서는 조향을 0 으로 펴지 않고 prev_steer_deg 를 유지한다 —
    # 막힘 후진(곧게 뺀다)과 목적이 다르기 때문이다. 그쪽은 '벽에서 물러나기'
    # 이고 이쪽은 '왔던 길로 되돌아가기'다.
    #
    # 이 자리가 필요한 이유는 대안이 IDLE(수동 개입만이 출구)이기 때문이다.
    # 소실 원인 중 '인도를 벗어남'은 앞이 트여 있어 blocking 이 안 잡히고, 그래서
    # 위 막힘 후진 경로로 가지 못한 채 유예 1.5초 만에 미션이 끝난다 — 정작
    # 후진으로 가장 잘 풀리는 경우다. 실패해도 잃는 것은 1초·20cm 남짓이고,
    # 그 자리는 방금 지나온 곳이다.
    #
    # 카메라가 죽었으면(frame None) 하지 않는다 — 눈먼 후진은 눈먼 전진만큼
    # 나쁘다. 막힘 후진이 이미 도는 경우(blocked_close)도 여기 오지 않는다.
    if (frame is not None and not blocked_close
            and params.recovery_backtrack_sec > 0.0):
        started = (context.backtrack_since
                   if context.backtrack_since is not None else now)
        if now - started < params.recovery_backtrack_sec:
            return (replace(context, backtrack_since=started,
                            perception_bad_since=bad_since),
                    DriveCommand(
                        throttle_pct=-params.recovery_reverse_throttle_pct,
                        steering_offset_deg=context.prev_steer_deg))

    # 유예도 되짚기도 다했다. 종전에는 여기가 IDLE(수동 개입만이 출구)이었다.
    # **U턴으로 계속 간다** (0806 실기 지시): 인도가 안 보이는 원인이 무엇이든 로봇은
    # 직전까지 인도 위에 있었으므로, 방향을 되돌리면 보이던 쪽이 앞에 온다.
    # frame 이 아예 없으면(카메라 장애) _uturn_escape 가 종전대로 IDLE 로 보낸다 —
    # 눈먼 180° 회전은 눈먼 전진보다 나쁘다. 차도 가드는 U턴 중에도 그대로 돈다
    # (DEAD_END 는 GUARDED_STATES·ESCAPE_SOURCE_STATES 양쪽에 들어 있다).
    return _uturn_escape(context, params, frame, imu, now, lost, **trace_exit)


def _guard_perception(context: StateMachineContext, params: StateMachineParams,
                      frame: Optional[SegmentationFrame], imu: Optional[ImuReading],
                      now: float, trace_exit: dict
                      ) -> Tuple[StateMachineContext, GuardResult]:
    """가드 3 — 인지 두절·경계 소실. **컨텍스트만 갱신하고 넘길 수도 있다.**

    최종 폴백 명령은 브레이크다: 경사였을 가능성을 배제할 수 없고(특히 SLOPE에서
    인지가 끊긴 경우) 무동력(stop)은 후진 활강이 된다 — interfaces.py의 stop/brake.

    유예창(perception_grace_sec): 세그 마스크는 프레임 단위로 깜빡인다. 한 프레임
    오탐이 곧바로 IDLE 폴백이 되면 그때부터 사람이 가서 재시작해야 하므로(자동
    재출발 없음이 명세다) 실외에서 미션이 계속 끊긴다. 유예 중의 명령은 **복구 주행**
    이다 — 저속 + 소실 시점의 조향 유지 (_perception_lost_step).
    """
    if context.state in GUARDED_STATES:
        if context.reverse_recovery_since is not None:
            return context, _reverse_recovery_step(context, params, frame, imu, now)
        lost = ("인지 두절" if frame is None
                else "인도 경계 소실" if not frame.sidewalk_detected else None)
        if lost is not None:
            # **재정향(DEAD_END)은 경계 소실을 이유로 뺏지 않는다.** 막다른 길에서
            # 인도가 화면에 안 보이는 것은 이상이 아니라 그 상태의 전제다 — 벽·구석을
            # 마주보고 있으니 여기 온 것이고, 다시 보이게 만드는 것이 이 상태가 하는
            # 일이다. 그런데 이 가드가 핸들러보다 앞이라, 종전에는 재정향이 시작하는
            # 순간 곧바로 여기로 빠져 **국면 기계가 한 번도 돌지 못했다**: 명령은
            # 조향 0 직진 후진(복구)으로 덮이고, 그 후진이 상한에 닿으면
            # '전 방향 막힘 → 정지 + 수동 대기'로 미션이 끝났다.
            # 2026-08-05 실기에서 재정향 27회 중 11회가 정확히 그 경로였고,
            # 그중 상당수는 TURN_ARC 를 **한 틱도** 밟지 못했다(로그에 REVERSE 만
            # 10~17줄 연속). 실기 지적 '찔끔 돌다가 사방 막혔다 도돌이표'가 이것이다.
            # 눈이 먼 경우(frame 자체가 없음)는 예외다 — 그때는 아래 폴백대로 선다.
            if not (context.state is DriveState.DEAD_END and frame is not None):
                return context, _perception_lost_step(context, params, frame, imu, now,
                                                      lost, trace_exit)
        if (context.perception_bad_since is not None
                or context.backtrack_since is not None):
            # 정상 프레임 → 타이머 해제. 되짚기 중이었다면 그 후진이 성공한 것이다:
            # 이번 틱부터 상태 핸들러가 그대로 주행을 이어받는다(수동 개입 없음).
            context = replace(context, perception_bad_since=None, backtrack_since=None)
        return context, None

    if frame is None:
        # IDLE·PAUSED: 인지 두절 중 파킹 브레이크 유지.
        # ESCAPE만은 자기 타임아웃이 계속 가야 한다 — 이 폴백은 상태 핸들러보다
        # 앞이라, 인지가 끊긴 ESCAPE는 핸들러의 timeout→ESTOP에 영영 닿지 못하고
        # 차도 위(이거나 그 근처)에서 상태 전이 없이 무한 브레이크가 된다. 그동안
        # 서버 투영은 PATROLLING(주행 중)이라 관제도 이상을 모른다. 눈먼 선회를
        # 계속할 수는 없으니 명령은 브레이크로 두되, 이탈에 쓸 시간이 다하면
        # ESTOP으로 승격해 사람을 부른다(수동 해제만이 출구 — 핸들러의 타임아웃
        # 출구와 같은 결말이다).
        if (context.state is DriveState.ESCAPE
                and now - context.entered_at >= params.escape_timeout_sec):
            return context, (_enter(context, DriveState.ESTOP, now,
                                    "차도 이탈 중 인지 두절 지속 → 정지 + 수동 대기"),
                             BRAKE_COMMAND)
        return context, (context, BRAKE_COMMAND)
    return context, None


#: 상태 → 그 상태를 미는 핸들러. 디스패치가 표 하나로 끝나야 상태를 추가할 때
#: 고칠 곳이 한 군데다 (핸들러마다 필요한 인자가 달라 람다로 맞춘다).
_HANDLERS = {
    DriveState.IDLE:
        lambda ctx, params, frame, imu, now, req: _handle_idle(ctx, frame, req, now),
    DriveState.PAUSED:
        lambda ctx, params, frame, imu, now, req: _handle_paused(ctx, frame, req, now),
    DriveState.CRUISE:
        lambda ctx, params, frame, imu, now, req: _handle_cruise(ctx, params, frame,
                                                                 imu, now),
    DriveState.TRACE:
        lambda ctx, params, frame, imu, now, req: _handle_trace(ctx, params, frame,
                                                                imu, req, now),
    DriveState.INSPECT:
        lambda ctx, params, frame, imu, now, req: _handle_inspect(ctx, params, imu,
                                                                  req, now),
    DriveState.AVOID:
        lambda ctx, params, frame, imu, now, req: _handle_avoid(ctx, params, frame,
                                                                imu, now),
    DriveState.ESCAPE:
        lambda ctx, params, frame, imu, now, req: _handle_escape(ctx, params, frame, now),
    DriveState.SLOPE:
        lambda ctx, params, frame, imu, now, req: _handle_slope(ctx, params, frame,
                                                                imu, now),
    DriveState.TURN:
        lambda ctx, params, frame, imu, now, req: _handle_turn(ctx, params, frame,
                                                               imu, now),
    DriveState.DEAD_END:
        lambda ctx, params, frame, imu, now, req: _handle_dead_end(ctx, params, frame,
                                                                   imu, now),
}


def _decide(context: StateMachineContext, params: StateMachineParams,
            frame: Optional[SegmentationFrame], imu: Optional[ImuReading],
            now: float, requests: ExternalRequests
            ) -> Tuple[StateMachineContext, DriveCommand]:
    """공통 가드 → 상태 디스패치. step()의 본체 (슬루율 제한 전 단계).

    **가드의 순서가 곧 안전 우선순위다** (명세 하드 제약). 각 단계의 근거는 그 함수의
    독스트링에 있고, 여기서는 순서만 읽으면 된다:
      0. ESTOP 래치      — 수동 해제 외 어떤 입력도 무시한다
      1. _guard_estop            — 수동 정지 / 코리도가 차도로 덮임 → 즉시 ESTOP
      2. _guard_roadway_escape   — 차도 진입 → 선회 이탈 (정지가 아니다)
      2.5 _guard_remote_control  — 원격 취소·일시정지
      3. _guard_perception       — 인지 두절·경계 소실 → 복구 주행 → 되짚기 → IDLE
    """
    # 가드 0: ESTOP 래치
    if context.state is DriveState.ESTOP:
        return _handle_estop(context, requests, now)

    trace_exit = _trace_exit_fields(context, now)
    context = _debounce_step(context, params, frame, now)

    taken = _guard_estop(context, frame, now, requests, trace_exit)
    if taken is not None:
        return taken
    taken = _guard_roadway_escape(context, params, frame, now, trace_exit)
    if taken is not None:
        return taken
    taken = _guard_remote_control(context, now, requests, trace_exit)
    if taken is not None:
        return taken
    context, taken = _guard_perception(context, params, frame, imu, now, trace_exit)
    if taken is not None:
        return taken

    handler = _HANDLERS.get(context.state)
    if handler is None:
        return context, STOP_COMMAND  # 도달 불가 — 방어적 정지
    return handler(context, params, frame, imu, now, requests)


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
