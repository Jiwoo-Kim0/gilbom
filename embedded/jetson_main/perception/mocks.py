"""인지 mock — 시나리오(타임라인) 주입이 가능한 가짜 세그멘테이션 공급자.

시나리오는 config.yaml의 mock_scenarios 절에 데이터로 정의한다(숫자는 config에만).
각 구간은 시작 후 until_sec까지 같은 프레임 내용을 반복 공급한다.
"""

import time
from dataclasses import dataclass
from typing import Callable, Optional, Sequence, Tuple

from perception.interfaces import ObstacleRegion, SegmentationFrame, SegmentationProvider


@dataclass(frozen=True)
class ScenarioStep:
    """시나리오 타임라인의 한 구간 — until_sec(경과 초)까지 이 내용이 유지된다."""

    until_sec: float
    sidewalk_detected: bool = True
    center_offset_ratio: float = 0.0
    roadway_encroachment_risk: bool = False   # → 차도 이탈 선회(ESCAPE)
    roadway_critical: bool = False            # → 즉시 ESTOP
    obstacle: Optional[ObstacleRegion] = None
    braille_detected: bool = False
    braille_center_offset_ratio: float = 0.0
    braille_proximity: float = 0.0
    crosswalk_ahead: bool = False
    braille_missing_at_crosswalk: bool = False  # → 미설치 구역 보고 (주행 상태 불변)
    cost_profile: Optional[Tuple[float, ...]] = None  # None = 코스트맵 없음(비례 조향 폴백)


class ScriptedSegmentationProvider(SegmentationProvider):
    """타임라인을 따라 프레임을 공급하는 mock. 마지막 구간이 끝나면 finished."""

    def __init__(self, steps: Sequence[ScenarioStep],
                 clock: Callable[[], float] = time.monotonic):
        if not steps:
            raise ValueError("시나리오에 구간이 하나도 없습니다")
        self._steps: Tuple[ScenarioStep, ...] = tuple(steps)
        self._clock = clock
        self._t0: Optional[float] = None
        self._finished = False

    @classmethod
    def from_config(cls, step_dicts: Sequence[dict],
                    clock: Callable[[], float] = time.monotonic
                    ) -> "ScriptedSegmentationProvider":
        """config.yaml mock_scenarios 항목(dict 목록)으로부터 생성."""
        steps = []
        for d in step_dicts:
            obstacle = None
            if d.get("obstacle") is not None:
                o = d["obstacle"]
                obstacle = ObstacleRegion(
                    lateral_bias=float(o.get("lateral_bias", 0.0)),
                    proximity=float(o.get("proximity", 0.0)),
                    blocking=bool(o.get("blocking", False)),
                )
            braille = d.get("braille") or {}
            profile = d.get("cost_profile")
            steps.append(ScenarioStep(
                until_sec=float(d["until_sec"]),
                sidewalk_detected=bool(d.get("sidewalk_detected", True)),
                center_offset_ratio=float(d.get("center_offset_ratio", 0.0)),
                roadway_encroachment_risk=bool(d.get("roadway_encroachment_risk", False)),
                roadway_critical=bool(d.get("roadway_critical", False)),
                obstacle=obstacle,
                braille_detected=bool(braille) or bool(d.get("braille_detected", False)),
                braille_center_offset_ratio=float(braille.get("center_offset_ratio", 0.0)),
                braille_proximity=float(braille.get("proximity", 0.0)),
                crosswalk_ahead=bool(d.get("crosswalk_ahead", False)),
                braille_missing_at_crosswalk=bool(
                    d.get("braille_missing_at_crosswalk", False)),
                cost_profile=(tuple(float(v) for v in profile)
                              if profile is not None else None),
            ))
        return cls(steps, clock=clock)

    @property
    def finished(self) -> bool:
        """타임라인 종료 여부 — main 루프의 종료 조건."""
        return self._finished

    def get_latest(self) -> Optional[SegmentationFrame]:
        now = self._clock()
        if self._t0 is None:
            self._t0 = now  # 첫 호출 시각이 타임라인의 0초
        elapsed = now - self._t0

        current = None
        for step in self._steps:
            if elapsed < step.until_sec:
                current = step
                break
        if current is None:
            self._finished = True
            return None

        obstacles: Tuple[ObstacleRegion, ...] = (
            (current.obstacle,) if current.obstacle is not None else ())
        return SegmentationFrame(
            timestamp=now,
            sidewalk_detected=current.sidewalk_detected,
            sidewalk_center_offset_ratio=current.center_offset_ratio,
            roadway_encroachment_risk=current.roadway_encroachment_risk,
            roadway_critical=current.roadway_critical,
            obstacles=obstacles,
            braille_detected=current.braille_detected,
            braille_center_offset_ratio=current.braille_center_offset_ratio,
            braille_proximity=current.braille_proximity,
            crosswalk_ahead=current.crosswalk_ahead,
            braille_missing_at_crosswalk=current.braille_missing_at_crosswalk,
            cost_profile=current.cost_profile,
        )
