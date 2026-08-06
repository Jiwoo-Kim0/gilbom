"""조향·방향 선택 — 순수 기하. 상태를 모르고 값만 계산한다.

조향이 **좌우 비대칭**이라는 하드웨어 사실이 이 모듈 전체를 관통한다 (좌 45 / 우 30,
우측 절대 130°는 링키지 걸림 경계 — docs/HARDWARE.md §4). 그래서 어느 상태든 조향
크기는 좌우를 따로 들고, 클램프도 한 함수(_clamp_steer)만 지난다.
"""

import math
from dataclasses import replace
from typing import Optional, Tuple

from driving.states import DeadEndPhase, DriveCommand
from driving.steering_costmap import select_direction
from perception.interfaces import SegmentationFrame

from driving.state_machine.data import StateMachineContext, StateMachineParams



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


def _side_is_open(frame: SegmentationFrame, direction: int) -> bool:
    """그 방향 절반에 **갈 수 있는** 슬롯이 하나라도 있는가 (비용이 유한한 슬롯).

    반대쪽으로 돌리기 전에 확인하는 자리다 — 차도가 있는 절반은 전 슬롯이 ∞라
    여기서 걸러지고, 그래서 '번갈아 시도'가 로봇을 차도로 밀어 넣지 않는다.
    """
    if frame.cost_profile is None:
        return True
    half = len(frame.cost_profile) // 2
    side = (frame.cost_profile[:half] if direction < 0
            else frame.cost_profile[-half:])
    return any(not math.isinf(cost) for cost in side)


def _bypass_direction(frame: SegmentationFrame, previous: int = 0,
                      prefer: int = 0) -> int:
    """우회 방향 (-1=좌, +1=우) — 좌/우 중 경로 이득이 큰 쪽.

    `prefer`(점자블록이 있는 쪽)를 주면 **그 쪽을 먼저 고른다** — 단 그 절반이 통행
    가능하고, 직전에 그 쪽으로 시도해 실패한 것이 아닐 때만. 미션이 '블록을 최우선으로
    따라간다'인데 회피 방향을 코스트맵 점수로만 고르면, 블록 반대쪽으로 비껴간 뒤
    복귀가 인도 중심을 향하면서 블록에서 멀어진 채 끝난다 (0806 실기: '블록을 발견하고도
    장애물 이슈로 조향이 틀어져 비틀려 버렸다'). 어느 쪽으로 비껴가도 장애물을 지나는
    것은 같으므로, 같은 값이면 블록에 붙어 있는 쪽이 낫다.
    `previous` 와 같으면 양보한다 — 그 쪽으로 이미 실패했다는 사실이 '블록이 그쪽에
    있다'보다 강한 근거다(아래 `_alternate` 와 같은 판단).

    코스트맵이 있으면 통행 불가 슬롯이 적은 쪽, 동수면 통행 비용이 낮은 쪽이다
    (_half_score). 코스트맵이 없거나 좌우가 완전 동점이면 가장 가까운 blocking
    장애물의 치우침 반대쪽. 그래도 정중앙(치우침 0)이면 좌다.
    ⚠️ 이 좌 기본값의 **근거는 사라졌다.** 종전 근거는 '좌측 조향 여유(45°)가 우(30°)보다
    커서 회전반경이 작다'였는데, 0805 실측이 정반대다 — 같은 90% 스로틀에서
    우 20°가 17.6 dps·반경 0.94m, 좌 25°가 10.9 dps·반경 1.51m 다(config cruise 주석).
    **우측이 60% 빠르고 반경도 38% 작다.** 그래서 U턴 방향은 우로 뒤집었지만
    (dead_end.uturn_direction), 이 동점 기본값은 0806 실기의 비교 기준선을 유지하려고
    일부러 좌로 남겨 두었다 — 한 판에 변수를 둘 바꾸지 않는다. 다음 실기에서 뒤집을 것.

    장애물 요약이 영역 1개(무게중심)라 '왼쪽에 사람 + 오른쪽에 벽'이 중앙 치우침
    0으로 뭉개진다 — 그래서 코스트맵 쪽을 먼저 본다. 요약을 쪼개는 것은 인지 계층
    작업이다 (perception/segmentation.py의 연결성분 주석).

    `previous`(직전 회피에서 고른 방향)를 주면 **같은 쪽을 두 번 연속 고르지 않는다.**
    이 판정의 입력은 '지금 장면'뿐이라, 장면이 거의 안 변하는 실제 인도에서는 거의
    항상 같은 답을 낸다 — 한쪽이 차도(∞)거나 한쪽에 건물·화단이 이어지면 좌/우 절반
    점수가 매번 같은 쪽으로 기울고, 회피가 끝나면 RETURN 이 원래 자리로 되돌리므로
    같은 장애물을 같은 각도에서 다시 만난다. 실측(2026-08-05, 실사 38장)에서 방향이
    좌 5 / 우 13 으로 갈렸고, 그동안 좌우의 ∞ 슬롯 수는 8:7로 균형이었다 — 즉 쏠림은
    차도 때문만이 아니라 **기억이 없기 때문**이다. 같은 쪽으로 이미 시도해서 못 지나간
    상황이면 그 정보가 지금 장면보다 강한 근거다(DEAD_END 의 _attempt_direction 이
    같은 이유로 '직전의 반대'를 쓴다). 반대쪽이 통행 불가면 뒤집지 않는다.
    """
    if prefer != 0 and prefer != previous and _side_is_open(frame, prefer):
        return prefer
    if frame.cost_profile is not None:
        half = len(frame.cost_profile) // 2
        left = _half_score(tuple(frame.cost_profile[:half]))
        right = _half_score(tuple(frame.cost_profile[-half:]))
        if left != right:
            return _alternate(-1 if left < right else 1, previous, frame)
    blocking = [obstacle for obstacle in frame.obstacles if obstacle.blocking]
    if not blocking:
        return _alternate(-1, previous, frame)   # 방어적 기본값 (호출자가 blocking 보장)
    nearest = max(blocking, key=lambda obstacle: obstacle.proximity)
    return _alternate(-1 if nearest.lateral_bias >= 0.0 else 1, previous, frame)


def _alternate(chosen: int, previous: int, frame: SegmentationFrame) -> int:
    """직전과 같은 쪽이면 반대쪽으로 — 단 그쪽이 열려 있을 때만 (_bypass_direction)."""
    if previous == 0 or chosen != previous or not _side_is_open(frame, -previous):
        return chosen
    return -previous


def _bypass_command(params: StateMachineParams, direction: int = 0,
                    throttle_pct: Optional[float] = None) -> DriveCommand:
    """회피 국면의 주행 명령. 방향 0 이면 조향 중립(통과 직진)이다.

    회피는 감속하되 멈추지 않는다 — 30%대는 조향이 꺾이면 링키지·타이어 저항을
    못 이겨 거의 정지한다(0803 야외 실측). 국면 차이는 조향으로만 낸다.
    `throttle_pct` 를 주면 그 값을 쓴다 — 경사 보정된 스로틀을 넣는 통로다
    (handlers._bypass_throttle_pct). 이 모듈은 경사를 모르므로 계산은 그쪽이 한다.
    """
    return DriveCommand(
        throttle_pct=(params.avoid_bypass_throttle_pct if throttle_pct is None
                      else throttle_pct),
        steering_offset_deg=(0.0 if direction == 0
                             else _turn_steer_deg(params, direction)))


def _steer_command(params: StateMachineParams, resolved_ratio: float,
                   throttle_pct: float) -> DriveCommand:
    """방향(-1~+1) → 주행 명령. 조향 클램프는 **좌우 비대칭**이다.

    하드웨어가 비대칭이라(좌 45 / 우 30, 우측 절대 130°는 링키지 걸림 경계)
    주행 계층도 좌우를 따로 든다 — FSM 이 우 30°를 내고 하드웨어에서 잘리면 자기
    명령이 안 먹은 걸 모른 채 계속 그 값을 낸다. CRUISE·TRACE·SLOPE 가 같은 산식을
    쓰므로 한 곳에 둔다.
    """
    return DriveCommand(
        throttle_pct=throttle_pct,
        steering_offset_deg=_clamp_steer(resolved_ratio * params.cruise_steer_gain_deg,
                                         params.cruise_steer_left_max_deg,
                                         params.cruise_steer_right_max_deg))


def _turn_steer_deg(params: StateMachineParams, direction: int) -> float:
    """회전 방향(-1=좌, +1=우) → 조향 오프셋(도). 조향이 비대칭이라 좌우 크기가 다르다."""
    return (-abs(params.turn_steer_left_deg) if direction < 0
            else abs(params.turn_steer_right_deg))


def _dead_end_steer_deg(params: StateMachineParams, direction: int) -> float:
    """재정향 선회 조향 오프셋(도). 좌우 한계가 비대칭이라 크기가 다르다.

    ※ 0806 2차부터 **TURN 과 같은 값**이다(좌 25 / 우 20). 종전에는 '짧은 호 반복이라
    회전반경이 우선'이라며 더 깊게 줬는데, 실측이 그 전제를 뒤집었다 — 이 구동계는
    깊게 줄수록 스크럽 저항에 막혀 **덜 돈다**(좌 40° = 4.2 dps vs 좌 25° = 10.9 dps).
    즉 재정향에서 '더 깊게'는 정확히 반대 방향의 튜닝이었다. 그래도 별도 키로 남기는
    이유는 재정향만 다시 조정할 여지를 열어 두기 위해서다."""
    return (-abs(params.dead_end_steer_left_deg) if direction < 0
            else abs(params.dead_end_steer_right_deg))


def _dead_end_command(params: StateMachineParams, phase: DeadEndPhase,
                      direction: int) -> DriveCommand:
    """재정향 국면의 주행 명령 — 후진 종류가 둘, 전진이 하나다.

    REVERSE  조향 중립 직진 후진. 후방이 카메라 사각이라 **모르는 방향으로는 곧게만**
             간다 — 공간을 벌기 위한 후진(진입 직후, U턴의 정렬 다리)이 여기다.
    RETRACE  직전 전진 호와 **같은 조향각**으로 후진. 회전중심이 같아 그 호를 되짚어
             원점으로 돌아간다. 곡선 후진이지만 눈먼 기동이 아니다 — 방금 전진으로
             지나온 자리를 되감는 것이라 그 공간은 이미 통과가 확인됐다.
    TURN_ARC/UTURN  깊은 조향 전진.
    """
    if phase is DeadEndPhase.REVERSE:
        return DriveCommand(throttle_pct=-params.dead_end_throttle_pct,
                            steering_offset_deg=0.0)
    if phase is DeadEndPhase.RETRACE:
        return DriveCommand(throttle_pct=-params.dead_end_throttle_pct,
                            steering_offset_deg=_dead_end_steer_deg(params, direction))
    return DriveCommand(throttle_pct=params.dead_end_throttle_pct,
                        steering_offset_deg=_dead_end_steer_deg(params, direction))


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
    오른쪽으로 꺾어야 인도로 돌아온다. 오차가 정확히 0이면 좌 —
    이 기본값의 근거에 대해서는 _bypass_direction 의 ⚠️ 주석 참조(실측이 뒤집었다).
    """
    if frame.cost_profile is not None:
        half = len(frame.cost_profile) // 2
        left = _half_score(tuple(frame.cost_profile[:half]))
        right = _half_score(tuple(frame.cost_profile[-half:]))
        if left != right:
            return -1 if left < right else 1
    return 1 if frame.sidewalk_center_offset_ratio > 0.0 else -1
