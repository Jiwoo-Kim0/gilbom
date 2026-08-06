"""상태별 핸들러 — 각 상태가 한 틱에 무엇을 하는가.

각 핸들러는 (새 컨텍스트, 주행 명령)을 돌려주는 순수 함수다. 상태 안의 진행 단계는
하위 국면(AvoidPhase·DeadEndPhase)으로 표현하고 **상태 전이로 만들지 않는다** —
전이는 on_event 로 밖에 알려지는 사건이라, 한 기동의 내부 진행까지 전이로 만들면
관제 로그가 국면 변경으로 뒤덮인다.
"""

import math
from dataclasses import replace
from typing import Optional, Tuple

from driving.states import (BRAKE_COMMAND, STOP_COMMAND, AvoidPhase, DeadEndPhase,
                            DriveCommand, DriveState)
from hardware.interfaces import ImuReading
from perception.interfaces import SegmentationFrame

from driving.state_machine.data import (ExternalRequests, GuardResult,
                                        StateMachineContext, StateMachineParams,
                                        _enter)
from driving.state_machine.steering import (
    _bypass_command, _bypass_direction, _dead_end_command, _escape_steer_deg,
    _resolve_direction, _steer_command, _turn_steer_deg)



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
    """점자블록 발견/소실 판정의 **비대칭** 디바운스 → (확정 값, 대기 시작 시각).

    확정 값과 다른 raw가 그 방향의 창만큼 유지돼야 뒤집힌다.
      발견(→True)  inspect_confirm_sec — 짧게. 한 프레임 오탐에 주행 상태가
                   바뀌는 것만 막으면 된다.
      소실(→False) inspect_release_sec — 길게. **세그의 점자블록 재현율이 낮다**는
                   실측(2026-08-05: conf 0.25 운영점에서 8장면 중 2장면, 같은 장면이
                   5초 사이에 0.374 → 0.000으로 사라짐) 때문이다. 같은 창을 쓰면
                   블록 위를 달리는 동안에도 추종이 계속 끊겼다 붙고, 그때마다
                   TRACE→CRUISE→TRACE 전이가 나며 **검사 추론이 멈춘다**(미션 본체가
                   꺼진다). 놓친 것과 없어진 것은 다르고, 이 창이 그 차이를 흡수한다.

    긴 소실 창의 대가는 작다: 블록이 안 보이는 틱에는 근접도가 0이라 추종 혼합
    가중치도 0이 되어(_approach_weight) 조향은 그대로 인도 중심을 따른다.
    남는 것은 'TRACE 상태를 유지해 하향 카메라 탐지를 계속 돌린다' 뿐이다.
    """
    if raw == context.braille_stable:
        return context.braille_stable, None
    window = params.inspect_confirm_sec if raw else params.inspect_release_sec
    since = (context.braille_pending_since
             if context.braille_pending_since is not None else now)
    if now - since >= window:
        return raw, None
    return context.braille_stable, since


def _hold_command(params: StateMachineParams, imu: Optional[ImuReading]) -> DriveCommand:
    """**정지 유지** 명령 — 평지는 숏브레이크, 내리막은 경사 비례 역스로틀(위치 고수).

    0806 실기 지시: "내리막길에서는 적절한 역주행을 통해 현재 위치를 고수할 줄 알아야
    보도블록 탐지에 도움이 될 듯 하다."

    종전에 낼 수 있는 정지 명령은 둘뿐이었다 — 무동력(stop)과 숏브레이크(brake). 특히
    INSPECT(파손 정밀 판정 정차)가 **무동력**이라, 판정용 정지 화면을 찍는 그 0.7초 동안
    내리막에서는 로봇이 굴러갔다: 정차해서 얻으려던 것(모션 블러 없는 화면)을 정차가
    지키지 못했다. 숏브레이크도 완전한 제동은 아니다.
    저듀티 **역전** PWM 은 오프 구간이 모터 브레이크로 작동하면서(내리막 스로틀 하한과
    같은 원리) 온 구간이 중력 성분을 밀어 올린다 — 그래서 브레이크보다 강하다.

    개루프라 노면·경사 판정이 틀리면 이 값만큼 **후진한다**. 그래서 이득을 주행 감속
    이득보다 작게, 상한을 따로 둔다 (config slope.downhill_hold_*).
    무한정 서 있는 상태(IDLE·PAUSED·ESTOP)에는 쓰지 않는다 — 몇 분씩 역스로틀을 물리는
    것은 구동계에 나쁘고, 그쪽은 사람이 옆에 있는 상태다.
    """
    if imu is None or imu.pitch_deg > -params.slope_downhill_hold_pitch_deg:
        return BRAKE_COMMAND
    throttle = min(params.slope_downhill_hold_max_pct,
                   params.slope_downhill_hold_gain_pct_per_deg * abs(imu.pitch_deg))
    return DriveCommand(throttle_pct=-throttle, steering_offset_deg=0.0)


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


def _blend_target_ratio(context: StateMachineContext, params: StateMachineParams,
                        frame: SegmentationFrame) -> Tuple[float, float]:
    """(접근 가중치, 목표 방향 비율) — 추종의 목표 산식. TRACE 와 회피 복귀가 공유한다.

    블록이 확정 상태가 아니면 가중치 0 = 인도 중심 그대로다. 두 곳이 같은 식을 써야
    '회피가 끝난 자리'와 '추종이 이어받는 자리'가 어긋나지 않는다 — 종전에는 복귀가
    인도 중심만 봐서, 블록을 옆에 두고 중심으로 돌아온 뒤 다시 블록 쪽으로 꺾었다.
    """
    if not context.braille_stable:
        return 0.0, frame.sidewalk_center_offset_ratio
    weight = _approach_weight(params, frame.braille_proximity)
    return weight, ((1.0 - weight) * frame.sidewalk_center_offset_ratio
                    + weight * frame.braille_center_offset_ratio)


def _braille_side(context: StateMachineContext, frame: SegmentationFrame) -> int:
    """점자블록이 화면의 어느 쪽에 있는가 (-1=좌, +1=우, 0=중앙/없음).

    회피 방향의 **선호**로만 쓴다 (_bypass_direction 의 prefer). 정중앙(±0.05)이면 0을
    돌려 종전대로 코스트맵이 고르게 둔다 — 어느 쪽으로 비껴가도 블록과의 거리가 같다.
    """
    if not context.braille_stable:
        return 0
    ratio = frame.braille_center_offset_ratio
    if abs(ratio) < 0.05:
        return 0
    return -1 if ratio < 0.0 else 1


def _bypass_throttle_pct(params: StateMachineParams,
                         imu: Optional[ImuReading]) -> float:
    """회피 선회 스로틀 — 경사에서는 **같은 산식으로 보정한다** (TRACE 와 같은 처리).

    평지 기준값(avoid.bypass_throttle_pct)을 경사에서도 그대로 쓰면 오르막에서
    **순찰(100%)보다 느리게** 비껴가게 된다 — 등판 토크가 가장 필요한 순간에 출력을
    줄이는 것이라, 조향이 꺾여 구름저항까지 커진 상태에서 그 자리에 선다. 실기에서
    '오르막 장애물에서 멈춘다'로 보이는 것의 절반이 여기다(나머지 절반은 브레이크
    대기 — _slope_holds_with_brake).
    기준은 SLOPE 진입 임계와 **같은 값**이다: '경사인가'의 정의가 세 곳에서 갈리면
    같은 노면에서 상태에 따라 다른 속도가 나온다.
    """
    base = params.avoid_bypass_throttle_pct
    if imu is None or abs(imu.pitch_deg) < params.slope_enter_pitch_deg:
        return base
    return _slope_throttle_pct(params, imu.pitch_deg, base_pct=base)


def _enter_bypass(context: StateMachineContext, params: StateMachineParams,
                  frame: SegmentationFrame, imu: Optional[ImuReading],
                  now: float, reason: str
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
    # 반복 회피 카운트 — 창 안에 연달아 들어오면 누적, 창을 넘겨 조용했으면 리셋.
    # 이 값이 '앞으로 못 나가고 있다'의 유일한 증거다: 인지가 매번 '한쪽은 열렸다'고
    # 해도 실제로 나아갔다면 회피가 이렇게 촘촘히 반복될 수 없다.
    consecutive = (context.avoid_thrash_count + 1
                   if (context.avoid_thrash_last is not None
                       and now - context.avoid_thrash_last <= params.avoid_thrash_window_sec)
                   else 1)

    if consecutive >= params.avoid_thrash_limit:
        # 인지 판정과 무관하게 재정향으로 승격한다. all_directions_blocked를 기다리면
        # 0804 실내처럼 90초 동안 14회를 치고도 영영 승격되지 않는다.
        # 카운터는 여기서 리셋한다 — 재정향이 끝나고 나서 다시 세기 시작해야 한다.
        #
        # **첫 국면은 선회 전진이다 — 후진이 아니다.** 여기까지 온 이유는 '못 지나갔다'
        # 이지 '벽에 코가 닿았다'가 아니다(닿았으면 전 방향 통행 불가 쪽으로 왔을
        # 것이고, 그 경로는 아래 _avoid_hold 처럼 여전히 후진으로 시작한다).
        # 앞이 열려 있는 자리에서 먼저 뒤로 빼면 번 것 없이 인도 뒤쪽으로 밀려나기만
        # 하고, 그 후진이 시도 시간을 다 먹어 U턴까지 간다 (0805 실기).
        attempt = _next_attempt(context, params, now)
        return _enter_dead_end(
            context, params, now,
            f"{reason} → 회피 {consecutive}회 반복(진행 없음) → 재정향 시작",
            phase=DeadEndPhase.TURN_ARC, attempt=attempt,
            direction=_attempt_direction(frame, attempt, context.dead_end_direction),
            avoid_thrash_count=0, avoid_thrash_last=None)

    if frame.all_directions_blocked:
        return (_enter(context, DriveState.AVOID, now,
                       f"{reason} → 좌우 모두 막힘, 정지 후 재확인",
                       avoid_brake=False, avoid_phase=AvoidPhase.HOLD,
                       avoid_phase_since=now,
                       avoid_thrash_count=consecutive, avoid_thrash_last=now),
                STOP_COMMAND)

    # 연속 회피(창 안 반복)일 때만 직전 방향을 넘긴다 — 한참 만에 다시 만난 장애물은
    # 새 사건이므로 지금 장면대로 고르는 것이 맞다.
    # 블록이 확정 상태면 **블록이 있는 쪽**을 선호한다 (근거는 _bypass_direction prefer).
    direction = _bypass_direction(
        frame, previous=(context.avoid_direction if consecutive > 1 else 0),
        prefer=_braille_side(context, frame))
    # 추종 중에 들어온 회피라면 그 추종 세션의 시작 시각을 들고 간다 — 회피가 끝났을 때
    # 블록이 아직 보이면 CRUISE 가 아니라 TRACE 로 직접 돌아가는데(_avoid_exit),
    # 그때 세션 시각을 새로 찍으면 추종 상한이 회피마다 리셋돼 무의미해진다.
    return (_enter(context, DriveState.AVOID, now,
                   f"{reason} → 정지 없이 {'좌' if direction < 0 else '우'} 회피"
                   f" ({consecutive}회째)",
                   avoid_brake=False, avoid_phase=AvoidPhase.SWERVE,
                   avoid_phase_since=now, avoid_direction=direction,
                   avoid_progress_at=now,
                   avoid_trace_started_at=(context.trace_started_at
                                           if context.state is DriveState.TRACE
                                           else None),
                   avoid_thrash_count=consecutive, avoid_thrash_last=now),
            _bypass_command(params, direction, _bypass_throttle_pct(params, imu)))


def _enter_slope(context: StateMachineContext, params: StateMachineParams,
                 frame: SegmentationFrame, imu: Optional[ImuReading], now: float,
                 reason: str) -> GuardResult:
    """경사 진입 게이트 — IMU 가 있고 pitch 가 임계를 넘을 때만 (없으면 이 경로가 죽는다).

    **경사 판정이 장애물보다 앞이다**: 경사에서는 장애물을 만나도 급하면 브레이크 정지가
    맞고(_handle_slope 가 완만·급경사를 갈라 처리한다), 순서가 뒤바뀌면 경사에 올라선
    첫 틱에 저속 횡방향 회피가 시작돼 밀림·등판 토크 양쪽으로 나쁜 기동이 된다.
    장애물이 사라지지 않으면 다음 틱에 SLOPE 가 곧바로 처리하므로 지연은 한 틱뿐이다.
    """
    if imu is None or abs(imu.pitch_deg) < params.slope_enter_pitch_deg:
        return None
    # 진입 첫 틱의 명령. 장애물이 있어도 **브레이크는 내리막에서만** 건다 —
    # 다음 틱에 어차피 _handle_slope 가 회피 선회로 보낸다(_slope_holds_with_brake).
    if frame.has_blocking_obstacle() and _slope_holds_with_brake(params, imu):
        return (_enter(context, DriveState.SLOPE, now,
                       f"{reason} (pitch {imu.pitch_deg:+.1f}°)"), BRAKE_COMMAND)
    # **무동력 정지(STOP_COMMAND)로 들어가지 않는다.** 대부분의 전이는 한 틱을 정지로
    # 흘려보내도 무해하지만, 경사 진입은 그 한 틱이 곧 중력이다:
    #     오르막 10° → 50ms 무동력에 0.085 m/s 감속 (최고속 0.32의 27%)
    #     오르막 15° → 0.127 m/s (40%)
    # 등판 중에 그만큼을 잃고 다음 틱에 다시 붙이는 것은 이 구동계에 여유가 없다
    # (오르막 스로틀 이득이 상한 100 에 물려 사실상 무효인 것이 그 여유의 크기다).
    # 그래서 진입 틱부터 경사 보정 스로틀로 나가고, 조향은 직전 값을 유지한다 —
    # SLOPE→TRACE 인계가 쓰는 것과 같은 처리다.
    return (_enter(context, DriveState.SLOPE, now,
                   f"{reason} (pitch {imu.pitch_deg:+.1f}°)"),
            DriveCommand(throttle_pct=_slope_throttle_pct(params, imu.pitch_deg),
                         steering_offset_deg=context.prev_steer_deg))


def _enter_avoid_hold(context: StateMachineContext, params: StateMachineParams,
                      imu: Optional[ImuReading], now: float, reason: str, *,
                      brake: bool = False) -> Tuple[StateMachineContext, DriveCommand]:
    """비껴갈 방향 자체가 없다 → 정지 대기(HOLD).

    회피가 아니라 정지가 맞는 유일한 경우다. 재확인 주기마다 다시 보고, 막힘이
    유지되면 막다른 길로 보고 재정향으로 승격된다(_avoid_hold).
    브레이크 대기(경사발)는 내리막에서 **위치 고수 역스로틀**이 된다 (_hold_command).
    """
    return (_enter(context, DriveState.AVOID, now, reason, avoid_brake=brake),
            _hold_command(params, imu) if brake else STOP_COMMAND)


def _cruise_turn_gate(context: StateMachineContext, params: StateMachineParams,
                      target_ratio: float, imu: Optional[ImuReading], now: float
                      ) -> Tuple[StateMachineContext, GuardResult]:
    """회전 필요 판단 — **목표 오차**가 상한을 넘어 포화된 채 확정 시간 지속되는가.

    코스트맵이 고른 슬롯이 아니라 목표 오차로 본다. 둘은 단위만 같고 다른 양이다:
    목표 오차는 '얼마나 어긋났나'(연속값, ±1까지)이고 슬롯은 '어디로 조향하나'(이산값)다.
    슬롯으로 판정하면 회전이 사실상 안 걸린다: slot_count=15에서 슬롯 중심의 최대
    크기가 0.933이라 임계를 넘는 슬롯이 최외곽 2개뿐이고, 그 최외곽은 화면 좌·우 끝
    = 차도(∞)나 미분류일 확률이 가장 높아 애초에 후보에서 빠진다. 그 결과 90° 코너에서
    CRUISE 상한(좌 15/우 12)으로 버티다 인도가 화면을 벗어나 경계 소실 IDLE로 끝났다.
    클램프 **전** 수요를 쓰는 이유도 같다: 클램프 후에는 상한에 붙어 있다는 사실이
    지워져 '상한으로 부족한 굽이'와 '상한 근처 추종'을 구분 못 한다.
    ※ IMU 게이트가 없다 — 회전 완료의 본 판정은 시각 재정렬이고 yaw는 교차 검증이라,
      yaw가 없으면 그 교차 검증만 시간으로 바꾼다 (_handle_turn).
    """
    demand_deg = target_ratio * params.cruise_steer_gain_deg
    if abs(demand_deg) < params.turn_enter_steer_demand_deg:
        if context.turn_pending_since is not None:
            context = replace(context, turn_pending_since=None)  # 포화 해소 → 타이머 해제
        return context, None

    pending_since = (context.turn_pending_since
                     if context.turn_pending_since is not None else now)
    if now - pending_since < params.turn_confirm_sec:
        return replace(context, turn_pending_since=pending_since), None

    direction = -1 if demand_deg < 0 else 1
    return context, (
        _enter(context, DriveState.TURN, now,
               f"조향 수요 포화 지속 → {'좌' if direction < 0 else '우'}회전",
               turn_direction=direction,
               turn_entry_yaw_deg=(imu.yaw_deg if imu is not None else None)),
        DriveCommand(throttle_pct=params.turn_throttle_pct,
                     steering_offset_deg=_turn_steer_deg(params, direction)))


def _handle_cruise(context: StateMachineContext, params: StateMachineParams,
                   frame: SegmentationFrame, imu: Optional[ImuReading], now: float
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
    slope = _enter_slope(context, params, frame, imu, now, "경사 감지")
    if slope is not None:
        return slope

    # 장애물: 정지하지 않고 즉시 좌/우로 비껴간다 (명세 '좌,우로 회피').
    # 정지→재확인은 움직이는 장애물이 지나가길 기다리는 정책이었지만, 인도에서
    # blocking의 대부분은 지나가지 않는 고정물(볼라드·주차·적재물)이고 사람은 오히려
    # 로봇을 피해 준다 — 3초씩 서서 기다리는 값이 통행 자체보다 컸다.
    if frame.has_blocking_obstacle():
        return _enter_bypass(context, params, frame, imu, now, "장애물 인식")

    # 점자블록 추종 게이트 — **블록이 보이면 순찰을 접고 그쪽으로 간다(최우선).**
    # 남은 조건은 둘뿐이고 둘 다 미션 조건이 아니라 고착·오탐 방지 장치다:
    #   · braille_stable  — 발견 디바운스. 한 프레임 오탐에 주행 상태가 바뀌는 것을 막는다
    #   · _trace_cooldown_over — 직전 추종이 **타임아웃으로** 끝났을 때만 닫혀 있다
    # 근접도 하한과 중앙 정렬 조건은 **없다** (근거는 이 함수 독스트링).
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
        return _enter_avoid_hold(context, params, imu, now,
                                 "코스트맵: 전 방향 통행 불가 → 정지 후 재확인")

    context, turning = _cruise_turn_gate(context, params, target_ratio, imu, now)
    if turning is not None:
        return turning

    # 조향은 코스트맵이 고른 방향으로 낸다 — 위 회전 판정은 목표 오차 전용이다.
    # 코스트맵이 없는 프레임에서는 둘이 같은 값이 된다.
    return context, _steer_command(params, resolved_ratio, params.cruise_throttle_pct)


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

    # 장애물은 CRUISE와 같은 우선순위·같은 처리다 (근거는 _handle_cruise 주석) —
    # 사람·볼라드를 뚫고 블록에 갈 수는 없으므로 이것만은 블록보다 위에 남는다.
    if frame.has_blocking_obstacle():
        return _enter_bypass(context, params, frame, imu, now, "추종 중 장애물 인식")

    # **경사는 추종을 그만둘 이유가 아니다.** 종전에는 |pitch| 5°만 넘으면 SLOPE 로
    # 나갔고, SLOPE 는 '점자블록 접근·검사를 하지 않는' 상태다 — 연석 경사로·건물
    # 진입로가 인도에 널려 있으므로(0805 실기: 추종 중 경사 진입이 반복) 블록을 잡아
    # 놓고도 경사 한 조각에 놓아 버렸다. 이 상태가 미션 본체이고 경사는 스로틀 문제다.
    # 서야 하는 급경사(명세의 '하강 안전 정지')만 SLOPE 에 넘긴다 — 그건 스로틀
    # 보정이 아니라 정지 판단이라 블록보다 위다.
    if _slope_must_stop(params, imu):
        return _enter_slope(context, params, frame, imu, now, "추종 중 하강 안전 정지")

    weight, target_ratio = _blend_target_ratio(context, params, frame)
    context, resolved_ratio = _resolve_direction(context, params, frame, target_ratio)
    if resolved_ratio is None:
        return _enter_avoid_hold(context, params, imu, now,
                                 "추종 중 전 방향 통행 불가 → 정지 후 재확인")

    # 접근 구간(weight<1 = 근접도 < enter_proximity)만 살짝 낮춘다. 도착 후 추종은
    # 종전 그대로다 — 감속의 근거가 없다는 판단(위 docstring)은 그 구간 이야기다.
    # 새 임계값을 만들지 않고 조향 혼합이 쓰는 weight를 그대로 재사용한다:
    # '블록 쪽으로 가는 중'의 정의가 두 곳에서 갈리지 않게.
    throttle = (params.trace_throttle_pct if weight >= 1.0
                else params.trace_approach_throttle_pct)
    # 경사 보정은 SLOPE 로 나가지 않고 여기서 그대로 한다 — 등판 토크와 하강 과속은
    # 추종 중에도 똑같이 필요하고, 그것 때문에 블록을 놓을 이유는 없다.
    # 기준은 SLOPE 진입 임계와 **같은 값**이다: '경사인가'의 정의가 두 곳에서 갈리면
    # 같은 노면에서 상태에 따라 다른 속도가 나온다.
    if imu is not None and abs(imu.pitch_deg) >= params.slope_enter_pitch_deg:
        throttle = _slope_throttle_pct(params, imu.pitch_deg, base_pct=throttle)
    return context, _steer_command(params, resolved_ratio, throttle)


def _handle_inspect(context: StateMachineContext, params: StateMachineParams,
                    imu: Optional[ImuReading], requests: ExternalRequests, now: float
                    ) -> Tuple[StateMachineContext, DriveCommand]:
    """INSPECT: 정차 유지 → 정밀 판정 완료 통지(또는 타임아웃) → **추종 재개** + 정차 쿨다운.

    TRACE에서 탐지 모델이 파손 후보를 본 순간에만 들어온다 — 정차 비용을 내는 이유는
    주행 중 판정이 모션 블러로 defect_partial(≈55px)부터 무너지기 때문이다.
    판정 실행(I/O)은 main 루프 담당이다: 루프가 이 상태를 보면 settle_sec 만큼 감속을
    기다린 뒤 정지 화면을 캡처해 판정을 돌리고 notify_inspection_done()으로 통지한다.
    타임아웃은 통지 유실 시 무한 정지를 막는 안전판이다.

    **정차 명령은 내리막에서 위치 고수 역스로틀이다** (_hold_command). 종전에는 무동력
    정지였고, 그래서 내리막에서는 settle_sec 동안 로봇이 굴러가 모션 블러로
    defect_partial(≈55px)부터 무너졌다 — 정차 비용을 내고 정차의 목적을 잃었다 (0806).

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
    # 정차 유지 — **내리막에서는 무동력이 아니라 위치 고수 역스로틀**이다 (_hold_command).
    # 이 상태의 존재 이유가 '정지 화면'인데, 종전 무동력 정지는 경사에서 그 전제를
    # 지키지 못했다: settle_sec(0.7초) 동안 굴러가면 모션 블러로 defect_partial(≈55px)
    # 부터 무너진다 — 정차 비용을 내고 아무것도 못 얻는다 (0806 실기).
    return context, _hold_command(params, imu)


def _swerve(context: StateMachineContext, params: StateMachineParams,
            frame: SegmentationFrame, imu: Optional[ImuReading], now: float
            ) -> Tuple[StateMachineContext, DriveCommand]:
    """AVOID 안에서 SWERVE 국면을 (재)시작한다 — 방향을 매번 다시 고른다.

    상태 전이가 아니라 국면 변경이므로 _enter를 쓰지 않는다(entered_at·on_event 불변).
    방향을 캐시하지 않는 이유는 회피 도중 장면이 바뀌기 때문이다 — 처음 고른 쪽이
    막히면 그때 열린 쪽으로 다시 고르는 것이 맞다. 블록이 확정 상태면 그 쪽을 선호한다.

    **avoid_progress_at 을 갱신하지 않는다.** 이것은 '진행'이 아니라 되돌아온 것이다 —
    여기서 진행으로 세면 SWERVE(상한)→PASS(1틱)→SWERVE 루프가 영원히 '진행 중'이 되어
    정체 상한(avoid_timeout_sec)이 한 번도 안 걸린다.
    """
    direction = _bypass_direction(frame, prefer=_braille_side(context, frame))
    return (replace(context, avoid_phase=AvoidPhase.SWERVE, avoid_phase_since=now,
                    avoid_direction=direction),
            _bypass_command(params, direction, _bypass_throttle_pct(params, imu)))


def _avoid_exit(context: StateMachineContext, params: StateMachineParams,
                now: float, reason: str) -> Tuple[StateMachineContext, DriveCommand]:
    """회피 종료 — 블록이 아직 확정 상태면 순찰이 아니라 **추종으로 직접** 돌아간다.

    0806 실기: "주행 중 점자 블록이 발견되면 최우선적으로 점자블록에 도달하는 경로로
    가야 하는데, 중간에 장애물 이슈로 조향 틀면서 비틀려 버렸다."
    종전에는 회피가 끝나면 무조건 CRUISE 였다. 다음 틱에 CRUISE 의 블록 게이트가 다시
    TRACE 로 보내 주기는 하지만, 그 사이 한 틱을 인도 중심 추종 명령으로 쓰고
    **추종 세션 시각(trace_started_at)이 새로 찍혀** 추종 상한이 회피마다 리셋됐다.
    여기서 직접 넘기면 그 두 가지가 없다 — 회피는 추종을 잠깐 미루는 것이지 끝내는
    것이 아니다.
    추종 재진입 쿨다운(타임아웃으로 끝난 추종)은 그대로 존중한다 — 그 창은 '유도선
    오탐에 영구히 갇히지 않는다'는 상한을 실효화하는 유일한 장치다.
    """
    if context.braille_stable and _trace_cooldown_over(context, params, now):
        return (_enter(context, DriveState.TRACE, now, f"{reason} → 블록 추종 복귀",
                       trace_started_at=(context.avoid_trace_started_at
                                         if context.avoid_trace_started_at is not None
                                         else now)),
                STOP_COMMAND)
    return _enter(context, DriveState.CRUISE, now, reason), STOP_COMMAND


def _avoid_hold(context: StateMachineContext, params: StateMachineParams,
                frame: SegmentationFrame, imu: Optional[ImuReading], now: float
                ) -> Tuple[StateMachineContext, DriveCommand]:
    """정지 대기 — 재확인 주기마다 '풀렸나 / 막다른 길인가 / 비껴갈 수 있나'를 본다.

    여기로 들어오는 경우는 둘뿐이다: 급경사발(avoid_brake)과 전 방향 통행 불가.
    급경사발은 회차로 승격하지도, 선회하지도 않는다 — 경사 위 저속 횡방향 기동이
    밀림·등판 토크 양쪽으로 나쁘다는 것이 브레이크 대기의 근거이기 때문이다.
    """
    if now - context.avoid_phase_since < params.avoid_recheck_sec:
        return context, (_hold_command(params, imu) if context.avoid_brake
                         else STOP_COMMAND)

    blocked = _blocked_everywhere(frame)
    if not frame.has_blocking_obstacle() and not blocked:
        context = replace(context, avoid_brake=False)
        return _avoid_exit(context, params, now, "재확인: 장애물 소멸 → 직진 복귀")
    if blocked and not context.avoid_brake:
        # IMU를 요구하지 않는다 — 완료가 시각이라 센서 없이도 성립한다.
        # 방향은 여기서 한 번만 고른다(코스트맵 반쪽 점수 → 이득이 큰 쪽,
        # 동점이면 좌 — 단 그 근거는 실측이 뒤집었다, _bypass_direction ⚠️ 참조).
        # 첫 국면은 **후진**이다 — 벽에 붙은 채로는 선회할 공간이 없다.
        attempt = _next_attempt(context, params, now)
        return _enter_dead_end(
            context, params, now,
            "재확인: 전 방향 통행 불가 유지(막다른 길) → 재정향 시작",
            phase=DeadEndPhase.REVERSE, attempt=attempt,
            direction=_attempt_direction(frame, attempt, context.dead_end_direction),
            entry_yaw=(None if imu is None else imu.yaw_deg))
    if not blocked and not context.avoid_brake:
        return _swerve(context, params, frame, imu, now)
    return context, (_hold_command(params, imu) if context.avoid_brake
                     else STOP_COMMAND)


def _avoid_gave_up(context: StateMachineContext, params: StateMachineParams,
                   now: float) -> GuardResult:
    """**회피의 두 상한** — 정체 상한과 절대 상한. 걸리면 **판단을 다시 한다**.

    ※ 0806 실기 '6초는 좀 짧다, 조건을 더 걸어야 한다' 반영. 종전에는 진입 시각 기준
      단일 상한(6초)이었고, 그래서 '오래 걸린 정상 회피'와 '제자리 맴돌기'를 구분하지
      못했다 — 0805 세션 7에서 회피 52회 중 21회가 이 상한에 걸려 후진 재정향으로 갔다.
      지금은 둘로 나눈다:
        정체 상한(avoid_timeout_sec)  — 기준점이 **마지막으로 국면이 앞으로 넘어간
                                        시각**(avoid_progress_at)이다. 국면을 밟아
                                        나가는 중이면 계속 연장된다.
        절대 상한(avoid_hard_timeout_sec) — 진입 시각 기준. 무한 연장을 막는다.
    절대 상한이 반드시 필요한 이유는 아래 루프 때문이다.

    국면 상한만으로는 이 상태를 못 빠져나온다: 장애물이 계속 잡히면 PASS·RETURN 이
    첫 틱에 _swerve 로 되돌아가고, 그 재시작은 상태 전이가 아니라 국면 변경이라 반복
    회피 카운터(_enter_bypass)에도 안 잡힌다 — 즉 SWERVE(상한) → PASS(1틱) → SWERVE
    … 가 끝없이 돈다. 실제로 mock 시나리오 obstacle_persists 에서 10초 내내 AVOID
    였고, 실기의 '회피가 너무 오래 끈다'가 이것이다. 조향이 꺾인 채 그 시간을 달리므로
    인도를 벗어나는 것으로 끝난다.
    그 루프에서 SWERVE 재시작은 진행으로 세지 않으므로(_swerve) 정체 상한이 정상적으로
    걸린다 — 절대 상한은 그 판단이 틀렸을 때의 두 번째 안전판이다.

    **나가는 곳은 CRUISE 다 — 후진 재정향이 아니다** (2026-08-05 실기 반영).
    종전에는 여기서 곧바로 DEAD_END(후진)로 승격했는데, 그 결과가 실측으로 나쁘다:
    같은 날 AVOID 20회 중 13회가 6초 상한에 걸려 승격됐고 그 후진 8회가 '전 방향 막힘
    → 정지 + 수동 대기'로 미션을 끝냈다. 상한에 걸렸다는 것은 '못 지나갔다'는 사실
    하나이고, 그것이 곧 '뒤로 빼야 한다'는 뜻은 아니다 — 왕복의 원인이 유령 장애물이면
    후진은 아무것도 풀지 못한 채 로봇을 인도 뒤쪽으로 밀어낼 뿐이다(그 유령의 정체는
    perception/segmentation.py BlankFrameGate 에 있다).

    그래서 조향을 펴고 CRUISE 로 돌려보내 **지금 장면으로 다시 판단하게** 한다.
    장애물이 진짜면 CRUISE 의 장애물 분기가 그 자리에서 다시 회피로 보내므로 고착은
    없고, 재정향으로 가는 길도 그대로 남아 있다 — 그 경로가 반복 회피 카운터
    (avoid_thrash_limit)다. 카운터를 **여기서 리셋하지 않는 것**이 그 승격의 조건이다:
    상한까지 쓴 회피도 한 번으로 세어져야 창 안에서 누적된다.
    """
    since_progress = now - (context.avoid_progress_at
                            if context.avoid_progress_at is not None
                            else context.entered_at)
    stalled = since_progress >= params.avoid_timeout_sec
    exhausted = now - context.entered_at >= params.avoid_hard_timeout_sec
    if not (stalled or exhausted):
        return None
    why = (f"진행 없이 {since_progress:.0f}초" if stalled
           else f"절대 상한 {params.avoid_hard_timeout_sec:.0f}초")
    return _avoid_exit(context, params, now,
                       f"회피 {why}(못 지나감) → 조향 펴고 경로 재판단")


def _handle_avoid(context: StateMachineContext, params: StateMachineParams,
                  frame: SegmentationFrame, imu: Optional[ImuReading], now: float
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
             유지되면 막다른 길로 보고 DEAD_END 재정향으로 승격한다(**IMU 불필요** —
             완료가 시각이다. 이 괄호는 0804 개편 전 문구가 남아 있던 자리다).
             홀드 명령은 avoid_brake면
             매 틱 브레이크 유지 — 전이 틱 1회 브레이크로는 다음 틱부터 경사에서 밀린다.
      SWERVE 좌/우 중 경로 이득이 큰 쪽(_bypass_direction — 블록이 확정 상태면 그 쪽을
             선호한다)으로 조향해 벗어난다. **swerve_min_sec 은 무조건 밀고 나간다**:
             종전에는 종료 조건이 '장애물이 경로에서 빠짐' 단독이라 **한 틱(0.05초)**
             만에 끝날 수 있었고, 조향이 슬루율 때문에 목표에 도착하지도 않은 채
             통과 국면으로 넘어갔다 — 0806 실기 '조향 후 이동을 찔끔찔끔 게이처럼
             해서, 충분히 진행하면 피할 각을 헛짓하다 오히려 걸린다'가 이것이다.
             그 뒤에는 장애물이 경로에서 빠지면 통과 국면으로 — yaw 이탈량을 대신하는
             시각 판정이고, 실제로 재려던 것('충분히 비켜났나')에 더 가깝다.
             빠지지 않아도 phase_timeout_sec에 통과로 넘어간다: 계속 잡히는 장애물을
             이유로 선회를 무한정 이어가면 인도를 벗어난다.
      PASS   조향 중립으로 pass_sec 직진 — 전방 카메라는 옆의 장애물을 못 보므로
             통과 판정은 시간뿐이다.
      RETURN 반대 조향으로 되돌아오다 |목표 오차| ≤ return_center_abs면 완료.
             **목표는 블록이 확정 상태면 추종과 같은 혼합 목표다**(_blend_target_ratio) —
             인도 중심만 보면 블록을 옆에 둔 채 중심으로 돌아와 놓고 다음 틱에 다시
             블록 쪽으로 꺾는다. 남은 횡오차 수렴은 추종/중심 추종이 담당한다.
    PASS·RETURN 중 새 blocking 장애물은 SWERVE 재시작이다(방향을 다시 고른다) —
    종전에는 HOLD 재정지였는데, 그것은 '회피 중에 또 막히면 선다'는 뜻이라 사람이
    많은 인도에서 정지 연쇄가 된다. 회피 경로가 막혔으면 다시 비껴가는 것이 맞다.
    어느 국면이든 phase_timeout_sec을 넘기면 다음 국면으로, RETURN이면 나간다 —
    나가는 곳은 블록이 살아 있으면 TRACE, 아니면 CRUISE 다 (_avoid_exit).
    여전히 막혀 있으면 그쪽의 장애물 검사가 즉시 다시 보내므로 고착이 없다.

    국면이 **앞으로** 넘어갈 때마다 avoid_progress_at 을 찍는다 — 정체 상한의 기준점이다.
    """
    if context.avoid_phase is AvoidPhase.HOLD:
        return _avoid_hold(context, params, frame, imu, now)

    # 경사 보정된 회피 스로틀 — 국면마다 다시 계산하지 않고 한 번만 잡는다
    bypass = _bypass_throttle_pct(params, imu)
    gave_up = _avoid_gave_up(context, params, now)
    if gave_up is not None:
        return gave_up

    timed_out = now - context.avoid_phase_since >= params.avoid_phase_timeout_sec

    if context.avoid_phase is AvoidPhase.SWERVE:
        # **최소 커밋** — 이 시간 전에는 장애물이 사라져도 선회를 끝내지 않는다.
        # 조향 슬루율 때문에 명령한 각도에 도착하는 데만 0.1초대가 걸리고, 그 사이
        # 로봇은 사실상 직진한다. '한 프레임 장애물이 밴드에서 빠짐 = 비켜났다'로
        # 읽으면 아무것도 안 하고 통과 국면에 들어간 뒤 복귀에서 다시 걸린다.
        committed = now - context.avoid_phase_since >= params.avoid_swerve_min_sec
        cleared = not frame.has_blocking_obstacle()
        if committed and (cleared or timed_out):
            # **진행으로 세는 것은 시각 판정으로 넘어갈 때뿐이다.** 국면 상한에 걸려
            # (장애물이 아직 잡히는 채로) 넘어가는 것은 진행이 아니다 — 그것까지 세면
            # SWERVE(상한) → PASS(1틱) → SWERVE 루프가 매 바퀴 진행으로 갱신되어
            # 정체 상한이 영원히 안 걸린다 (_avoid_gave_up 이 기록한 그 루프다).
            return (replace(context, avoid_phase=AvoidPhase.PASS,
                            avoid_phase_since=now,
                            avoid_progress_at=(now if cleared
                                               else context.avoid_progress_at)),
                    _bypass_command(params, throttle_pct=bypass))
        return context, _bypass_command(params, context.avoid_direction, bypass)

    if frame.has_blocking_obstacle():   # PASS·RETURN 중 새 장애물 → 다시 비껴간다
        return _swerve(context, params, frame, imu, now)

    if context.avoid_phase is AvoidPhase.PASS:
        if now - context.avoid_phase_since >= params.avoid_pass_sec or timed_out:
            return (replace(context, avoid_phase=AvoidPhase.RETURN,
                            avoid_phase_since=now, avoid_progress_at=now),
                    _bypass_command(params, -context.avoid_direction, bypass))
        return context, _bypass_command(params, throttle_pct=bypass)

    # RETURN — 목표(블록이 있으면 혼합 목표)로 되돌아오면 완료.
    # 잔여 횡오차는 추종/중심 추종이 수렴시킨다.
    _, return_ratio = _blend_target_ratio(context, params, frame)
    if abs(return_ratio) <= params.avoid_return_center_abs or timed_out:
        done = "재정렬" if not timed_out else "복귀 타임아웃"
        return _avoid_exit(context, params, now,
                           f"회피 완료 ({done}) → 원 경로 합류는 추종이 담당")
    return context, _bypass_command(params, -context.avoid_direction, bypass)


def _slope_must_stop(params: StateMachineParams, imu: Optional[ImuReading]) -> bool:
    """이 경사가 **주행이 아니라 정지**로 대응해야 하는 급한 하강인가.

    명세의 '하강 안전 정지'다. 이것만이 경사가 미션(점자블록 추종)을 앞지르는
    경우이고, 나머지 경사는 스로틀 보정으로 충분하다 (_handle_trace).
    """
    return (imu is not None
            and imu.pitch_deg <= -params.slope_downhill_brake_pitch_deg)


def _slope_throttle_pct(params: StateMachineParams, pitch_deg: float,
                        base_pct: Optional[float] = None) -> float:
    """경사 스로틀 프로파일 — 오르막(+pitch)은 증속, 내리막(−pitch)은 감속.

    pitch 부호는 IMU 실측 규약(hardware/jetson/imu.py): 기수 들림(오르막)이 양수.

    base_pct 는 보정의 기준 스로틀이다. 안 주면 CRUISE 스로틀 — SLOPE 상태의 종전
    동작 그대로다. TRACE 는 자기 추종/접근 스로틀을 기준으로 넘긴다: 같은 경사라도
    기준 속도가 다르면 보정 결과도 달라야 한다.
    """
    base = params.cruise_throttle_pct if base_pct is None else base_pct
    if pitch_deg > 0.0:
        return min(params.slope_uphill_throttle_max_pct,
                   base + params.slope_uphill_throttle_gain_pct_per_deg * pitch_deg)
    return max(params.slope_downhill_throttle_min_pct,
               base - params.slope_downhill_throttle_gain_pct_per_deg * -pitch_deg)


def _slope_holds_with_brake(params: StateMachineParams,
                            imu: Optional[ImuReading]) -> bool:
    """이 경사가 '장애물을 만나면 서서 기다려야 하는' **급한 내리막**인가.

    ※ 0806 2차: 오르막을 통째로 뺐다 (`abs(pitch)` → `pitch <= -값`). 실기 요구가
      '오르막에서는 장애물 때문에 서는 일이 아예 없게'였고, 근거를 다시 보니 브레이크
      대기의 두 이유가 오르막에서는 성립하지 않는다:
        · **밀림** — 오르막에서 뒤로 밀리는 것을 막는 방법은 브레이크가 아니라 계속
          굴러가는 것이다. 한 번 서면 정지마찰을 다시 이겨야 하는데 이 구동계는 등판
          여유가 없다(오르막 스로틀 이득이 상한 100에 물려 사실상 무효인 것이 그 증거).
        · **등판 토크** — 서 있는 동안 토크는 0이다. 아끼는 것이 아니라 버리는 것이다.
      즉 오르막에서 장애물에 서는 것은 '안전하게 기다림'이 아니라 '못 올라가게 됨'이다.
      내리막은 반대로 밀림이 실재하므로 그대로 둔다.

    브레이크 대기의 근거는 **밀림과 등판 토크**다 — 경사 위에서 저속으로 옆으로
    빠지는 기동은 뒤로 밀리기 쉽고 다시 붙기도 어렵다. 그런데 그 대가가 실제로
    발생하는 것은 경사가 어느 정도 급할 때이고, 이 로봇이 인도에서 만나는 경사
    대부분(연석 경사로·진입로)은 그보다 완만하다.

    종전에는 경사 진입 임계(5°) 하나로 갈라, **경사 위의 모든 장애물이 무기한 정지**
    였다: AVOID(avoid_brake)는 HOLD에서 선회로도, 재정향으로도 나가지 못하고
    장애물이 스스로 사라질 때까지 브레이크만 잡는다(_handle_avoid). 인지 오탐 하나가
    그대로 '경사에서 안 움직이는 로봇'이 된다 — 실제로 가장 흔한 정지 사유였다.
    그래서 정지가 필요한 급경사와, 평지처럼 비껴가면 되는 완만한 경사를 나눈다.
    """
    return (imu is not None
            and imu.pitch_deg <= -params.slope_downhill_avoid_brake_pitch_deg)


def _handle_slope(context: StateMachineContext, params: StateMachineParams, frame: SegmentationFrame,
                  imu: Optional[ImuReading], now: float
                  ) -> Tuple[StateMachineContext, DriveCommand]:
    """SLOPE: 경사 보정 **주행** — 조향은 CRUISE와 같은 인도 중심 추종, 스로틀만 보정.

    상승 경사: cruise 스로틀 + 이득×pitch (상한 클램프) — 등판 토크 확보.
    하강 경사: cruise 스로틀 − 이득×|pitch| (하한 클램프) — 과속 억제. 저듀티
              전진 PWM은 오프 구간이 사실상 모터 브레이크라 무동력 활강보다 느리다.
    급한 하강(pitch ≤ −downhill_brake_pitch_deg): 숏브레이크 정지 유지 —
              명세의 '하강 안전 정지'. 수동 개입 전까지 그 자리에 선다.
    **점자블록이 확정되면 추종(TRACE)에 넘긴다.** 종전에는 '경사 위에서는 블록을
    보지 않는다'였는데, 인도의 경사 대부분은 연석 경사로·건물 진입로라 그 규칙이
    곧 '블록을 잡아 놓고도 놓아 버린다'가 됐다(0805 실기: 추종 중 경사 진입 반복).
    경사에서 하지 말아야 할 것은 **정차 검사**이지 접근이 아니고, 스로틀 보정은
    TRACE 도 같은 산식으로 한다. 서야 하는 급경사(하강 안전 정지)는 아래에서
    여전히 잡는다 — 그건 블록보다 위다.
    pitch가 해제 임계 아래로 내려오거나 IMU가 사라지면(게이트 소실) CRUISE 복귀,
    장애물이 인식되면 AVOID(브레이크 유지 — 경사에서 밀리지 않게)로 넘긴다.
    """
    if imu is None:
        return _enter(context, DriveState.CRUISE, now, "IMU 소실 → 경사 보정 해제"), STOP_COMMAND
    hold_with_brake = _slope_holds_with_brake(params, imu)
    if frame.has_blocking_obstacle():
        if not hold_with_brake:
            # 완만한 경사 — 평지와 같이 좌/우로 비껴간다 (명세: '좌,우로 회피').
            return _enter_bypass(context, params, frame, imu, now,
                                 f"경사 중 장애물 인식 (pitch {imu.pitch_deg:+.1f}°)")
        return (_enter(context, DriveState.AVOID, now,
                       f"급경사 중 장애물 인식 (pitch {imu.pitch_deg:+.1f}°) — 정지 대기",
                       avoid_brake=True), BRAKE_COMMAND)
    # 블록 추종 게이트 — CRUISE 와 **같은 조건**이다 (braille_stable + 재추종 쿨다운).
    # 하강 안전 정지 구간만 빼고 넘긴다: 그 정지는 스로틀 보정이 아니라 안전 판단이다.
    if (context.braille_stable and _trace_cooldown_over(context, params, now)
            and not _slope_must_stop(params, imu)):
        return (_enter(context, DriveState.TRACE, now,
                       f"경사 중 점자블록 발견 확정 (pitch {imu.pitch_deg:+.1f}°)"
                       " → 추종 우선", trace_started_at=now, slope_brake_since=None),
                DriveCommand(throttle_pct=_slope_throttle_pct(
                    params, imu.pitch_deg, base_pct=params.trace_approach_throttle_pct),
                    steering_offset_deg=context.prev_steer_deg))
    if abs(imu.pitch_deg) < params.slope_exit_pitch_deg:
        return (_enter(context, DriveState.CRUISE, now, "경사 해소",
                       slope_brake_since=None), STOP_COMMAND)
    if imu.pitch_deg <= -params.slope_downhill_brake_pitch_deg:
        # 급경사 하강 안전 정지(명세). **스스로 푸는 출구가 없는 정지**이므로 오래
        # 끌면 관제에 보이게 한다: 그대로 두면 서버 투영은 PATROLLING(순찰 중)인데
        # 로봇은 그 자리에 영원히 서 있고, 아무도 그 사실을 모른다.
        since = (context.slope_brake_since
                 if context.slope_brake_since is not None else now)
        if now - since >= params.slope_downhill_brake_timeout_sec:
            return (_enter(context, DriveState.ESTOP, now,
                           f"급경사 하강 정지 지속 (pitch {imu.pitch_deg:+.1f}°)"
                           " → 정지 + 수동 대기", slope_brake_since=None),
                    BRAKE_COMMAND)
        # 정지 유지 — 급경사 하강이므로 숏브레이크가 아니라 **위치 고수 역스로틀**이다.
        # 명세의 '하강 안전 정지'가 지키려는 것이 '그 자리에 선다'이고, 브레이크만으로는
        # 그게 안 된다 (_hold_command).
        return replace(context, slope_brake_since=since), _hold_command(params, imu)
    if context.slope_brake_since is not None:
        context = replace(context, slope_brake_since=None)  # 경사가 완만해졌다

    context, resolved_ratio = _resolve_direction(
        context, params, frame, frame.sidewalk_center_offset_ratio)
    if resolved_ratio is None:
        return _enter_avoid_hold(context, params, imu, now,
                                 "코스트맵: 전 방향 통행 불가 → 정지 후 재확인",
                                 brake=hold_with_brake)
    return context, _steer_command(params, resolved_ratio,
                                   _slope_throttle_pct(params, imu.pitch_deg))


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
        return _enter_bypass(context, params, frame, imu, now, "회전 중 장애물 인식")

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


def _path_seen(frame: SegmentationFrame, blocked: bool) -> bool:
    """주행 카메라가 **갈 수 있는 경로**를 인지했는가.

    'blocked 아님'과 다르다: 코스트맵 슬롯이 ∞가 아니기만 하면 blocked는 풀리므로
    벽이 코앞이어도 참이 된다. 실제로 나가려면 인도가 보이고 정면을 막는 것이 없어야
    한다. 후진 종료·선회 완료가 **같은 기준**을 써야 시도 하나가 제 몫을 한다.
    """
    return (frame.sidewalk_detected and not blocked
            and not frame.has_blocking_obstacle())


def _next_attempt(context: StateMachineContext, params: StateMachineParams,
                  now: float) -> int:
    """이번이 몇 번째 탈출 시도인지.

    기준점은 **직전 시도/탈출 시각**이다. 탈출할 때도 시각을 다시 찍으므로, 이 값과
    지금의 차이는 '탈출한 뒤 얼마나 멀쩡히 달렸나'가 된다 — 창을 넘겨 달렸으면 그
    자리를 실제로 벗어난 것이니 1부터 다시 센다.
    진입 시각만 찍던 종전에는 사다리 한 번이 창보다 길어(17.8초 vs 20초) 다음 진입이
    늘 창 안에 들어왔고, 카운터가 7→10→13→17로 올라 **모든 탈출이 U턴**이 됐다.
    좌/우 시도를 건너뛰는 것은 낭비이고 야외에서는 위험하다 (0804 실기).
    """
    if (context.dead_end_attempt_at is not None
            and now - context.dead_end_attempt_at <= params.dead_end_attempt_window_sec):
        return context.dead_end_attempt + 1
    return 1


def _attempt_direction(frame: SegmentationFrame, attempt: int, previous: int) -> int:
    """시도 번호 → 선회 방향. 1회는 유리한 쪽, 2회부터는 **직전에 시도한 쪽의 반대**.

    반대를 '지금 시점의 유리한 쪽' 기준으로 잡으면 안 된다: 코스트맵이 매 틱 바뀌므로
    1회에 좌를 시도한 뒤 2회 시점에 우가 유리해지면 그 반대는 다시 좌가 되어 **같은
    방향을 반복**한다(0804 실기: 1번째 좌 → 2번째도 좌). 기준은 관측이 아니라
    '이미 해 본 것'이어야 한다.

    ※ 0806: 1회에도 `previous` 를 넘긴다. 시도 카운터는 창(attempt_window_sec)을 넘기면
      1로 리셋되는데, 그 리셋된 1회가 `_bypass_direction` 을 **기억 없이** 부르면 실제
      인도에서는 거의 항상 같은 답이 나온다(그 함수 주석의 실측: 좌 5 / 우 13 으로
      쏠렸고 ∞ 슬롯 수는 8:7 로 균형이었다). 그래서 재정향에 들어올 때마다 같은 쪽으로만
      꺾었다 — 0806 실기 '같은 방향으로만 조향하려는 경향'이 그것이다.
      `_bypass_direction` 의 교대는 **반대쪽이 통행 가능할 때만** 뒤집으므로 안전하다.
    """
    if attempt <= 1 or previous == 0:
        return _bypass_direction(frame, previous=previous)
    return -previous


def _enter_dead_end(context: StateMachineContext, params: StateMachineParams,
                    now: float, reason: str, *, phase: DeadEndPhase, direction: int,
                    entry_yaw: Optional[float] = None, attempt: Optional[int] = None,
                    steer_direction: Optional[int] = None, **fields
                    ) -> Tuple[StateMachineContext, DriveCommand]:
    """재정향 진입 — **여덟 경로가 여기로 모인다** (국면·방향·기준 방위만 다르다).

    들어오는 문이 여덟인 이유는 '막혔다'를 여러 층이 각자 발견하기 때문이다:
    AVOID 재확인 · 반복 회피 · 회피 전체 상한 · 후진 복구의 세 갈래 · U턴 승격.
    필드 묶음(국면·국면 시각·방향·기준 방위)이 같으므로 한 곳에서 만든다 — 종전에는
    같은 여덟 벌이 흩어져 있어 필드 하나를 더할 때마다 여덟 곳을 맞춰야 했다.

    entry_yaw 는 회전 상한 판정의 기준 방위다(IMU 가 없으면 None → 상한만 비활성).
    attempt 를 주면 시도 번호와 그 시각을 함께 찍는다 — 안 주는 경로(후진 복구 반복)는
    시도 카운터를 건드리지 않는다는 뜻이다.
    steer_direction 은 **첫 틱의 조향에만** 쓰는 예외 통로다 (U턴 승격 한 곳 — 그
    호출부 주석 참조). 지정하지 않으면 direction 과 같다.
    """
    if attempt is not None:
        fields.setdefault("dead_end_attempt", attempt)
        fields.setdefault("dead_end_attempt_at", now)
    # **인지 소실 계열 타이머를 전부 끈다.** 재정향에 들어왔다는 것은 이 상태의 국면
    # 기계가 이제 기동을 소유한다는 뜻인데, 이 타이머 중 하나라도 살아 있으면
    # _guard_perception 이 핸들러보다 먼저 돌아 명령을 후진 복구로 덮는다 — 그쪽은
    # 조향 0 직진 후진이라 재정향이 벌어야 할 회전을 한 도(°)도 벌지 못한다.
    # 여덟 진입 경로 중 일부만 이걸 껐던 것이 0805 실기의 '재정향이 후진만 하다
    # 사방 막힘으로 끝남'의 나머지 절반이다.
    fields.setdefault("reverse_recovery_since", None)
    fields.setdefault("perception_bad_since", None)
    fields.setdefault("backtrack_since", None)
    steering = direction if steer_direction is None else steer_direction
    return (_enter(context, DriveState.DEAD_END, now, reason,
                   avoid_brake=False, dead_end_phase=phase, dead_end_phase_since=now,
                   dead_end_direction=direction, dead_end_entry_yaw_deg=entry_yaw,
                   **fields),
            _dead_end_command(params, phase, steering))


def _uturn_escape(context: StateMachineContext, params: StateMachineParams,
                  frame: Optional[SegmentationFrame], imu: Optional[ImuReading],
                  now: float, reason: str, **fields
                  ) -> Tuple[StateMachineContext, DriveCommand]:
    """**'정지 + 수동 대기' 대신 U턴** — 0806 실기 지시가 만든 자리.

    "완전 정지 해야하는 경우에는 그냥 u턴으로 180도 돌려버리고 계속 진행해야한다."

    종전에 미션을 끝내던 세 자리가 여기로 모인다: 후진 복구 상한(전 방향 막힘) ·
    재정향 상한(회전량/시간) · 경계 소실 유예 만료. 셋 다 IDLE 이었고, IDLE 은 수동
    개입만이 출구라 그때마다 사람이 로봇에게 가야 했다 — 0805 세션 7에서 미션 종료
    33회 중 11회가 첫 번째 사유 하나였다. 셋의 공통점은 '**앞으로는** 못 간다'이고,
    그 답이 '선다'일 이유가 없다: 방향을 되돌리면 방금 지나온, 통과가 이미 확인된
    길이 앞에 온다.

    **ESTOP 계열은 여기 오지 않는다** (수동 정지 · 코리도가 차도로 덮임 · 급경사 하강
    정지 지속). 그건 고착이 아니라 안전 판단이라 방향을 되돌린다고 풀리지 않는다.

    두 경우에만 종전대로 IDLE 이다:
      · frame 이 없다(카메라 장애) — 눈먼 180° 회전은 눈먼 전진보다 나쁘다.
      · 창 안에서 uturn_escape_limit 회를 이미 썼다 — 무한 배회를 막는 유일한 장치다.
        창을 **정상 주행으로** 넘겼으면 실제로 벗어난 것이니 1부터 다시 센다.
    """
    episodes = (context.uturn_escape_count + 1
                if (context.uturn_escape_last is not None
                    and now - context.uturn_escape_last
                    <= params.dead_end_uturn_escape_window_sec)
                else 1)
    if frame is None or episodes > params.dead_end_uturn_escape_limit:
        why = ("카메라 장애 — 눈먼 회차는 하지 않는다" if frame is None
               else f"U턴 탈출 {params.dead_end_uturn_escape_limit}회 소진")
        return (_enter(context, DriveState.IDLE, now,
                       f"{reason} ({why}) → 정지 + 수동 대기",
                       avoid_brake=False, perception_bad_since=None,
                       reverse_recovery_since=None, backtrack_since=None,
                       uturn_escape_count=0, uturn_escape_last=None, **fields),
                BRAKE_COMMAND)
    # 시도 번호를 U턴 문턱에 맞춰 찍는다 — 이 자리에서 빠져나갔다가 창 안에 다시
    # 막히면 좌/우를 처음부터 해 보는 것이 아니라 곧장 U턴이어야 한다.
    return _enter_dead_end(
        context, params, now,
        f"{reason} → 정지 대신 U턴 ({episodes}/{params.dead_end_uturn_escape_limit}회째)",
        phase=DeadEndPhase.UTURN, attempt=params.dead_end_uturn_after_cycles,
        direction=params.dead_end_uturn_direction,
        entry_yaw=(None if imu is None else imu.yaw_deg),
        dead_end_uturn_since=now,
        uturn_escape_count=episodes, uturn_escape_last=now,
        reverse_recovery_count=0, reverse_recovery_last=None, **fields)


def _enter_straighten(context: StateMachineContext, now: float, reason: str
                      ) -> Tuple[StateMachineContext, DriveCommand]:
    """재정향에서 빠져나가기 전 **바퀴 정렬** 국면 — 정지한 채 조향만 편다.

    세 출구(재정향 완료·U턴 완료·U턴 중 경로 확보)가 같은 자리로 모인다.
    """
    return (_enter(context, DriveState.DEAD_END, now, reason,
                   dead_end_attempt_at=now,
                   dead_end_phase=DeadEndPhase.STRAIGHTEN,
                   dead_end_phase_since=now), STOP_COMMAND)


def _blocked_everywhere(frame: SegmentationFrame) -> bool:
    """갈 수 있는 방향이 하나도 없는가 — **두 가지 사실의 합집합**이다.

    비용 ∞(차도·횡단보도로만 둘러싸임)는 '가면 안 된다'이고,
    all_directions_blocked(벽·담장, 자유거리 절대 판정)는 '갈 수 없다'다.
    후자를 빼면 실외에서 회차가 사실상 발동하지 않는다: 벽은 어느 클래스도 ∞가
    아니라(미분류 0.9) 전 슬롯 ∞가 성립하지 않고, 좌우 회피만 무한 반복된다.
    AVOID 재확인과 재정향이 같은 질문을 하므로 식은 여기 한 곳에만 둔다.
    """
    return bool(frame.all_directions_blocked
                or (frame.cost_profile is not None
                    and all(math.isinf(cost) for cost in frame.cost_profile)))


def _dead_end_straighten(context: StateMachineContext, params: StateMachineParams,
                         now: float) -> Tuple[StateMachineContext, DriveCommand]:
    """바퀴 중앙 정렬 — 조향 슬루율 제한 때문에 -40°에서 한 번에 안 돌아온다.

    **정지한 채로** 조향만 0으로 되돌린 뒤에 추종을 시작한다: 꺾인 채로 전진하면
    방금 피한 쪽으로 호를 그리며 다시 들어간다(0804 실측: 꼬리 0.6초, 그 사이
    19cm를 30° 꺾인 채 주행 — '재정향 완료 → 0.3초 뒤 다시 AVOID'의 원인).
    """
    aligned = abs(context.prev_steer_deg) <= params.dead_end_straighten_deg
    timed_out = (now - context.dead_end_phase_since
                 >= params.dead_end_straighten_max_sec)
    if aligned or timed_out:
        return (_enter(context, DriveState.CRUISE, now,
                       "바퀴 정렬 완료 → 인도 추종 시작"
                       if aligned else "바퀴 정렬 상한 → 인도 추종 시작",
                       dead_end_attempt_at=now), STOP_COMMAND)
    return context, STOP_COMMAND


def _dead_end_escaped(context: StateMachineContext, params: StateMachineParams,
                      frame: SegmentationFrame, blocked: bool, now: float
                      ) -> GuardResult:
    """재정향의 **유일한 정상 출구** — 선회 호를 다 돈 뒤 새 경로가 보이는가.

    **TURN_ARC 국면에서만 본다.** REVERSE 중에 보지 않는 이유: 반복 회피로 승격된
    경우 인지는 바로 그 순간에도 '막히지 않았다'고 말하고 있다(그 오판이 승격의
    이유다). 그때 완료를 보면 후진을 단 한 틱도 못 하고 곧장 CRUISE로 되돌아가 같은
    회피 루프에 다시 빠진다. 뒤로 빼서 상황을 바꾸는 것이 이 상태의 목적이므로,
    최소 한 번의 후진과 선회를 마친 뒤에 판정한다.
    **호를 다 돈 뒤에만** 판정하는 것도 같은 이유다: 매 틱 보면 진입 순간 바로
    끝난다(후진은 path_seen 이 참이 되어야 끝나고, 받는 쪽은 그 상태를 그대로
    넘겨받는다 — 0804 실기: 0.1초 만에 이탈, 조향은 10°도 못 들어간 채였다).

    장애물 유무는 보지 않는다: 막다른 길의 벽은 빠져나오는 동안에도 blocking으로
    남아 있고, 그것까지 요구하면 '경로는 생겼는데 못 나가는' 상태가 된다.
    남은 장애물은 CRUISE의 장애물 검사가 다시 AVOID로 보내 처리한다.
    완료 기준은 후진 종료와 **같다**(_path_seen): 'blocked 아님'만으로는 벽이
    코앞이어도 통과해(슬롯이 ∞만 아니면 풀린다) 꺾자마자 빠져나갔다가 CRUISE가
    곧장 다시 박고, 시도 하나가 1.6초 만에 소진돼 3초 만에 U턴까지 간다(0804 실기).
    """
    if (context.dead_end_phase is DeadEndPhase.TURN_ARC
            and now - context.dead_end_phase_since >= params.dead_end_forward_sec
            and _path_seen(frame, blocked)):
        return _enter_straighten(context, now, "재정향 완료 (새 경로 확보) → 바퀴 정렬")
    return None


def _dead_end_limits(context: StateMachineContext, params: StateMachineParams,
                     frame: SegmentationFrame, imu: Optional[ImuReading], now: float
                     ) -> GuardResult:
    """무한 반복 방지 상한 — 회전량(IMU 있을 때)과 전체 시간.

    **출구가 상황에 따라 둘이다** (0806):
      좌/우 시도 중이었다 → U턴 승격. 앞으로 못 간다는 사실만으로 사람을 부르지 않는다.
      이미 U턴 중이었다  → IDLE. 방향까지 되돌려 놓고도 상한에 닿았으면 진짜로 갇혔다
                           (또는 바퀴가 헛돌아 yaw 가 안 늘고 있다).
    U턴 승격의 횟수 상한은 _uturn_escape 가 들고 있다.
    """
    def _stopped(reason: str) -> Tuple[StateMachineContext, DriveCommand]:
        if context.dead_end_phase is DeadEndPhase.UTURN:
            return (_enter(context, DriveState.IDLE, now,
                           f"{reason} → 정지 + 수동 대기",
                           uturn_escape_count=0, uturn_escape_last=None),
                    BRAKE_COMMAND)
        return _uturn_escape(context, params, frame, imu, now, reason)

    if imu is not None and context.dead_end_entry_yaw_deg is not None:
        # 회전 상한 — 부호에 의존하지 않도록 절댓값으로 본다. 완료가 시각이므로
        # 이 값은 '얼마나 돌았나'가 아니라 '너무 돌았나'만 판단한다.
        spun_deg = abs(imu.yaw_deg - context.dead_end_entry_yaw_deg)
        if spun_deg >= params.dead_end_max_yaw_deg:
            return _stopped(f"재정향 실패(회전 {spun_deg:.0f}° 초과 — 갇힘)")
    if now - context.entered_at >= params.dead_end_timeout_sec:
        spun = ("" if imu is None or context.dead_end_entry_yaw_deg is None
                else f", 회전 {imu.yaw_deg - context.dead_end_entry_yaw_deg:+.0f}°")
        return _stopped(f"재정향 실패(타임아웃{spun})")
    return None


def _dead_end_uturn(context: StateMachineContext, params: StateMachineParams,
                    frame: SegmentationFrame, imu: Optional[ImuReading],
                    blocked: bool, now: float
                    ) -> Tuple[StateMachineContext, DriveCommand]:
    """U턴 — **여기서만 완료 기준이 yaw 목표각이다.**

    방향을 되돌리는 것이 목적이라 '경로가 보인다'로는 끝낼 수 없다(뒤로 빼고 꺾는
    것으로 이미 여러 번 실패한 자리이고, 그때마다 '보인다'가 곧 다시 막혔다).
    yaw_sign이 정정된 뒤에야 성립하는 판정이다 — 부호가 틀렸을 때 이 조건은 도달
    불가였다. IMU가 없으면 각도를 잴 수 없으므로 시간으로 대신한다(정확하지 않지만
    '아무것도 못 함'보다 낫다 — AVOID가 같은 이유로 시간 폴백을 쓴다).
    """
    started = (context.dead_end_uturn_since
               if context.dead_end_uturn_since is not None else now)
    if imu is not None and context.dead_end_entry_yaw_deg is not None:
        turned = abs(imu.yaw_deg - context.dead_end_entry_yaw_deg)
        done = turned >= (params.dead_end_uturn_target_yaw_deg
                          - params.dead_end_uturn_tolerance_deg)
    else:
        turned = None
        done = now - started >= params.dead_end_uturn_sec
    spun = "" if turned is None else f" (회전 {turned:.0f}°)"
    if done:
        return _enter_straighten(context, now, "U턴 완료" + spun + " → 바퀴 정렬")

    turned_enough = (now - started >= params.dead_end_uturn_min_sec
                     if turned is None
                     else turned >= params.dead_end_uturn_min_yaw_deg)
    if turned_enough and _path_seen(frame, blocked):
        # 회차 도중에 **진짜로 트인 경로**가 나오면 굳이 다 돌지 않는다 —
        # U턴은 목적이 아니라 수단이다. 여기서 쓰는 기준은 강한 쪽(_path_seen:
        # 인도가 보이고 정면을 막는 것이 없음)이다. 약한 'blocked 아님'으로 열어
        # 두면 벽이 코앞인 상태에서 빠져나가 곧장 다시 박는다 — 그 실패는
        # 재정향 완료 판정에서 이미 겪었다.
        return _enter_straighten(context, now, "U턴 중 경로 확보" + spun + " → 바퀴 정렬")

    # 깊은 조향 전진 호와 짧은 정렬 후진을 교대한다 — 좁은 곳에서 한 번에 돌 수
    # 있는 반경이 아니다. 국면 타이머는 dead_end_phase_since를 그대로 쓴다.
    leg = now - context.dead_end_phase_since
    if leg >= params.dead_end_forward_sec + params.dead_end_reverse_min_sec:
        context = replace(context, dead_end_phase_since=now)
        leg = 0.0
    context = replace(context, dead_end_uturn_since=started)
    phase = (DeadEndPhase.TURN_ARC if leg < params.dead_end_forward_sec
             else DeadEndPhase.REVERSE)
    return context, _dead_end_command(params, phase, context.dead_end_direction)


def _dead_end_reverse(context: StateMachineContext, params: StateMachineParams,
                      frame: SegmentationFrame, blocked: bool, now: float
                      ) -> Tuple[StateMachineContext, DriveCommand]:
    """후진 — 고정 시간이 아니라 **막힘이 풀릴 때까지** 한다(상한 안에서).

    0.9초 고정이던 종전 구조는 벽에 바짝 붙었을 때 선회할 공간을 못 만들고
    곧장 전진 호로 넘어가 다시 벽에 박았다. 최소 시간을 두는 이유는 인지가
    한 틱 깜빡이는 것으로 후진이 끊기지 않게 하기 위해서다.
    종료 조건은 **주행 카메라가 경로를 인지했는가**다 — 시간이 아니다.
    'blocked가 아니다'만으로는 부족하다: 벽이 여전히 코앞에 있어도 코스트맵
    슬롯이 ∞가 아니면 blocked가 풀린다. 실제로 나갈 수 있으려면 인도가 보이고
    정면을 막는 것이 없어야 한다. 상한은 안전 한계일 뿐 목표가 아니다
    (후방은 카메라 사각이라 무한정 뺄 수 없다).
    """
    reversed_sec = now - context.dead_end_phase_since
    if (reversed_sec >= params.dead_end_reverse_max_sec
            or (_path_seen(frame, blocked)
                and reversed_sec >= params.dead_end_reverse_min_sec)):
        return (replace(context, dead_end_phase=DeadEndPhase.TURN_ARC,
                        dead_end_phase_since=now),
                _dead_end_command(params, DeadEndPhase.TURN_ARC,
                                  context.dead_end_direction))
    return context, _dead_end_command(params, DeadEndPhase.REVERSE,
                                      context.dead_end_direction)


def _dead_end_retrace(context: StateMachineContext, params: StateMachineParams,
                      imu: Optional[ImuReading], now: float
                      ) -> Tuple[StateMachineContext, DriveCommand]:
    """**되짚기 후진** — 실패한 전진 호를 같은 조향각으로 되감아 원점으로 돌아간다.

    0806 실기 지시: "첫 조향에 실패하면 정렬하지 않고 그대로 후진해서 원점에 오고,
    첫 방향의 반대방향으로 쭉 조향해서 가야 한다."

    종전에는 실패한 호 뒤에 조향을 0으로 펴고 직진 후진했다(REVERSE). 호로 나간 만큼
    횡방향·각도로 어긋난 자리에서 뒤로만 빠지므로, **반대쪽 시도가 원점이 아닌 곳에서
    시작**했다 — 좌·우를 다 해 봐도 같은 벽을 다른 각도에서 만나기만 했고, 그래서
    시도가 전부 실패로 세어져 U턴까지 갔다.
    같은 조향각·같은 스로틀·같은 시간이면 회전중심이 같아 그 호를 되짚는다(차량
    기구학). 오도메트리 없이 원점 복귀를 근사하는 유일한 수단이고, 경계 소실의 되짚기
    후진이 이미 같은 원리를 쓰고 있다.

    **여기서는 '경로가 보인다'로 조기 종료하지 않는다.** 후진 국면(REVERSE)은 공간을
    버는 것이 목적이라 보이는 즉시 끝내는 것이 맞지만, 이쪽의 목적은 원점 복귀다 —
    중간에 끊으면 반대쪽 시도가 다시 어긋난 자리에서 출발한다. 상한은 시간뿐이고,
    되짚는 공간은 방금 전진으로 지나온 자리라 후방 사각의 위험이 가장 작은 후진이다.
    """
    if now - context.dead_end_phase_since < params.dead_end_retrace_sec:
        return context, _dead_end_command(params, DeadEndPhase.RETRACE,
                                          context.dead_end_direction)
    # 원점 복귀 완료 — 이제 **반대쪽**으로 꺾어 나간다.
    cycles = context.dead_end_attempt + 1
    if cycles >= params.dead_end_uturn_after_cycles:
        return _enter_dead_end(
            context, params, now, f"재정향 {cycles}회 실패(되짚기 완료) → U턴",
            phase=DeadEndPhase.UTURN, attempt=cycles,
            direction=params.dead_end_uturn_direction,
            entry_yaw=(None if imu is None else imu.yaw_deg),
            dead_end_uturn_since=now)
    flipped = -context.dead_end_direction
    return (replace(context, dead_end_phase=DeadEndPhase.TURN_ARC,
                    dead_end_phase_since=now, dead_end_attempt=cycles,
                    dead_end_attempt_at=now, dead_end_direction=flipped),
            _dead_end_command(params, DeadEndPhase.TURN_ARC, flipped))


def _dead_end_turn_arc(context: StateMachineContext, params: StateMachineParams,
                       now: float) -> Tuple[StateMachineContext, DriveCommand]:
    """선회 전진 호 — 호를 다 돌았는데 완료(=경로 확보)가 안 났으면 이번 시도 실패다.

    실패의 다음 국면은 **되짚기 후진(RETRACE)**이다 — 방향을 뒤집는 것은 원점으로
    돌아간 뒤(_dead_end_retrace)이지 여기가 아니다.
    """
    if now - context.dead_end_phase_since < params.dead_end_forward_sec:
        return context, _dead_end_command(params, DeadEndPhase.TURN_ARC,
                                          context.dead_end_direction)

    # 실패한 호를 **되짚어 원점으로** 돌아간다. 방향을 뒤집는 것도, U턴 승격도 그 뒤다
    # (_dead_end_retrace) — 같은 방향으로 반복하면 그쪽이 막힌 자리에서는 몇 번을
    # 해도 같은 결과이지만(0804 실기 지적), 어긋난 자리에서 반대로 꺾는 것도
    # 같은 벽을 다른 각도에서 만나는 것뿐이다(0806 실기 지적).
    # 시도 카운터는 되짚기가 끝난 뒤에 올린다 — 한 번의 시도는 '호 + 되짚기'다.
    return (replace(context, dead_end_phase=DeadEndPhase.RETRACE,
                    dead_end_phase_since=now),
            _dead_end_command(params, DeadEndPhase.RETRACE,
                              context.dead_end_direction))


def _handle_dead_end(context: StateMachineContext, params: StateMachineParams,
                     frame: SegmentationFrame, imu: Optional[ImuReading], now: float
                     ) -> Tuple[StateMachineContext, DriveCommand]:
    """DEAD_END: 막다른 길 재정향 — 후진으로 공간을 벌고 유리한 쪽으로 선회해 새 경로를 찾는다.

    진입은 AVOID 재확인이 담당한다: 좌/우 어느 쪽에도 경로가 없을 때만 여기로 온다.

    하위 국면(DeadEndPhase) — **한 번의 시도 = 조향 전진 호 + 되짚기 후진**이다:
      TURN_ARC  고른 방향으로 조향 전진 — 회전을 **버는** 국면이고, 이것이 기본이다.
      RETRACE   실패한 호를 **같은 조향각으로** 되감아 원점으로 — 그 다음 시도가
                원점에서 반대쪽으로 출발하게 만드는 국면. 여기서 방향이 뒤집힌다.
      REVERSE   조향을 0으로 펴고 직진 후진 — **진입 직후**(벽에 코가 닿아 호를 그릴
                공간 자체가 없을 때)와 U턴의 정렬 다리에만 쓴다. 조향을 주지 않는
                이유는 후방이 카메라 사각(완전 무지)이라서다. 되짚기는 방금 전진으로
                지나온 자리를 되감는 것이라 그 사각의 위험이 가장 작다.
    호를 하나 다 돈 뒤마다 '갈 수 있는 경로가 생겼는가'를 본다.

    **어느 쪽으로 시작하는가는 진입 사유가 정한다.** 전 방향 통행 불가로 온 경우
    (벽에 코가 닿았다)는 후진부터다 — 꺾어도 호를 그릴 공간이 없다. 반복 회피로 온
    경우(못 지나갔을 뿐 앞은 열려 있다)는 선회 전진부터다: 그 자리에서 먼저 뒤로 빼면
    번 것 없이 인도 뒤쪽으로 밀려나기만 하고 시도 시간만 먹는다 (0805 실기).

    **완료는 시각이다 — 목표 각도가 아니다.** 종전에는 yaw 누적이 180°에 닿는 것이
    유일한 출구였고, 그래서 부호 하나(yaw_sign)가 틀리자 누적이 반대로 가며 완료
    조건에 **도달 자체가 불가능**해져 매번 timeout 30초를 채웠다(0804 실내 실측:
    좌조향 회차의 누적이 -1.5° → -7.0°). AVOID·TURN은 같은 교훈으로 이미 시각
    판정으로 옮겨가 있었고 그래서 같은 부호 오류에도 멀쩡히 돌았다 — 이 상태만
    yaw 단독이라 죽었다. yaw는 이제 무한 회전을 막는 상한으로만 남는다.

    **IMU를 요구하지 않는다.** 완료가 시각이므로 IMU 없이도 재정향은 성립한다.
    종전 IMU 게이트는 센서가 빠지면 막다른 길에서 아무것도 못 하게 만들었고,
    그것은 AvoidPhase 주석이 기록한 0803 실외 실패('자꾸 정지만 함')와 같은 형태다.
    IMU가 있으면 회전 상한만 추가로 얻는다.

    재정향 중 blocking 장애물 검출은 무시한다 — 막다른 길에서는 벽·구석이 상시
    blocking으로 잡히므로 여기서 재정지하면 재정향이 영원히 시작을 못 한다.
    (차도 위험 ESTOP·경계 소실 IDLE 폴백 등 공통 가드는 그대로 산다.)

    안전 출구:
      경로 확보(시각)        → CRUISE. 잔여 정렬은 중심 추종이 수렴시킨다.
      |yaw| ≥ max_yaw_deg   → 좌/우 시도 중이었으면 **U턴 승격**, U턴 중이었으면 IDLE.
      timeout_sec 초과       → 같은 규칙. 무한 반복하지 않는다 (_dead_end_limits).
    """
    if context.dead_end_phase is DeadEndPhase.STRAIGHTEN:
        return _dead_end_straighten(context, params, now)

    blocked = _blocked_everywhere(frame)
    escaped = _dead_end_escaped(context, params, frame, blocked, now)
    if escaped is not None:
        return escaped
    stopped = _dead_end_limits(context, params, frame, imu, now)
    if stopped is not None:
        return stopped

    if context.dead_end_phase is DeadEndPhase.UTURN:
        return _dead_end_uturn(context, params, frame, imu, blocked, now)
    if context.dead_end_phase is DeadEndPhase.REVERSE:
        return _dead_end_reverse(context, params, frame, blocked, now)
    if context.dead_end_phase is DeadEndPhase.RETRACE:
        return _dead_end_retrace(context, params, imu, now)
    return _dead_end_turn_arc(context, params, now)


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
