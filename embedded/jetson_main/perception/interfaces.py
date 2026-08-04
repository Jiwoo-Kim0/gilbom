"""인지 계약 — 세그멘테이션 프레임 데이터 타입과 공급자(Provider) 추상.

주행(FSM)이 소비하는 유일한 인지 입력 형식을 정의한다. 구현체는 둘이다 —
perception/segmentation.py의 YoloSegmentationProvider(실추론, 보드 전용)와
perception/mocks.py의 ScriptedSegmentationProvider(시나리오 재생). 원시 마스크
(주행가능/차도/장애물 영역)를 아래 요약 필드로 변환하는 책임은 Provider 쪽에 있고,
호출부(main.py, driving/)는 이 추상만 본다.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional, Tuple


@dataclass(frozen=True)
class ObstacleRegion:
    """세그멘테이션이 보고한 장애물 영역 하나의 요약.

    lateral_bias: 화면 내 좌우 치우침, -1.0(완전 좌측) ~ +1.0(완전 우측).
        AVOID의 회피 방향 선택(치우친 반대쪽으로 우회)이 소비한다. — TODO(우회 기동)
    proximity: 근접도 proxy, 0.0(멀다) ~ 1.0(범퍼 직전). 마스크 하단 위치 등으로
        인지 계층이 계산한다. 거리 센서가 없으므로 절대 거리는 제공하지 않는다.
    blocking: 진행 경로를 막는지 여부. 판정 기준(코리도·근접 임계)은 인지 계층
        책임 — 주행 계층은 이 불리언만 신뢰한다.
    """

    lateral_bias: float
    proximity: float
    blocking: bool


@dataclass(frozen=True)
class SegmentationFrame:
    """프레임 1장에 대한 세그멘테이션 결과 요약 (FSM step()의 인지 입력).

    timestamp: 프레임 생성 시각 (time.monotonic 기준, 초).
    sidewalk_detected: 인도(주행가능 영역) 경계가 보이는지. False = 경계 소실
        → 공통 가드가 즉시 브레이크로 정지시키고, 소실이 driving.perception_grace_sec
        이상 지속되면 수동 대기(IDLE)로 보낸다(한 프레임 깜빡임은 유예창이 흡수한다).
    sidewalk_center_offset_ratio: 주행가능 영역 중심의 좌우 오차,
        -1.0(중심이 화면 왼쪽 끝) ~ +1.0(오른쪽 끝). CRUISE 비례 조향이 소비.
        실추론에서는 행별 중심을 RANSAC 적합한 중심선을 lookahead 지점에서 읽은
        값이고(굽은 길을 미리 반영한다), 적합 실패 시 무게중심으로 강등된다 —
        어느 쪽이든 의미와 범위는 같으므로 주행 계층은 구분하지 않는다.
    roadway_encroachment_risk: 차도·횡단보도가 진행 코리도에 들어왔다는 판정.
        True → 공통 가드가 **차도 이탈 선회(ESCAPE)** 로 보낸다 — 정지가 아니다.
        명세: '차도는 절대 접근 금지, 차도를 만나면 좌 or 우로 방향 전환 후 인도로
        주행'. 인도 옆이 차도인 것은 정상 상황이므로, 이 판정 하나로 미션을 끝내면
        (종전 ESTOP) 오탐 한 번에 순찰이 끝난다. 판정 로직은 인지 계층 책임.
    roadway_critical: 코리도가 차도로 **덮였다**는 판정 (더 높은 임계).
        True → 즉시 ESTOP(수동 해제만). 선회로 되돌릴 단계를 이미 지났다는 뜻이고,
        '차도 절대 접근 금지'의 최후 방어선이다. 위 risk와 두 단계로 나눈 이유는
        되돌릴 수 있는 상황과 그렇지 않은 상황에 같은 대응을 하지 않기 위해서다.
    obstacles: 장애물 영역 요약 목록.

    점자블록 필드 (미션: 점자블록 결함 점검 — 세그의 braille_guide_blocks 클래스 요약):
    braille_detected: 점자블록이 화면에 보이는지. **최우선 접근(TRACE)의 유일한
        트리거**다 — 확정(디바운스)되면 근접도·정렬과 무관하게 순찰을 접고 그쪽으로
        간다. 근거는 driving/state_machine.py _handle_cruise의 게이트 주석.
    braille_center_offset_ratio: 블록 중심의 좌우 오차 (부호 관례는 위와 동일).
        TRACE가 인도 중심 오차와 근접도 가중으로 혼합해 추종한다(근접도 0에서 0 →
        enter_proximity에서 블록 쪽 비중 1). 목표 전환이 아니다 — 통째로 갈아끼우면
        화면 가장자리 블록 하나에 조향이 꺾여 인도를 벗어난다.
    braille_proximity: 근접도 proxy, 0.0(멀다) ~ 1.0(범퍼 직전) —
        ObstacleRegion.proximity와 같은 관례. 세 곳이 이 값을 읽는다: 조향 혼합
        비중(_approach_weight), 추종 스로틀(_trace_throttle_pct), 탐지 추론 스케줄
        (main.detection_due — 도착 전에는 돌리지 않는다).

    횡단보도 필드 (미션: 점자블록 **미설치 구역** 판정 — 명세 5단계):
    crosswalk_ahead: 횡단보도가 화면에 유의미한 크기로 보이는지.
    braille_missing_at_crosswalk: 횡단보도와 인도 사이 이음 띠에 점자블록이 없는지.
        True면 미설치 구역이다. 주행 계층은 이 필드를 보지 않는다 — 보고(사진 +
        GPS 태깅)는 main 루프의 일이고 주행 상태를 바꾸지 않는다. 판정 기하는
        perception/segmentation.py의 crosswalk_seam_* 항목.

    cost_profile: 시맨틱 코스트맵의 방향별 통행 비용 (docs/ALGORITHMS.md N7).
        화면 x축을 균등 슬롯으로 쪼갠 슬롯별 비용 — 인덱스 0=화면 좌끝, 마지막=우끝,
        슬롯 중심 좌표는 *_offset_ratio와 같은 -1~+1 관례. 0=선호, float('inf')=
        절대 선택 금지(차도 방향). CRUISE의 방향 선택이 소비하며, None이면 코스트맵
        없음 — 위 오차 기반 비례 조향으로 폴백한다 (mock 시나리오의 기본값).
    all_directions_blocked: 좌·정면·우 **어느 방향으로도 갈 자유공간이 없는지**
        (막다른 길). cost_profile의 ∞와는 다른 사실이다: ∞는 차도·횡단보도처럼
        '가면 안 되는' 방향이고, 이 필드는 벽·담장·공사벽처럼 '갈 수 없는' 방향이다.
        후자는 어느 클래스도 ∞가 아니어서(미분류 0.9 등) 비용만으로는 관측되지 않는다.
        AVOID 재확인이 이 필드로 회차(DEAD_END 180° K턴) 승격을 판단한다.
    """

    timestamp: float
    sidewalk_detected: bool
    sidewalk_center_offset_ratio: float = 0.0
    roadway_encroachment_risk: bool = False
    roadway_critical: bool = False
    obstacles: Tuple[ObstacleRegion, ...] = ()
    braille_detected: bool = False
    braille_center_offset_ratio: float = 0.0
    braille_proximity: float = 0.0
    crosswalk_ahead: bool = False
    braille_missing_at_crosswalk: bool = False
    cost_profile: Optional[Tuple[float, ...]] = None
    all_directions_blocked: bool = False

    def has_blocking_obstacle(self) -> bool:
        """진행을 막는 장애물이 하나라도 있는가."""
        return any(o.blocking for o in self.obstacles)


class SegmentationProvider(ABC):
    """세그멘테이션 결과 공급자 추상 — get_latest() 풀(pull) 방식.

    호출자는 매 루프 get_latest()로 최신 프레임을 당겨간다. 지금은 단일 스레드
    안에서 mock이 즉석 생성하지만, 나중에 인지가 생산자 스레드로 분리되면 이
    메서드가 '최신 프레임 버퍼 읽기'로 바뀔 뿐 시그니처는 유지된다.
    """

    @abstractmethod
    def get_latest(self) -> Optional[SegmentationFrame]:
        """가장 최신 세그멘테이션 프레임. 아직/더 이상 없으면 None."""
