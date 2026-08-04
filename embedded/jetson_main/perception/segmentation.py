"""실추론 세그멘테이션 — 마스크를 SegmentationFrame 요약으로 변환하는 Provider.

계층을 둘로 가른다 (테스트 전략의 핵심):
  1. numpy 계층: 세그 결과(클래스별 불리언 마스크) → MaskSummary(스칼라 통계 묶음).
     numpy·ultralytics가 필요하므로 보드에서만 돈다. import는 함수 안으로 미룬다.
  2. 순수 계층: MaskSummary + 임계값 → SegmentationFrame. 표준 라이브러리만 쓰므로
     개발 PC(pytest)에서 그대로 검증된다.

클래스 취급은 config ...segmentation.classes **표 하나**에서만 온다 (7클래스 우선순위 —
명세 요구). 표의 세 열이 drivable/cost/roadway이고 ClassNames.from_config가 유일한
파서다. 같은 정보를 여러 곳에 두면 어긋난다는 것이 실측된 교훈이다.

요약 규칙 (전부 비율·근접도 proxy — 절대 거리 없음, 카메라는 하향각 마운트 전제):
  - 주행가능 = classes 표의 drivable 합집합 (점자블록 포함: 블록 앞 정차 시 인도가
    가려져도 경계 소실로 오판하지 않도록 블록을 주행가능에 포함한다). 여기에
    **자차 연결**을 한 번 더 건다 — 아래 항목.
  - 자차 연결(ego): 로봇은 지금 인도 위에 있으므로 '내 발밑에서 열이 끊기지 않고
    이어진 영역'만 내 주행면이다. 세그가 길 건너 차도를 sidewalk로 오분류해도 사이에
    차도가 있어 열이 끊기므로 걸러진다 — **'차도를 인도로 본다'(0803 실외)의 방어선**
    이고, 경계 판정·중심선·무게중심이 전부 이 마스크를 쓴다. 산식은 자유공간 도달거리와
    같은 것 하나뿐이다(_ego_run_mask). 세로 끊김은 post.ego_gap_rows만큼 닫아 준다.
  - sidewalk_detected: 화면 하단 절반에서 (자차 연결) 주행가능 픽셀 비율이 임계 이상.
  - 중심 오차: 하단 절반에서 행별 주행가능 구간의 중심을 뽑아 2차 곡선(중심선)으로
    RANSAC 적합하고, 그 곡선을 lookahead_ratio 지점에서 읽는다. 적합이 실패하면
    (표본 부족·인라이어 부족) 주행가능 무게중심 x로 강등된다 — 종전 동작이 폴백으로
    남으므로 곡선을 못 잡는 장면에서도 주행이 끊기지 않는다. docs/ALGORITHMS.md N6.
  - 차도 침범: 진행 코리도(중앙 폭 × 하단 높이) 안 차도 픽셀 비율을 **두 임계**로 본다 —
    risk(코리도에 들어왔다 → 차도 이탈 선회 ESCAPE)와 critical(코리도를 덮었다 →
    즉시 ESTOP). 명세가 '차도를 만나면 좌 or 우로 방향 전환 후 인도로 주행'이므로
    정상 대응은 선회이고, ESTOP은 선회로 되돌릴 단계를 지난 경우만이다.
    '차도'는 classes 표의 roadway: true 합집합이고 crosswalk가 여기 포함된다 —
    빼면 횡단보도(= 차도 위 도색면) 앞 방어가 조용히 사라진다.
  - 횡단보도 미설치 구역(명세 5단계): 횡단보도 마스크의 최근접 행 **바로 아래 띠**
    (x 범위는 횡단보도가 걸친 폭)에 점자블록이 있는지 본다. 없으면 미설치다.
    화면 전체에서 블록을 찾으면 방금 지나온 유도선이 늘 잡혀 미설치를 영원히 검출하지
    못한다 — 띠를 좁게 잡는 것이 이 판정의 핵심이다 (_crosswalk_seam_braille_fraction).
  - 장애물(근거리 전용): 코리도 안에서 **7클래스 어디에도 잡히지 않은** 픽셀 —
    명세의 '7개 클래스 제외 인도에서 발생하는 장애물(사람·킥보드·볼라드)'이 정확히
    이것이다. ※ 종전 정의는 '주행가능도 차도도 아닌' 픽셀이라 bike_lane·caution_zone·
    alley가 장애물로 잡혔고, 자전거도로 옆을 지나는 것만으로 회피가 걸렸다. 그
    클래스들은 코스트맵 비용(우선순위 표)이 담당한다 — '피한다'와 '못 지나간다'는
    다른 판단이고, 후자로 취급하면 지나갈 수 있는 길에서 회피 기동이 돈다.
    비율이 임계 이상이면 하나의 ObstacleRegion으로 요약한다(연결성분 분해는 하지
    않는다 — 20Hz 예산과 단순성 우선, 필요해지면 그때 쪼갠다). 근접도는 **판정 밴드
    내부 기준**(0=밴드 상단, 1=밴드 하단)이다 — 화면 전체 기준이면 코리도 안
    픽셀의 하한이 (1-corridor_height_ratio)라 blocking 임계가 죽은 노브가 된다.

    **이 판정의 유효 범위는 판정 밴드뿐이다** — 카메라 0.3m·하향 10° 기준 지면
    약 0.56~1.14m다. 4~5m 앞 보행자는 화면 y≈177에 찍혀 밴드(y 288~)에 들어오지도
    못한다(0803 실외 테스트에서 사람 다리·신발을 전혀 인지하지 못한 원인). 원거리는
    아래 '자유공간' 항목이 담당하고, 둘은 대체 관계가 아니라 **병행**이다.
  - 자유공간(원거리): 각 열에서 화면 아래부터 위로 훑어 **주행가능이 끊기는 지점**을
    그 방향의 자유거리로 잡는다. 지면에 선 물체는 자기 열의 자유거리를 이웃보다 짧게
    끊고(노치), 하늘은 주행가능 구간 위쪽이라 산식에 참여하지 않는다 — 그래서 밴드를
    넓혀도 오탐이 되지 않고, 세그가 보는 만큼 멀리까지 본다. 결과는 두 곳에 얹힌다:
    코스트맵 슬롯 비용(→ 상태 전이 없이 좌우로 비껴간다)과 obstacles 목록(→ 절대
    기준을 넘으면 blocking = 회피 기동). 근거·파라미터는 FreeSpaceParams 독스트링,
    설계는 docs/ALGORITHMS.md N8.

    **'미분류 = 장애물'은 모델의 미검출과 실제 물체를 구분하지 못한다** — 이 정의의
    근본 한계다. 픽셀 비율 하나로만 판정하면 실외에서 상시 오탐이 되므로 두 개의
    기하 조건을 추가로 요구한다(둘 다 물체의 물리적 성질에서 나온다):
      1. 판정 밴드에서 화면 최하단 obstacle_bottom_margin_ratio를 제외한다.
         그 구간은 로봇 자기 몸체·그림자·극단 사각으로 세그가 가장 자주 실패하는
         영역이고, 실패가 곧 '범퍼 앞 장애물'로 잡혀 정지를 유발한다.
      2. 영역의 **세로 폭**(obstacle_height_ratio)이 밴드 높이의
         obstacle_min_height_ratio 이상이어야 한다. 지면에 서 있는 물체는 화면에서
         위아래로 뻗지만, 노면 오분류는 얇은 띠로 깔린다. 게다가 blocking은 근접도가
         높을 때만이므로(가까운 = 화면에서 큰) 실제 물체는 이 조건을 쉽게 넘긴다.
  - 점자블록: 화면 전체에서 해당 클래스 비율이 임계 이상이면 발견. 근접도는
    마스크 최하단(proximity_quantile 행) 위치의 화면 전체 비율.
  - 추론은 retina_masks=True로 원본 이미지 공간 마스크를 받는다 — 기본(letterbox
    공간)이면 패딩 행이 비율 통계를 희석하고 근접도 상한을 깎는다.

코스트맵 (docs/ALGORITHMS.md N7 — 1단계 채택):
  - 화면 하단 밴드(코리도 높이, 전체 폭)를 x축 slot_count개 슬롯으로 쪼개
    슬롯별 클래스 구성비를 재고(numpy 계층), 클래스→비용 표를 적용해
    cost_profile을 만든다(순수 계층). 차도=∞라 그 방향은 애초에 선택되지 않는다.
  - 마스크가 겹치는 픽셀은 클래스별 비용이 중복 가산될 수 있다 — 비용은 절대량이
    아니라 방향 간 비교용 휴리스틱이므로 허용한다 (연결성분 분해와 같은 이유로
    단순성 우선).

자유공간 프로파일 (docs/ALGORITHMS.md N8-lite — 원거리 장애물):
  - 판정 밴드 아래 경계에서 화면 위로 훑어, 각 열의 **연속된** 주행가능 길이를 잰다.
    연속이 핵심이다 — 물체 위로 보이는 원거리 인도를 세면 그 열은 열려 있는 것이 된다.
  - 코스트맵과 같은 슬롯 분할로 묶어(슬롯당 분위수) free_reach_ratios를 만들고,
    순수 계층이 노치(최선 슬롯 대비 부족분)를 계산해 ① 코스트맵 비용에 가산하고
    ② 절대 기준을 넘으면 ObstacleRegion(blocking)으로 보고한다.
  - 판정을 순수 계층에 둔 이유는 테스트다: numpy 계층은 슬롯당 실수 하나만 내므로
    구멍·회피 판정 전체가 개발 PC의 pytest에서 검증된다 (tests/test_free_space.py).

임계값은 전부 config.yaml perception.segmentation의 post·costmap·free_space 절에서
온다 (숫자 단일 출처).
"""

import logging
import random
import time
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Sequence, Tuple

from perception.interfaces import (ObstacleRegion, SegmentationFrame,
                                   SegmentationProvider)

logger = logging.getLogger(__name__)

# 슬롯 구성비에서 '어느 마스크에도 없는 픽셀'(인도 마스크의 구멍 = 미학습 물체)을
# 나타내는 예약 클래스명 — config의 unknown_cost가 이 항목의 비용이다.
UNKNOWN_CLASS = "__unknown__"


@dataclass(frozen=True)
class ClassNames:
    """세그 클래스 역할 묶음 — config.yaml ...segmentation.classes 표에서 파생된다.

    drivable/roadway/costs는 같은 표의 세 열이다. 표가 단일 출처인 이유는 종전에
    같은 정보(어떤 클래스를 어떻게 대할 것인가)가 config의 세 곳에 흩어져 실제로
    어긋났기 때문이다 — bike_lane이 비용표에만 있어 '주행불가 = 장애물'로 잡혔고,
    자전거도로 옆을 지나는 것만으로 회피가 걸렸다. parse_class_table이 유일한 생성자다.
    """

    drivable: Tuple[str, ...]   # 주행가능으로 칠 클래스들 (표의 drivable: true)
    roadway: Tuple[str, ...]    # 차도 취급 클래스들 (표의 roadway: true)
    braille: str                # 검사 트리거 클래스
    crosswalk: str = ""         # 미설치 판정 트리거 클래스 (명세 5단계)
    costs: Tuple[Tuple[str, float], ...] = ()   # (클래스, 통행 비용) — 표의 cost 열.
                                # dict가 아닌 이유는 frozen dataclass의 해시 가능성 유지

    @classmethod
    def from_config(cls, seg: dict) -> "ClassNames":
        """config ...segmentation 절 → 클래스 역할 묶음 (순수).

        classes 표의 한 행은 {drivable: bool, cost: float, roadway: bool(생략 시 false)}다.
        cost는 필수다 — 빠뜨리면 조용히 0(자유 통행)이 되는 것이 가장 위험한 실수이므로
        KeyError로 기동을 막는다. braille_class·crosswalk_class가 표에 없으면 즉시
        실패시킨다: 전자는 미션 대상이 주행가능에서 빠져 블록 위에서 경계 소실 정지가
        걸리고, 후자는 미설치 판정이 '항상 횡단보도 없음'으로 조용히 죽는다.
        """
        classes = seg["classes"]
        braille, crosswalk = str(seg["braille_class"]), str(seg["crosswalk_class"])
        for key, name in (("braille_class", braille), ("crosswalk_class", crosswalk)):
            if name not in classes:
                raise ValueError(f"{key} '{name}'가 classes 표에 없습니다")
        drivable, roadway, costs = [], [], []
        for name, policy in classes.items():
            if bool(policy["drivable"]):
                drivable.append(name)
            if bool(policy.get("roadway", False)):
                roadway.append(name)
            costs.append((str(name), float(policy["cost"])))
        return cls(drivable=tuple(drivable), roadway=tuple(roadway),
                   braille=braille, crosswalk=crosswalk, costs=tuple(costs))


@dataclass(frozen=True)
class CenterlineParams:
    """중심선 RANSAC 적합 파라미터 — config.yaml ...segmentation.post.centerline."""

    sample_rows: int
    min_row_width_ratio: float
    min_samples: int
    ransac_iterations: int
    inlier_threshold_ratio: float
    min_inlier_ratio: float
    lookahead_ratio: float      # 중심선을 읽을 지점 (0=바로 앞, 1=밴드 상단=가장 멀리)

    @classmethod
    def from_config(cls, centerline: dict) -> "CenterlineParams":
        return cls(
            sample_rows=int(centerline["sample_rows"]),
            min_row_width_ratio=float(centerline["min_row_width_ratio"]),
            min_samples=int(centerline["min_samples"]),
            ransac_iterations=int(centerline["ransac_iterations"]),
            inlier_threshold_ratio=float(centerline["inlier_threshold_ratio"]),
            min_inlier_ratio=float(centerline["min_inlier_ratio"]),
            lookahead_ratio=float(centerline["lookahead_ratio"]),
        )


@dataclass(frozen=True)
class PostParams:
    """마스크 → 프레임 요약 임계값 묶음 — config.yaml perception.segmentation.post."""

    sidewalk_min_fraction: float
    corridor_width_ratio: float
    corridor_height_ratio: float
    ego_gap_rows: int           # 자차 연결 판정에서 메우는 세로 끊김 최대 행 수
    roadway_risk_fraction: float        # ≥ → 차도 이탈 선회(ESCAPE)
    roadway_critical_fraction: float    # ≥ → 즉시 ESTOP (선회로는 늦은 단계)
    obstacle_min_fraction: float
    obstacle_blocking_proximity: float
    obstacle_bottom_margin_ratio: float  # 판정 밴드에서 제외할 최하단 비율 (로봇 몸체·그림자)
    obstacle_min_height_ratio: float     # 영역 세로 폭 하한 (노면 오분류 띠 배제)
    braille_min_fraction: float
    proximity_quantile: float   # 근접도 = 이 퍼센타일 행 — 최하단 노이즈 픽셀 1개가
                                # 근접도를 1로 튀게 하는 것을 막는 강건성 노브
    crosswalk_min_fraction: float               # ≥ → 횡단보도 발견
    crosswalk_seam_band_ratio: float            # 이음 띠 높이 / 화면 높이
    crosswalk_seam_braille_min_fraction: float  # 띠 내 블록 비율 미만 → 미설치
    crosswalk_seam_drivable_min_fraction: float  # 띠 내 주행가능 비율 미만 → 판정 보류
    centerline: CenterlineParams

    @classmethod
    def from_config(cls, post: dict) -> "PostParams":
        return cls(
            sidewalk_min_fraction=float(post["sidewalk_min_fraction"]),
            corridor_width_ratio=float(post["corridor_width_ratio"]),
            corridor_height_ratio=float(post["corridor_height_ratio"]),
            ego_gap_rows=int(post["ego_gap_rows"]),
            roadway_risk_fraction=float(post["roadway_risk_fraction"]),
            roadway_critical_fraction=float(post["roadway_critical_fraction"]),
            obstacle_min_fraction=float(post["obstacle_min_fraction"]),
            obstacle_blocking_proximity=float(post["obstacle_blocking_proximity"]),
            obstacle_bottom_margin_ratio=float(post["obstacle_bottom_margin_ratio"]),
            obstacle_min_height_ratio=float(post["obstacle_min_height_ratio"]),
            braille_min_fraction=float(post["braille_min_fraction"]),
            proximity_quantile=float(post["proximity_quantile"]),
            crosswalk_min_fraction=float(post["crosswalk_min_fraction"]),
            crosswalk_seam_band_ratio=float(post["crosswalk_seam_band_ratio"]),
            crosswalk_seam_braille_min_fraction=float(
                post["crosswalk_seam_braille_min_fraction"]),
            crosswalk_seam_drivable_min_fraction=float(
                post["crosswalk_seam_drivable_min_fraction"]),
            centerline=CenterlineParams.from_config(post["centerline"]),
        )


@dataclass(frozen=True)
class FreeSpaceParams:
    """자유공간 프로파일 파라미터 — config.yaml ...segmentation.free_space.

    '미분류 픽셀 = 장애물'을 **원거리까지** 쓰기 위한 재정의다. 종전 정의(판정 밴드 안
    미분류 비율)는 밴드를 위로 넓히는 순간 무너진다 — 화면 상단은 하늘·건물·원경이라
    전부 미분류이고, 그러면 화면 절반이 장애물이 된다. 그래서 밴드가 화면 하단 60~90%
    (지면 약 0.4~1.2m)로 묶여 있었고, 4~5m 앞 보행자는 평가 대상에도 들지 못했다.

    여기서는 '미분류 픽셀을 센다'가 아니라 **'주행가능이 어디까지 이어지는가'**를 잰다.
    각 열에서 화면 아래부터 위로 훑어 주행가능이 끊기는 지점이 그 방향의 자유거리이고,
    지면에 선 물체는 자기 열의 자유거리를 이웃 열보다 짧게 끊는다(노치). 하늘은 애초에
    주행가능 구간 위쪽이라 이 산식에 참여하지 않는다 — 밴드를 넓혀도 오탐이 되지 않는
    이유가 이것이고, 그래서 세그가 보는 만큼 멀리까지 볼 수 있다.
    (docs/ALGORITHMS.md N8 '가상 LaserScan'의 축소판. 절대 거리는 IPM(B3) 없이는
     나오지 않으므로 여기서 나오는 것은 전부 행 기반 유사-거리다.)

    slot_quantile: 슬롯의 자유거리를 그 슬롯 열들의 이 분위수로 잡는다. 낮을수록
        일부만 막힌 슬롯을 민감하게 잡는다(중앙값이면 사람이 두 슬롯에 걸칠 때 양쪽
        모두 다수결로 지워진다 — 실측 폭 39px vs 슬롯 43px에서 실제로 일어난다).
    notch_min_ratio: 최선 슬롯 대비 자유거리가 이 비율 이상 짧아야 노치로 본다
        (상대 판정 — '옆이 더 열려 있다'). 좌우 회피의 트리거.
    notch_cost: 노치 깊이에 곱해 코스트맵 슬롯 비용에 가산하는 계수. 이 경로가
        **상태 전이 없이 좌우로 비껴가는** 동작을 만든다 (CRUISE 코스트맵 조향).
    blocking_reach_ratio: 자유거리 비율이 이 값 미만이면 blocking (절대 판정 —
        '바로 앞에서 막혔다'). AVOID 회피 기동의 트리거.
    obstacle_min_slots: 노치가 이 개수 이상 연속돼야 장애물로 보고한다 (세그 노이즈
        한 슬롯이 회피를 유발하는 것을 막는 하한).
    """

    enabled: bool
    slot_quantile: float
    notch_min_ratio: float
    notch_cost: float
    blocking_reach_ratio: float
    obstacle_min_slots: int

    @classmethod
    def from_config(cls, free_space: dict) -> "FreeSpaceParams":
        return cls(
            enabled=bool(free_space["enabled"]),
            slot_quantile=float(free_space["slot_quantile"]),
            notch_min_ratio=float(free_space["notch_min_ratio"]),
            notch_cost=float(free_space["notch_cost"]),
            blocking_reach_ratio=float(free_space["blocking_reach_ratio"]),
            obstacle_min_slots=int(free_space["obstacle_min_slots"]),
        )


@dataclass(frozen=True)
class CostmapParams:
    """코스트맵 산출 파라미터 — config.yaml perception.segmentation.costmap."""

    slot_count: int
    unknown_cost: float
    min_class_fraction: float   # 슬롯 내 이 비율 미만 클래스는 노이즈로 무시
    class_costs: Dict[str, float]   # 클래스명 → 통행 비용 (float('inf') 허용)

    @classmethod
    def from_config(cls, costmap: dict, classes: ClassNames) -> "CostmapParams":
        """비용표는 classes 표(ClassNames.costs)에서 온다 — 우선순위의 단일 출처."""
        return cls(
            slot_count=int(costmap["slot_count"]),
            unknown_cost=float(costmap["unknown_cost"]),
            min_class_fraction=float(costmap["min_class_fraction"]),
            class_costs=dict(classes.costs),
        )


@dataclass(frozen=True)
class MaskSummary:
    """numpy 계층의 출력 — 순수 스칼라 통계만. build_frame()의 유일한 입력.

    *_offset_ratio는 -1(화면 좌끝) ~ +1(우끝), *_proximity는 0(멀다) ~ 1(범퍼 직전).
    drivable_center_offset_ratio가 None이면 주행가능 픽셀이 하나도 없는 것이다.
    obstacle_height_ratio는 장애물 영역의 세로 폭 / 판정 밴드 높이 — 지면에 선 물체와
    노면 오분류 띠를 가르는 기하 조건이다(모듈 독스트링의 '장애물' 항목 참조).
    centerline_samples는 (y_ratio, x_ratio) 쌍의 열 — y_ratio 0=화면 최하단(가장 가까움)
    ~ 1=밴드 상단(가장 멀리). 비어 있으면 중심선 적합을 건너뛰고 무게중심으로 간다.
    slot_class_fractions는 코스트맵용 슬롯별 클래스 구성비 — 하단 밴드를 x축 슬롯으로
    쪼갠 각 슬롯에서 클래스명(+UNKNOWN_CLASS) → 픽셀 비율. None이면 코스트맵
    비활성(슬롯 미산출).
    free_reach_ratios는 슬롯별 **자유공간 도달 비율** — 0(판정 시작 행에서 곧바로
    막힘) ~ 1(화면 최상단까지 주행가능이 이어짐). 코스트맵과 **같은 슬롯 분할**을 쓰므로
    비용을 원소별로 더할 수 있다. None이면 자유공간 판정 비활성.
    이 값만이 원거리(4~5m) 물체를 볼 수 있는 통로다 — 나머지 obstacle_* 필드는 전부
    판정 밴드(지면 약 0.4~1.2m) 안에서만 유효하다. 근거는 FreeSpaceParams 독스트링.
    """

    drivable_fraction: float                        # 하단 절반 기준
    drivable_center_offset_ratio: Optional[float]
    roadway_corridor_fraction: float
    obstacle_corridor_fraction: float
    obstacle_lateral_bias: float
    obstacle_proximity: float                       # 판정 밴드 내부 기준 (0=밴드 상단)
    obstacle_height_ratio: float                    # 영역 세로 폭 / 판정 밴드 높이
    braille_fraction: float                         # 화면 전체 기준
    braille_center_offset_ratio: float
    braille_proximity: float                        # 화면 전체 기준
    centerline_samples: Tuple[Tuple[float, float], ...] = ()
    slot_class_fractions: Optional[Tuple[Dict[str, float], ...]] = None
    free_reach_ratios: Optional[Tuple[float, ...]] = None
    crosswalk_fraction: float = 0.0              # 화면 전체 기준 횡단보도 비율
    crosswalk_seam_braille_fraction: Optional[float] = None  # 이음 띠 내 점자블록 비율.
                                # 띠는 '횡단보도 최근접 행 바로 아래(로봇 쪽)' 구간이고
                                # x 범위는 횡단보도가 걸친 폭이다 — 명세 5단계의 '횡단보도와
                                # sidewalk 사이'를 화면 좌표로 옮긴 것.
                                # **None = 판정 보류**(횡단보도 없음 / 띠가 화면 밖 / 띠가
                                # 인도가 아님). 0.0과 구별해야 한다 — 0.0은 '봤는데 없다'다


# ======================================================================
# 순수 계층 — MaskSummary + 임계값 → SegmentationFrame (개발 PC에서 테스트)
# ======================================================================

def _clamp_ratio(value: float) -> float:
    return max(-1.0, min(1.0, value))


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


# ---- 중심선 적합 (RANSAC + 2차 최소제곱) — 표준 라이브러리만 쓴다 ----

_RANSAC_SEED = 20260731   # 고정 시드: 같은 입력이면 항상 같은 곡선이 나온다(재현·테스트)

Sample = Tuple[float, float]        # (y_ratio, x_ratio)
Quadratic = Tuple[float, float, float]


def evaluate_centerline(coeffs: Quadratic, y_ratio: float) -> float:
    """중심선 x(y) = c0 + c1·y + c2·y² 평가. y는 0(바로 앞) ~ 1(가장 멀리)."""
    return coeffs[0] + coeffs[1] * y_ratio + coeffs[2] * y_ratio * y_ratio


def _det3(m) -> float:
    return (m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
            - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
            + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]))


def _solve3(matrix, rhs) -> Optional[Quadratic]:
    """3×3 선형계를 크라메르 공식으로 푼다. 특이 행렬이면 None → 적합 실패 → 폴백."""
    det = _det3(matrix)
    if abs(det) < 1e-12:
        return None
    solved = []
    for index in range(3):
        replaced = [list(row) for row in matrix]
        for row, value in zip(replaced, rhs):
            row[index] = value
        solved.append(_det3(replaced) / det)
    return (solved[0], solved[1], solved[2])


def _quadratic_through(points: Sequence[Sample]) -> Optional[Quadratic]:
    """표본 3개를 정확히 지나는 2차 곡선 (RANSAC 가설). y가 겹치면 None."""
    matrix = [[1.0, y, y * y] for y, _ in points]
    return _solve3(matrix, [x for _, x in points])


def _least_squares_quadratic(points: Sequence[Sample]) -> Optional[Quadratic]:
    """인라이어 전체에 대한 2차 최소제곱 (정규방정식)."""
    sums = [0.0] * 5        # Σy^0 … Σy^4
    weighted = [0.0] * 3    # Σx·y^0 … Σx·y^2
    for y, x in points:
        power = 1.0
        for order in range(5):
            sums[order] += power
            if order < 3:
                weighted[order] += x * power
            power *= y
    matrix = [[sums[0], sums[1], sums[2]],
              [sums[1], sums[2], sums[3]],
              [sums[2], sums[3], sums[4]]]
    return _solve3(matrix, weighted)


def fit_centerline(samples: Sequence[Sample], params: CenterlineParams
                   ) -> Optional[Quadratic]:
    """행별 중심 표본 → 중심선 2차 계수. 실패하면 None (호출자가 무게중심으로 강등).

    RANSAC (Fischler & Bolles, 1981): 표본 3개로 곡선을 세우고 잔차가 임계 안인
    표본(인라이어)을 센다. 가장 많이 모은 가설의 인라이어 전체로 최소제곱 재적합한다.
    교차로·갈라진 인도에서 몇 행이 엉뚱한 가지를 잡아도 다수결에서 밀려나고,
    합의가 min_inlier_ratio에 못 미치면 '적합 실패'로 처리해 폴백한다 — 억지로
    선을 하나 내놓는 것이 이 알고리즘의 가장 큰 위험이기 때문이다.
    후보 3개조는 고정 시드 난수로 뽑으므로 같은 입력이면 결과가 항상 같다.
    """
    if len(samples) < max(3, params.min_samples):
        return None
    rng = random.Random(_RANSAC_SEED)
    best: Tuple[Sample, ...] = ()
    for _ in range(params.ransac_iterations):
        trio = [samples[index] for index in rng.sample(range(len(samples)), 3)]
        model = _quadratic_through(trio)
        if model is None:
            continue
        inliers = tuple(
            sample for sample in samples
            if abs(evaluate_centerline(model, sample[0]) - sample[1])
            <= params.inlier_threshold_ratio)
        if len(inliers) > len(best):
            best = inliers
    if len(best) < max(3, params.min_inlier_ratio * len(samples)):
        return None
    return _least_squares_quadratic(best)


def corridor_bounds(height: int, width: int, post: PostParams) -> Tuple[int, int, int]:
    """진행 코리도의 픽셀 경계 (x0, x1, y0). 중앙 corridor_width_ratio 폭 × 하단
    corridor_height_ratio 높이이고, 아래 경계는 항상 화면 최하단(height - 1)이다.

    관제 오버레이(scripts/web_test.py)가 판정 영역과 **같은** 사각형을 그려야 하므로
    산식은 여기 한 곳에만 둔다 — 두 벌이면 임계값 현장 재보정 중에 화면과 판정이
    조용히 어긋난다. 비율 계산뿐이라 numpy가 필요 없는 순수 함수다.
    """
    x0 = int(round(width * (1.0 - post.corridor_width_ratio) / 2.0))
    x1 = max(x0 + 1, int(round(width * (1.0 + post.corridor_width_ratio) / 2.0)))
    y0 = int(round(height * (1.0 - post.corridor_height_ratio)))
    return x0, x1, y0


def obstacle_band_y1(height: int, y0: int, post: PostParams) -> int:
    """장애물 판정 밴드의 아래 경계(exclusive) — 코리도에서 최하단 여백을 뺀 값.

    차도 위험 판정은 코리도 전체(화면 최하단까지)를 쓰지만 장애물 판정만 이 여백을
    둔다: 화면 최하단은 로봇 몸체·그림자·극단 사각으로 세그 미검출이 몰리는 구간이고,
    그 미검출이 '범퍼 앞 장애물'로 잡히면 곧바로 회피·정지가 걸린다. 차도 오탐은
    ESTOP이라는 더 무거운 결과를 내지만 roadway는 **명시적으로 예측된 클래스**여서
    미검출이 오탐 방향으로 작동하지 않으므로 같은 여백이 필요 없다.

    corridor_bounds와 같은 이유로 산식은 여기 한 곳에만 둔다 — 관제 오버레이
    (scripts/web_test.py)가 판정과 같은 사각형을 그려야 한다.
    """
    margin = int(round((height - y0) * post.obstacle_bottom_margin_ratio))
    return max(y0 + 1, height - margin)


def build_cost_profile(slot_fractions: Tuple[Dict[str, float], ...],
                       costmap: CostmapParams) -> Tuple[float, ...]:
    """슬롯별 클래스 구성비 × 클래스→비용 표 → 방향별 통행 비용 (순수).

    슬롯 비용 = Σ (클래스 비율 × 클래스 비용). min_class_fraction 미만 비율은
    노이즈로 보고 0으로 눌러, 차도 오탐 픽셀 몇 개가 슬롯을 ∞로 만드는 것을 막는다.
    비용표에 없는 클래스는 unknown_cost로 취급한다 — 모델에 클래스를 추가했는데
    config 비용표를 안 고친 경우, 조용히 0(자유 통행)이 되는 것보다 안전하다.
    """
    profile = []
    for fractions in slot_fractions:
        cost = 0.0
        for name, fraction in fractions.items():
            if fraction < costmap.min_class_fraction:
                continue
            cost += fraction * costmap.class_costs.get(name, costmap.unknown_cost)
        profile.append(cost)
    return tuple(profile)


# ---- 자유공간 프로파일 판정 (순수 — 원거리 장애물의 유일한 통로) ----

def free_space_notches(reaches: Sequence[float]) -> Tuple[float, ...]:
    """슬롯별 노치 깊이 — 0(가장 열린 슬롯과 같다) ~ 1(완전히 막혔다).

    기준은 **그 프레임에서 가장 멀리 보이는 슬롯**이다. 절대 행 위치를 쓰지 않는 이유는
    자유거리의 상한이 장면마다 다르기 때문이다: 인도가 곧게 뻗으면 지평선까지 열리고,
    코너·오르막이면 훨씬 아래에서 끊긴다. 절대 임계를 쓰면 코너마다 전 슬롯이 장애물이
    된다. '옆이 더 열려 있는가'가 좌우 회피가 실제로 답해야 하는 질문이고, 이 상대
    기준이 정확히 그것이다.

    전 슬롯이 균등하게 막힌 경우(가로 담장·벽) 노치는 전부 0이 된다 — 상대 기준의
    구조적 한계다. 그 경우는 free_space_obstacle의 **절대** 판정
    (blocking_reach_ratio)이 잡고, 그래도 못 잡으면 종전 근거리 밴드 판정이 잡는다.
    """
    if not reaches:
        return ()
    best = max(reaches)
    if best <= 0.0:
        return tuple(1.0 for _ in reaches)   # 전 방향이 즉시 막힘
    return tuple(_clamp01((best - reach) / best) for reach in reaches)


def free_space_costs(reaches: Sequence[float], params: FreeSpaceParams
                     ) -> Tuple[float, ...]:
    """노치 → 코스트맵 슬롯에 가산할 통행 비용 (순수).

    notch_min_ratio 미만은 0으로 눌러 세그 마스크의 미세한 상단 요동이 조향에 새지
    않게 한다. 이 비용이 코스트맵에 더해지면 CRUISE의 방향 선택이 **상태 전이 없이**
    노치가 얕은 쪽으로 비껴간다 — 사용자가 요구한 '좌우 회피'의 1차 경로이고,
    회피 기동(AVOID)보다 먼저·부드럽게 작동한다.
    """
    return tuple(params.notch_cost * notch if notch >= params.notch_min_ratio else 0.0
                 for notch in free_space_notches(reaches))


def _deepest_notch_run(notches: Sequence[float], threshold: float
                       ) -> Tuple[int, int]:
    """임계를 넘는 연속 구간 중 가장 깊은 것의 [시작, 끝) 인덱스. 없으면 (0, 0).

    '가장 긴' 구간이 아니라 '최대 노치를 포함한' 구간을 고른다 — 넓고 얕은 노면
    오분류보다 좁고 깊은 실물(사람·볼라드)이 회피 대상이기 때문이다.
    """
    best_run, best_depth = (0, 0), -1.0
    index = 0
    while index < len(notches):
        if notches[index] < threshold:
            index += 1
            continue
        start = index
        while index < len(notches) and notches[index] >= threshold:
            index += 1
        depth = max(notches[start:index])
        if depth > best_depth:
            best_run, best_depth = (start, index), depth
    return best_run


def _overlaps_path(start: int, end: int, slot_count: int,
                   path_width_ratio: float) -> bool:
    """노치 구간 [start, end)이 **진행 경로**(코리도 폭)와 겹치는가.

    blocking의 추가 조건이다. 자유거리가 짧다는 것만으로는 '내 길이 막혔다'가 되지
    않는다: 인도 오른쪽 끝의 화단·담장은 항상 그 열의 자유거리를 끊지만 그건 지나가면
    될 일이다. 이 조건이 없으면 두 가지가 깨진다 —
      ① 회피가 **끝나지 않는다**. 선회로 비켜나도 그 화단은 계속 잡히므로 blocking이
         유지되고, 국면 상한이 유일한 출구가 된다(그래서 회피가 끝없이 이어진다).
      ② 인도 옆이 벽인 평범한 구간에서 상시 회피 기동이 돈다.
    경로 폭은 새 임계값이 아니라 진행 코리도(post.corridor_width_ratio)를 그대로 쓴다 —
    '내 길의 폭'이 두 곳에서 다른 값이 되지 않게.
    노치 **비용**에는 이 조건을 걸지 않는다: 경로 밖 노치도 부드럽게 비껴갈 이유는 있고,
    그쪽은 상태 전이 없는 조향이라 대가가 작다.
    """
    if slot_count <= 1:
        return True      # 슬롯이 하나면 그 슬롯이 곧 경로다 (_offset_ratio는 폭 2 이상 전제)
    limit = _clamp01(path_width_ratio)
    return any(abs(_offset_ratio(index, slot_count)) <= limit
               for index in range(start, end))


def free_space_obstacle(reaches: Sequence[float], params: FreeSpaceParams,
                        path_width_ratio: float = 1.0
                        ) -> Optional[ObstacleRegion]:
    """자유공간 프로파일 → 장애물 영역 1개 요약 (순수). 없으면 None.

    종전 밴드 판정을 **대체하지 않고 더한다** — 근거리 검출은 그대로 살리고 원거리
    검출을 얹는 것이다(둘의 유효 범위가 다르다). FSM은 obstacles 목록을 보므로
    두 경로가 각각 보고해도 계약이 깨지지 않는다.

    lateral_bias: 노치 구간의 중심 슬롯 → -1(좌끝) ~ +1(우끝). 회피 방향 판단이
        이 반대쪽을 고른다. 슬롯 중심 좌표 관례는 cost_profile과 동일하다.
    proximity: 1 − (노치 구간의 최소 자유거리). 자유거리가 짧을수록 가깝다는
        단조 관계이고, 절대 거리가 아니라 ObstacleRegion.proximity 관례의 proxy다.
    blocking: 노치 구간의 최소 자유거리가 blocking_reach_ratio 미만 **이고** 그 구간이
        진행 경로와 겹칠 때. 앞은 **절대** 기준('바로 앞이 막혔다'), 뒤는 **경로 관련성**
        (_overlaps_path — 없으면 회피가 끝나지 않는다)이다. 상대 노치만으로 회피 기동에
        들어가지 않는 이유는 그것이 '옆이 더 열렸다'는 뜻일 뿐이어서다 — 그건 비껴가면
        될 일이고, 기동까지 갈 근거는 내 길이 실제로 막혔을 때여야 한다.
    """
    if not params.enabled or len(reaches) < 1:
        return None
    notches = free_space_notches(reaches)
    start, end = _deepest_notch_run(notches, params.notch_min_ratio)
    if end - start < max(1, params.obstacle_min_slots):
        return None
    min_reach = min(reaches[start:end])
    center_slot = (start + end - 1) / 2.0
    span = len(reaches) - 1
    bias = 0.0 if span <= 0 else _offset_ratio(center_slot, len(reaches))
    return ObstacleRegion(
        lateral_bias=_clamp_ratio(bias),
        proximity=_clamp01(1.0 - min_reach),
        blocking=(min_reach < params.blocking_reach_ratio
                  and _overlaps_path(start, end, len(reaches), path_width_ratio)),
    )


def build_frame(summary: MaskSummary, post: PostParams, timestamp: float,
                costmap: Optional[CostmapParams] = None,
                free_space: Optional[FreeSpaceParams] = None) -> SegmentationFrame:
    """요약 통계에 임계값을 적용해 FSM이 소비하는 프레임을 만든다 (순수)."""
    sidewalk_detected = (summary.drivable_fraction >= post.sidewalk_min_fraction
                        and summary.drivable_center_offset_ratio is not None)

    # 중심 오차: 중심선 곡선을 lookahead 지점에서 읽는다. 적합 실패 시에만 무게중심
    # 으로 강등된다 — 무게중심은 인도가 한쪽으로 넓어지면 통째로 끌려가고 형상 정보가
    # 스칼라 1개로 뭉개진다 (docs/ALGORITHMS.md N6).
    centerline = fit_centerline(summary.centerline_samples, post.centerline)
    if centerline is not None:
        raw_offset = evaluate_centerline(centerline, post.centerline.lookahead_ratio)
    elif summary.drivable_center_offset_ratio is not None:
        raw_offset = summary.drivable_center_offset_ratio
    else:
        raw_offset = 0.0
    center_offset = _clamp_ratio(raw_offset)

    # 장애물 보고 조건은 비율 **과** 세로 폭 둘 다다 — 비율만으로는 노면 오분류
    # 띠(얇고 넓다)와 지면에 선 물체(좁고 높다)가 구분되지 않는다. 모듈 독스트링의
    # '장애물' 항목이 근거. 미검출을 장애물로 보고하지 않는 것이 여기 목적이므로
    # 조건 미달은 '장애물 없음'이지 '먼 장애물'이 아니다.
    obstacles: Tuple[ObstacleRegion, ...] = ()
    if (summary.obstacle_corridor_fraction >= post.obstacle_min_fraction
            and summary.obstacle_height_ratio >= post.obstacle_min_height_ratio):
        proximity = _clamp01(summary.obstacle_proximity)
        obstacles = (ObstacleRegion(
            lateral_bias=_clamp_ratio(summary.obstacle_lateral_bias),
            proximity=proximity,
            blocking=proximity >= post.obstacle_blocking_proximity,
        ),)

    # 자유공간 프로파일 — 원거리(밴드 밖) 장애물의 유일한 통로. 두 곳에 얹는다:
    #  ① 코스트맵 슬롯 비용 → CRUISE가 상태 전이 없이 노치를 비껴간다 (1차, 부드럽다)
    #  ② obstacles 목록   → 절대 기준을 넘으면 blocking = AVOID 회피 기동 (2차)
    # 종전 밴드 판정을 대체하지 않고 더한다 — 유효 범위가 다르다(밴드는 0.4~1.2m).
    reaches = summary.free_reach_ratios
    use_free_space = (free_space is not None and free_space.enabled
                      and reaches is not None and len(reaches) > 0)

    cost_profile = None
    if costmap is not None and summary.slot_class_fractions is not None:
        cost_profile = build_cost_profile(summary.slot_class_fractions, costmap)
        if use_free_space and len(reaches) == len(cost_profile):
            extra = free_space_costs(reaches, free_space)
            cost_profile = tuple(base + add for base, add in zip(cost_profile, extra))

    if use_free_space:
        far = free_space_obstacle(reaches, free_space,
                                  path_width_ratio=post.corridor_width_ratio)
        if far is not None:
            obstacles = obstacles + (far,)

    # 막다른 길: **모든** 슬롯의 자유거리가 blocking 절대 기준 미만 = 좌우로도 갈 길이
    # 없다. free_space_obstacle의 상대 노치 판정으로는 이 장면이 잡히지 않는다 —
    # 가로 담장·벽이면 전 슬롯이 균등하게 막혀 노치가 전부 0이 된다(free_space_notches
    # 독스트링의 명시된 한계). 그래서 같은 절대 기준을 전 슬롯에 직접 적용한다.
    all_directions_blocked = bool(
        use_free_space
        and all(reach < free_space.blocking_reach_ratio for reach in reaches))

    braille_detected = summary.braille_fraction >= post.braille_min_fraction

    # 횡단보도 미설치 판정 (명세 5단계) — 횡단보도가 보일 때만 의미가 있다.
    # 보이지 않으면 '이음 띠에 블록이 없다'가 항상 참이므로, 이 게이트가 없으면
    # 인도 한복판에서 미설치 보고가 계속 나간다.
    # seam이 None이면 **판정 보류**다 — 보고하지 않는다. '관측하지 못했다'를 '없다'로
    # 바꾸면 카메라가 하늘을 보거나 이음부를 이미 밟고 있는 프레임이 전부 미설치가
    # 된다(0804 실외 오탐의 구조적 원인).
    crosswalk_ahead = summary.crosswalk_fraction >= post.crosswalk_min_fraction
    seam = summary.crosswalk_seam_braille_fraction
    braille_missing = (crosswalk_ahead and seam is not None
                       and seam < post.crosswalk_seam_braille_min_fraction)

    return SegmentationFrame(
        timestamp=timestamp,
        sidewalk_detected=sidewalk_detected,
        sidewalk_center_offset_ratio=center_offset,
        # 차도는 두 단계다: 위험(코리도에 들어왔다 → 선회로 이탈)과 치명(코리도를
        # 덮었다 → 즉시 ESTOP). 명세의 '차도를 만나면 좌 or 우로 방향 전환'이 전자,
        # '차도 절대 접근 금지'의 최후 방어선이 후자다.
        roadway_encroachment_risk=(summary.roadway_corridor_fraction
                                   >= post.roadway_risk_fraction),
        roadway_critical=(summary.roadway_corridor_fraction
                          >= post.roadway_critical_fraction),
        obstacles=obstacles,
        braille_detected=braille_detected,
        braille_center_offset_ratio=(_clamp_ratio(summary.braille_center_offset_ratio)
                                     if braille_detected else 0.0),
        braille_proximity=_clamp01(summary.braille_proximity) if braille_detected else 0.0,
        crosswalk_ahead=crosswalk_ahead,
        braille_missing_at_crosswalk=braille_missing,
        cost_profile=cost_profile,
        all_directions_blocked=all_directions_blocked,
    )


# ======================================================================
# numpy 계층 — 클래스별 마스크 → MaskSummary (보드에서만 실행)
# ======================================================================

def summarize_class_masks(class_masks: Dict[str, "object"], shape: Tuple[int, int],
                          classes: ClassNames, post: PostParams,
                          costmap_slot_count: Optional[int] = None,
                          free_space: Optional[FreeSpaceParams] = None) -> MaskSummary:
    """클래스명 → 불리언 마스크(H×W) 사전을 스칼라 통계로 요약한다.

    class_masks의 각 값은 numpy 불리언 배열이어야 한다. shape는 마스크 공간의
    (H, W) — 모든 산출값이 비율이므로 원본 해상도와 달라도 무방하다.
    costmap_slot_count를 주면 하단 밴드(코리도 높이 × 전체 폭)를 x축 슬롯으로
    쪼갠 슬롯별 클래스 구성비도 산출한다 (코스트맵 재료 — build_cost_profile 참조).
    """
    import numpy as np  # 보드 전용 경로 — 개발 PC 테스트는 이 함수를 부르지 않는다

    height, width = shape
    empty = np.zeros((height, width), dtype=bool)

    def union(names: Tuple[str, ...]):
        merged = empty
        for name in names:
            if name in class_masks:
                merged = merged | class_masks[name]
        return merged

    raw_drivable = union(classes.drivable)
    roadway = union(classes.roadway)
    braille = class_masks.get(classes.braille, empty)
    known = union(tuple(class_masks.keys()))    # 7클래스 중 무엇이든 잡힌 픽셀

    # 진행 코리도 (경계 산식은 corridor_bounds — 관제 오버레이와 공유한다)
    x0, x1, y0 = corridor_bounds(height, width, post)
    corridor = (slice(y0, height), slice(x0, x1))
    y1 = obstacle_band_y1(height, y0, post)

    # ---- 자차 연결(ego-connected) 주행가능 — '차도를 인도로 본다'의 방어선 ----
    # 세그가 길 건너 차도를 sidewalk로 오분류하면 그 픽셀이 무게중심·중심선·경계
    # 판정에 그대로 섞여 조향을 차도 쪽으로 끌어당긴다(0803 실외 관찰). 로봇은
    # 지금 인도 위에 서 있으므로, **내 발밑에서 끊기지 않고 이어진 영역만** 내
    # 주행면이다 — 길 건너 영역은 사이에 차도가 있어 열이 끊긴다.
    # 산식은 free_reach와 같은 것 하나뿐이다(열별 하단부터의 연속 구간) — 원거리
    # 장애물 판정과 자차 연결이 같은 물리를 보고 있으므로 두 벌로 두지 않는다.
    ego_run = _ego_run_mask(np, raw_drivable, scan_from=y1 - 1,
                            gap_rows=post.ego_gap_rows)
    drivable = raw_drivable.copy()
    drivable[:y1, :] = ego_run

    # 주행가능: 하단 절반 기준 (상단은 원경이라 경계 판정에 노이즈만 보탠다)
    bottom_half = drivable[height // 2:, :]
    drivable_fraction = _fraction(bottom_half)
    drivable_center = _centroid_offset_ratio(bottom_half, x_origin=0, full_width=width)

    roadway_in = roadway[corridor]
    roadway_fraction = _fraction(roadway_in)

    # 장애물 판정 밴드는 코리도에서 최하단 여백을 뺀 구간이다 (obstacle_band_y1의
    # 독스트링이 근거 — 로봇 몸체·그림자 구간의 세그 미검출을 장애물로 읽지 않는다).
    # 근접도·세로 폭은 이 밴드 내부 행 기준으로 정규화한다: 화면 전체 기준이면
    # 밴드 안 어떤 픽셀도 (1-corridor_height_ratio) 아래로 못 내려가
    # obstacle_blocking_proximity 임계가 항상 참인 죽은 노브가 되기 때문이다.
    #
    # 장애물 = **미분류**(7클래스 어디에도 안 잡힌) 픽셀뿐이다 — 명세의 '7개 클래스
    # 제외 인도에서 발생하는 장애물(사람·킥보드·볼라드)'. 종전 정의(~drivable &
    # ~roadway)는 bike_lane·caution_zone·alley를 장애물로 잡아, 자전거도로 옆을
    # 지나는 것만으로 회피가 걸렸다. 그 클래스들은 코스트맵 비용(우선순위 표)이
    # 담당한다 — '피한다'와 '못 지나간다'는 다른 판단이다.
    obstacle_in = (~known)[(slice(y0, y1), slice(x0, x1))]
    obstacle_fraction = _fraction(obstacle_in)
    obstacle_bias, obstacle_proximity, obstacle_height = _region_position(
        np, obstacle_in, x_origin=x0, full_width=width,
        height_denominator=y1 - y0, quantile=post.proximity_quantile)

    braille_fraction = _fraction(braille)
    braille_offset = _centroid_offset_ratio(braille, x_origin=0, full_width=width)
    _, braille_proximity, _ = _region_position(
        np, braille, x_origin=0, full_width=width,
        height_denominator=height, quantile=post.proximity_quantile)

    # 횡단보도 이음 띠 — 명세 5단계(미설치 구역 판정)의 재료 2개
    crosswalk = class_masks.get(classes.crosswalk, empty)
    crosswalk_fraction = _fraction(crosswalk)
    seam_braille_fraction = _crosswalk_seam_braille_fraction(
        np, crosswalk, braille, drivable, height=height, width=width,
        band_ratio=post.crosswalk_seam_band_ratio,
        quantile=post.proximity_quantile,
        drivable_min_fraction=post.crosswalk_seam_drivable_min_fraction)

    # 코스트맵 슬롯: 조향 후보는 전체 폭 — 코리도(중앙만)와 달리 옆으로 비껴갈
    # 방향까지 평가해야 한다. 높이는 코리도와 같은 하단 밴드(원경 노이즈 배제).
    slot_fractions = None
    if costmap_slot_count is not None and costmap_slot_count > 0:
        slot_fractions = _slot_class_fractions(
            np, class_masks, band_y0=y0, height=height, width=width,
            slot_count=costmap_slot_count)

    # 자유공간 프로파일 — 코스트맵과 **같은 슬롯 분할**을 쓴다(비용을 원소별로 더한다).
    # 밴드 위쪽 전체를 훑으므로 이것만이 원거리 물체를 볼 수 있다 (FreeSpaceParams 참조).
    free_reach = None
    if (free_space is not None and free_space.enabled
            and costmap_slot_count is not None and costmap_slot_count > 0):
        free_reach = _free_reach_ratios(
            np, ego_run, width=width, slot_count=costmap_slot_count,
            quantile=free_space.slot_quantile)

    return MaskSummary(
        centerline_samples=_centerline_samples(np, drivable, height, width,
                                               post.centerline),
        drivable_fraction=drivable_fraction,
        drivable_center_offset_ratio=drivable_center,
        roadway_corridor_fraction=roadway_fraction,
        obstacle_corridor_fraction=obstacle_fraction,
        obstacle_lateral_bias=obstacle_bias,
        obstacle_proximity=obstacle_proximity,
        obstacle_height_ratio=obstacle_height,
        braille_fraction=braille_fraction,
        braille_center_offset_ratio=(braille_offset if braille_offset is not None else 0.0),
        braille_proximity=braille_proximity,
        slot_class_fractions=slot_fractions,
        free_reach_ratios=free_reach,
        crosswalk_fraction=crosswalk_fraction,
        crosswalk_seam_braille_fraction=seam_braille_fraction,
    )


def _close_vertical_gaps(np, mask, gap_rows: int):
    """세로 방향 닫힘(closing) — gap_rows 이하의 세로 끊김을 메운다 (numpy 계층).

    아래 자차 연결 판정은 '열이 끊기지 않고 이어지는가'를 보는데, 실사 마스크는
    그림자·맨홀 뚜껑·타일 이음선·세그 경계 지터로 한두 행이 쉽게 끊긴다. 그 한 행
    때문에 그 위 인도 전체가 '내 것이 아닌 영역'이 되면 판정이 쓸 수 없게 된다.
    팽창 후 침식(= 닫힘)이라 원래 True 픽셀은 반드시 살아남고, 늘어나는 것은
    끊김을 메운 부분뿐이다. gap_rows를 키우면 사람 다리 사이로도 이어져 버린다.
    """
    if gap_rows <= 0:
        return mask
    grown = mask.copy()
    for shift in range(1, gap_rows + 1):        # 세로 선 구조요소로 팽창
        grown[:-shift] |= mask[shift:]
        grown[shift:] |= mask[:-shift]
    eroded = grown.copy()
    for shift in range(1, gap_rows + 1):        # 같은 구조요소로 침식
        eroded[:-shift] &= grown[shift:]
        eroded[shift:] &= grown[:-shift]
    return mask | eroded


def _ego_run_mask(np, drivable, scan_from: int, gap_rows: int):
    """자차에서 **연속으로 이어진** 주행가능 마스크 (0~scan_from 행, numpy 계층).

    각 열에서 scan_from 행부터 위로 누적 AND를 걸어 끊기지 않은 구간만 남긴다.
    이 한 장이 두 판정의 공통 재료다:
      · 자차 연결 주행가능 — 길 건너 차도가 sidewalk로 오분류돼도 사이에 차도가
        있어 열이 끊기므로 걸러진다 ('차도를 인도로 본다'의 방어선).
      · 자유공간 도달거리 — 지면에 선 물체는 자기 열을 끊어 이웃보다 짧아진다.
    같은 물리를 보는 판정을 두 벌로 두지 않기 위해 하나로 합쳤다.

    scan_from은 화면 최하단이 아니라 **장애물 판정 밴드의 아래 경계**
    (obstacle_band_y1)다. 최하단은 로봇 자기 몸체·그림자로 세그 미검출이 몰리는
    구간이라, 거기서 시작하면 모든 열이 '즉시 막힘'으로 나온다
    (obstacle_bottom_margin_ratio의 근거를 그대로 재사용한다).
    """
    band = _close_vertical_gaps(np, drivable[:scan_from + 1, :], gap_rows)
    # 아래에서 위로 누적 AND → 연속 구간만 True. 뒤집어 원래 행 순서로 돌려놓는다
    return np.logical_and.accumulate(band[::-1, :], axis=0)[::-1, :]


def _free_reach_ratios(np, ego_run, width: int, slot_count: int,
                       quantile: float) -> Tuple[float, ...]:
    """슬롯별 자유공간 도달 비율 — _ego_run_mask의 열별 길이를 슬롯으로 묶는다.

    0 = 판정 시작 행에서 곧바로 막힘, 1 = 밴드 최상단까지 열림. 연속성은 이미
    ego_run이 보장한다(뒤쪽에 보이는 인도를 세지 않는다 — 사람 다리 위로 보이는
    원거리 인도를 세면 그 열은 열려 있는 것이 된다).

    슬롯 값은 그 슬롯 열들의 quantile 분위수다. 중앙값이 아닌 이유: 4.5m 사람의
    겉보기 폭은 약 39px이고 슬롯 폭은 640/15 ≈ 43px이라, 두 슬롯에 걸치면 양쪽 모두
    다수결에서 지워진다. 낮은 분위수는 '일부만 막힌 슬롯'을 막힌 쪽으로 읽는다 —
    노이즈 방어는 대신 슬롯 연속성(obstacle_min_slots)이 담당한다.
    """
    rows = ego_run.shape[0]
    ratios = ego_run.sum(axis=0) / float(max(1, rows))   # 열별 연속 주행가능 행 수
    edges = [round(i * width / slot_count) for i in range(slot_count)] + [width]
    return tuple(
        float(np.quantile(ratios[edges[i]:max(edges[i] + 1, edges[i + 1])], quantile))
        for i in range(slot_count))


def _crosswalk_seam_braille_fraction(np, crosswalk, braille, drivable,
                                     height: int, width: int, band_ratio: float,
                                     quantile: float,
                                     drivable_min_fraction: float) -> Optional[float]:
    """횡단보도-인도 **이음 띠** 안의 점자블록 비율 (numpy 계층). 판정 불가면 None.

    명세 5단계('횡단보도와 sidewalk 클래스 사이에 점자블록 클래스 있는지 판단')를
    화면 좌표로 옮긴 것이다. 로봇은 인도 위에 있고 횡단보도는 그 앞이므로 화면에서
    횡단보도가 위, 인도가 아래다 — 따라서 '사이'는 **횡단보도 마스크의 가장 가까운
    행(= 최대 y) 바로 아래 띠**이고, x 범위는 횡단보도가 실제로 걸친 폭이다.
    전체 화면에서 블록을 찾으면 안 된다: 로봇이 방금 지나온 유도선이 화면 하단에
    항상 있어 '있다'가 되어 버린다 — 미설치를 절대 검출하지 못하는 판정이 된다.

    최근접 행은 quantile 분위수로 잡는다(최대 y가 아니다) — 마스크 최하단의 노이즈
    픽셀 하나가 띠를 인도 쪽으로 통째로 밀어내리는 것을 막는다. x 범위도 같은 이유로
    양끝 분위수를 쓴다.

    **None(판정 보류)을 돌려주는 두 경우** — 0804 실외에서 오탐 2건의 원인이다.
    보고는 되돌릴 수 없으므로 '관측하지 못했다'를 '없다'로 바꾸지 않는다:
      ① 띠가 화면 안에 다 들어오지 않는다 — 횡단보도 최근접 행이 화면 하단에서
         band_px 안쪽이면 띠가 잘린다. 잘린 띠는 블록 비율이 구조적으로 0에 가깝다
         (로봇이 이음부를 이미 밟고 있는 순간도 여기 걸린다 — 그때 판정은 무의미하다).
      ② 띠 안에 주행가능면이 거의 없다 — 명세는 '횡단보도와 **sidewalk 사이**'다.
         띠가 차도·미분류 영역에 얹혀 있으면 애초에 그 '사이'가 아니다.
    """
    ys, xs = crosswalk.nonzero()
    if ys.size == 0:
        return None
    near_y = int(np.quantile(ys, quantile))
    band_px = max(1, int(round(height * band_ratio)))
    y_from = min(height - 1, near_y)
    y_to = min(height, near_y + band_px)             # 아래(로봇 쪽)로 band_px
    if y_to - y_from < band_px:                      # ① 띠가 화면 밖으로 잘렸다
        return None
    x_from = int(np.quantile(xs, 1.0 - quantile))
    x_to = max(x_from + 1, int(np.quantile(xs, quantile)) + 1)
    band = (slice(y_from, y_to), slice(x_from, min(width, x_to)))
    if _fraction(drivable[band]) < drivable_min_fraction:   # ② 인도가 아니다
        return None
    return _fraction(braille[band])


def _slot_class_fractions(np, class_masks: Dict[str, "object"], band_y0: int,
                          height: int, width: int, slot_count: int
                          ) -> Tuple[Dict[str, float], ...]:
    """하단 밴드를 x축 균등 슬롯으로 쪼개 슬롯별 클래스 구성비를 잰다.

    UNKNOWN_CLASS 항목은 '어느 마스크에도 없는 픽셀' 비율 — 인도 마스크의
    구멍(미학습 물체)이 여기로 잡힌다. 슬롯 경계는 균등 분할이라 마지막 슬롯이
    나머지 픽셀을 흡수한다(폭 차이 최대 1px — 비율 통계라 영향 없음).
    """
    band = slice(band_y0, height)
    known = np.zeros((height - band_y0, width), dtype=bool)
    banded = {}
    for name, mask in class_masks.items():
        cropped = mask[band, :]
        banded[name] = cropped
        known = known | cropped

    edges = [round(i * width / slot_count) for i in range(slot_count)] + [width]
    fractions = []
    for i in range(slot_count):
        columns = slice(edges[i], max(edges[i] + 1, edges[i + 1]))
        slot = {name: _fraction(cropped[:, columns])
                for name, cropped in banded.items()}
        slot[UNKNOWN_CLASS] = _fraction(~known[:, columns])
        fractions.append(slot)
    return tuple(fractions)


def _centerline_samples(np, drivable, height: int, width: int,
                        params: CenterlineParams) -> Tuple[Sample, ...]:
    """하단 절반에서 sample_rows개 행을 골라 각 행의 주행가능 구간 중심을 표본으로 낸다.

    행마다 **최장 연속 구간**을 쓴다: 길 건너 보도나 갈라진 가지가 같은 행에 조각으로
    잡혀도 본줄기를 고르기 위해서다. 폭이 min_row_width_ratio 미만인 행은 부스러기로
    보고 버린다. 점자블록은 drivable에 포함돼 있으므로(config drivable_classes)
    블록 위를 지날 때 중심선이 끊기지 않는다.
    밴드는 drivable_fraction과 같은 하단 절반이다 — 상단은 원경이라 노이즈만 보탠다.
    """
    top = height // 2
    span = (height - 1) - top
    rows = max(2, params.sample_rows)
    if span <= 0 or width <= 1:
        return ()

    min_width_px = params.min_row_width_ratio * width
    samples = []
    for index in range(rows):
        row_index = (height - 1) - int(round(span * index / (rows - 1)))
        xs = np.flatnonzero(drivable[row_index])
        if xs.size == 0:
            continue
        breaks = np.flatnonzero(np.diff(xs) > 1)          # 연속 구간 경계
        starts = np.concatenate(([0], breaks + 1))
        ends = np.concatenate((breaks, [xs.size - 1]))
        longest = int(np.argmax(ends - starts))
        left, right = float(xs[starts[longest]]), float(xs[ends[longest]])
        if right - left < min_width_px:
            continue
        samples.append((((height - 1) - row_index) / span,
                        _offset_ratio((left + right) / 2.0, width)))
    return tuple(samples)


def _fraction(mask) -> float:
    """마스크의 True 비율. 빈 슬라이스(size 0)면 0.0 — mean()이 nan을 내는 것을 막는다."""
    return float(mask.mean()) if mask.size else 0.0


def _offset_ratio(center_x: float, full_width: int) -> float:
    """픽셀 x → 화면 가로 오차 비율 -1(좌끝) ~ +1(우끝).

    이 파일 x 관례의 단일 출처다 — MaskSummary의 *_center_offset_ratio와
    obstacle_lateral_bias가 전부 이 값이고, 중심선 표본의 x도 같은 축이라야 한다.
    호출자가 full_width > 1을 보장한다.
    """
    return (center_x / (full_width - 1)) * 2.0 - 1.0


def _centroid_offset_ratio(mask, x_origin: int, full_width: int) -> Optional[float]:
    """마스크 무게중심 x → -1 ~ +1 오차 비율. 픽셀이 없으면 None."""
    xs = mask.nonzero()[1]
    if xs.size == 0 or full_width <= 1:
        return None
    return _offset_ratio(float(xs.mean()) + x_origin, full_width)


def _region_position(np, mask, x_origin: int, full_width: int,
                     height_denominator: int, quantile: float
                     ) -> Tuple[float, float, float]:
    """영역의 (좌우 치우침, 근접도, 세로 폭 비율). 치우침은 화면 전체 x 기준,
    나머지 둘은 height_denominator 기준 — 호출자가 기준 공간(화면 전체 또는 판정
    밴드 내부)을 정한다. 근접도는 마스크의 quantile 퍼센타일 행, 세로 폭은
    최상단~최하단 행의 거리다. 픽셀이 없으면 (0.0, 0.0, 0.0)."""
    ys, xs = mask.nonzero()
    if ys.size == 0 or full_width <= 1 or height_denominator <= 1:
        return 0.0, 0.0, 0.0
    bias = _offset_ratio(float(xs.mean()) + x_origin, full_width)
    span = float(height_denominator - 1)
    proximity = float(np.quantile(ys, quantile)) / span
    height_ratio = (float(ys.max()) - float(ys.min()) + 1.0) / span
    return bias, proximity, min(1.0, height_ratio)


def _class_masks_from_result(result) -> Tuple[Dict[str, "object"], Tuple[int, int]]:
    """ultralytics 세그 결과 → (클래스명→불리언 마스크 합집합, 마스크 공간 (H, W)).

    마스크가 하나도 없으면 빈 사전 + 원본 해상도 — 요약이 전부 0이 되어
    sidewalk_detected=False(경계 소실 정지)로 흘러간다. 안전한 기본값.
    """
    import numpy as np

    if result.masks is None or len(result.masks) == 0:
        height, width = result.orig_shape[:2]
        return {}, (int(height), int(width))

    data = result.masks.data
    masks = data.cpu().numpy() if hasattr(data, "cpu") else np.asarray(data)
    cls_ids = result.boxes.cls
    cls_ids = cls_ids.cpu().numpy() if hasattr(cls_ids, "cpu") else np.asarray(cls_ids)

    merged: Dict[str, "object"] = {}
    for mask, cls_id in zip(masks, cls_ids):
        name = result.names[int(cls_id)]
        boolean = mask > 0.5
        merged[name] = (merged[name] | boolean) if name in merged else boolean
    height, width = masks.shape[1], masks.shape[2]
    return merged, (height, width)


# ======================================================================
# Provider — 카메라 1장 → 추론 1회 → 프레임 1개 (풀 방식 계약 그대로)
# ======================================================================

def _warn_class_mismatch(model, classes: ClassNames,
                         costmap: Optional[CostmapParams]) -> None:
    """config가 기대하는 클래스 이름이 실제 모델에 있는지 기동 시 1회 검사한다.

    없는 이름은 조용히 무시되므로(union은 present한 마스크만 합친다) **클래스 수가
    다른 가중치로 갈아끼우면 판정이 소리 없이 약해진다**: 6클래스 모델에 7클래스
    config를 쓰면 crosswalk 마스크가 아예 안 와서 횡단보도 앞 ESTOP과 코스트맵 ∞가
    함께 사라진다(모델 교체 시 실제로 겪는 사고라 경고를 띄운다). 반대로 모델에만
    있는 클래스는 비용표에서 unknown_cost로 떨어지므로 정보 로그로만 알린다.
    """
    names = set(getattr(model, "names", {}).values())
    if not names:
        return
    expected = (set(classes.drivable) | set(classes.roadway)
                | {classes.braille, classes.crosswalk}) - {""}
    missing = sorted(expected - names)
    if missing:
        logger.warning("[세그] config가 기대하는 클래스가 모델에 없음: %s — 해당 판정이 "
                       "조용히 비활성된다(모델 클래스: %s). config.yaml의 "
                       "drivable_classes/roadway_classes/braille_class를 모델에 맞출 것",
                       ", ".join(missing), ", ".join(sorted(names)))
    if costmap is not None:
        uncosted = sorted(names - set(costmap.class_costs))
        if uncosted:
            logger.info("[세그] 비용표에 없는 모델 클래스: %s — unknown_cost(%.2f)로 취급",
                        ", ".join(uncosted), costmap.unknown_cost)

class YoloSegmentationProvider(SegmentationProvider):
    """config perception.segmentation.model_path 실추론 Provider. 보드 전용 (ultralytics 필요).

    단일 스레드 전제: get_latest() 호출 안에서 캡처와 추론을 동기로 한다.
    스레드 분리 단계가 오면 이 클래스가 생산자 스레드가 되고 get_latest()는
    최신 프레임 버퍼 읽기로 바뀐다 — 시그니처는 유지된다 (README 참고).
    """

    def __init__(self, camera, model_path: str, imgsz: int, conf: float,
                 classes: ClassNames, post: PostParams,
                 costmap: Optional[CostmapParams] = None,
                 free_space: Optional[FreeSpaceParams] = None,
                 clock: Callable[[], float] = time.monotonic):
        from ultralytics import YOLO  # 보드 전용 — 개발 PC에서는 생성 자체를 안 한다

        self._camera = camera
        self._model = YOLO(model_path)
        _warn_class_mismatch(self._model, classes, costmap)
        self._imgsz = imgsz
        self._conf = conf
        self._classes = classes
        self._post = post
        self._costmap = costmap
        self._free_space = free_space
        self._clock = clock
        self._last_image = None

    @property
    def last_image(self):
        """직전 추론에 쓴 주행 카메라 프레임 (없으면 None).

        횡단보도 미설치 보고의 증거 사진이 이 프레임이다 — 판정 근거가 된 화면과
        보고에 실리는 사진이 같아야 하고, 보고 시점에 카메라를 다시 읽으면 그 사이
        로봇이 움직여 횡단보도가 화면에서 벗어난 사진이 올라간다.
        파손 보고는 이쪽이 아니라 하향 검사 카메라 프레임을 쓴다.
        """
        return self._last_image

    def get_latest(self) -> Optional[SegmentationFrame]:
        image = self._camera.read()
        if image is None:
            return None  # 카메라 장애 → FSM 공통 가드가 '인지 두절' 정지
        self._last_image = image

        # retina_masks=True: 마스크를 letterbox(패딩) 공간이 아닌 원본 이미지 공간으로
        # 받는다 — 패딩 행이 섞이면 비율 통계가 희석되고 근접도 상한이 깎여
        # 카메라 해상도·종횡비를 바꿀 때마다 임계 재보정이 필요해진다.
        result = self._model.predict(image, imgsz=self._imgsz, conf=self._conf,
                                     retina_masks=True, verbose=False)[0]
        class_masks, shape = _class_masks_from_result(result)
        summary = summarize_class_masks(
            class_masks, shape, self._classes, self._post,
            costmap_slot_count=(self._costmap.slot_count
                                if self._costmap is not None else None),
            free_space=self._free_space)
        return build_frame(summary, self._post, timestamp=self._clock(),
                           costmap=self._costmap, free_space=self._free_space)
