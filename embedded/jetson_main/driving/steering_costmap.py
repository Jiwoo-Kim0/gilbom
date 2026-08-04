"""코스트맵 조향 — 방향 슬롯별 점수 최소화로 진행 방향을 고른다 (순수).

docs/ALGORITHMS.md N7(시맨틱 코스트맵) + B1(Context Steering)의 결합점:
슬롯 축은 각도가 아니라 인지의 정규화 image-x(-1~+1)다 — cost_profile·
*_offset_ratio가 이미 그 좌표계에 있고, 기하 정보 없이 극좌표를 흉내 내면
없는 기하를 지어내는 꼴이기 때문이다(B1 항목의 결정 그대로).

슬롯 점수 = 통행 비용                       (인지 계층이 준 cost_profile)
          + target_weight × |슬롯 − 목표|   (interest: 인도 중심/블록 추종)
          + commit_weight × |슬롯 − 직전|   (히스테리시스: 조향 채터링 방지)

∞ 비용 슬롯(차도 방향)은 후보에서 제외된다 — "차도 쪽으로 조향하지 않는 예방".
전 슬롯이 ∞면 None을 돌려주고, 정지 판단은 호출자(FSM)의 몫이다 — 이 모듈은
"어느 방향이 덜 나쁜가"만 답하고 "멈춰야 하는가"는 절대 답하지 않는다(B1 리스크
항목의 경고 그대로).
"""

import math
from typing import Optional, Sequence


def slot_center_ratio(index: int, slot_count: int) -> float:
    """슬롯 인덱스 → 슬롯 중심의 정규화 x 좌표 (-1=화면 좌끝, +1=우끝)."""
    return (index + 0.5) / slot_count * 2.0 - 1.0


def select_direction(cost_profile: Sequence[float], target_ratio: float,
                     prev_ratio: Optional[float], target_weight: float,
                     commit_weight: float) -> Optional[float]:
    """점수 최소 슬롯의 중심 좌표(-1~+1)를 고른다. 통행 가능 슬롯이 없으면 None.

    prev_ratio가 None이면(첫 틱·직전 선택 없음) 히스테리시스 항은 생략한다.
    동점이면 목표에 가까운 슬롯이 이긴다 — 좌우 대칭 비용에서 결정성을 보장한다.
    """
    best_ratio: Optional[float] = None
    best_score = math.inf
    best_target_distance = math.inf
    for index, cost in enumerate(cost_profile):
        if math.isinf(cost):
            continue
        ratio = slot_center_ratio(index, len(cost_profile))
        target_distance = abs(ratio - target_ratio)
        score = cost + target_weight * target_distance
        if prev_ratio is not None:
            score += commit_weight * abs(ratio - prev_ratio)
        if score < best_score or (score == best_score
                                  and target_distance < best_target_distance):
            best_ratio = ratio
            best_score = score
            best_target_distance = target_distance
    return best_ratio
