"""정지 사후 분석 — '왜 멈췄나'를 주행 기록에서 되짚는다 (순수).

야외 테스트의 실제 흐름은 '한참 달리다 선다 → 사람이 다시 시작한다'의 반복이고,
그때마다 남는 것은 로그 수백 줄이다. 그 자리에서 원인을 못 짚으면 돌아와서도
못 짚는다 — 0805 실주행 36분에서 AVOID→DEAD_END 21회, DEAD_END→IDLE 10회가
났는데 로그만으로는 '막혔다'는 사실 외에 무엇을 고쳐야 하는지가 안 나왔다.

그래서 정지 한 번마다 **그 직전 창(window)의 인지 기록**을 요약해 원인을
분류하고, 손댈 config 항목을 이름으로 짚어 준다. 판단 근거는 전부 트레이스
기록(scripts/web_test.py 의 _append_trace 가 남기는 dict)이다.

계층은 이 저장소의 원칙 그대로다:
  · 여기(순수) — 기록 리스트 → 분석 결과. 파일도 장치도 모른다. pytest 가 검증한다.
  · scripts/web_test.py(계측) — 창을 모아 두었다가 정지 전이에서 이 함수를 부르고
    결과를 var/runs/<run_id>/stops.jsonl 에 남긴다.
  · scripts/analyze_run.py(오프라인) — 저장된 주행을 같은 함수로 다시 분석한다.

**제안은 제안일 뿐이다.** 값을 자동으로 바꾸지 않는다 — 임계값 하나가 실주행에서
무엇을 망가뜨리는지는 사람이 판단할 일이고, 이 모듈은 그 판단에 필요한 숫자를
모아 줄 뿐이다.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass, field
from typing import Optional, Sequence, Tuple

#: 이 상태로 들어가면 '멈췄다'로 본다. 주행이 끝나는 자리는 이 셋뿐이다
#: (DEAD_END·AVOID 는 스스로 빠져나오는 중간 상태라 정지가 아니다).
STOP_STATES: Tuple[str, ...] = ("IDLE", "ESTOP", "PAUSED")

#: 정지 직전 몇 초를 근거로 볼 것인가. 회피 한 번(avoid.timeout_sec 6초)과
#: 재정향 한 번(dead_end 후진+선회 ~7초)이 통째로 들어가는 길이여야 원인이 보인다.
WINDOW_SEC = 20.0

# ── 임계값 — 분류 기준 ────────────────────────────────────────────────────────
# 전부 0805 실주행 4032 프레임의 실측에서 나왔다. 실패한 회피와 성공한 회피가
# 갈라지는 지점을 그대로 옮긴 것이다:
#   blocked_all   실패 37.0% / 성공 9.2%
#   blocking 근접도 중앙값  실패 0.95 / 성공 0.74   (1.0 = 코앞)
#   sidewalk 검출 실패 61.7% / 성공 89.5%
_BLOCKED_RATIO = 0.30       # 창의 이 비율 이상이 전 방향 막힘 → 막혀서 선 것이다
_LATE_PROXIMITY = 0.90      # blocking 장애물이 이만큼 가까우면 '너무 늦게 봤다'
_BOUNDARY_RATIO = 0.70      # sidewalk_detected 가 이 아래면 경계를 놓치고 있었다
_THRASH_CYCLES = 2          # 창 안에서 회피→재정향이 이만큼 반복되면 헛도는 것이다


@dataclass(frozen=True)
class Finding:
    """정지 원인 하나. `knob` 은 손댈 config 경로 — 없으면 설정 문제가 아니다."""

    code: str
    headline: str
    evidence: str
    knob: Optional[str] = None

    def to_record(self) -> dict:
        record = {"code": self.code, "headline": self.headline,
                  "evidence": self.evidence}
        if self.knob:
            record["knob"] = self.knob
        return record


@dataclass(frozen=True)
class StopAnalysis:
    """정지 한 번의 분석 결과. 그대로 JSONL 한 줄이 된다."""

    at: str
    state_from: str
    state_to: str
    reason: str
    window_sec: float
    frames: int
    cause: str
    findings: Tuple[Finding, ...] = ()
    counters: dict = field(default_factory=dict)

    def to_record(self) -> dict:
        return {
            "at": self.at, "from": self.state_from, "to": self.state_to,
            "reason": self.reason, "window_sec": round(self.window_sec, 1),
            "frames": self.frames, "cause": self.cause,
            "findings": [f.to_record() for f in self.findings],
            "counters": self.counters,
        }

    def headline(self) -> str:
        """로그 한 줄용 요약 — 현장에서 화면을 못 볼 때 이것만 보고도 알아야 한다."""
        if not self.findings:
            return f"{self.cause}"
        return f"{self.cause} — " + " / ".join(f.headline for f in self.findings)


# ── 창 요약 (순수 보조) ───────────────────────────────────────────────────────

def _ratio(records: Sequence[dict], key: str) -> float:
    seen = [r for r in records if key in r]
    if not seen:
        return 0.0
    return sum(1 for r in seen if r.get(key)) / len(seen)


def _blocking_proximity(records: Sequence[dict]) -> Optional[float]:
    """창 안에서 '막는' 장애물의 근접도 중앙값. 없으면 None."""
    values = [obstacle["prox"]
              for record in records
              for obstacle in (record.get("obstacles") or [])
              if obstacle.get("blocking") and obstacle.get("prox") is not None]
    return statistics.median(values) if values else None


def _transitions(records: Sequence[dict]) -> Tuple[Tuple[str, str], ...]:
    """창 안의 상태 전이 (앞 상태, 뒤 상태) 목록."""
    pairs = []
    previous = None
    for record in records:
        state = record.get("state")
        if previous is not None and state != previous:
            pairs.append((previous, state))
        previous = state
    return tuple(pairs)


def _has_forbidden_slot(records: Sequence[dict]) -> bool:
    """코스트맵에 절대 금지(∞ → JSON null) 슬롯이 있었는가 — 차도·횡단보도다."""
    return any(c is None
               for record in records
               for c in (record.get("costs") or []))


#: 사람이 낸 정지의 사유 문자열 (driving/states.py 의 전이 사유 원문 일부).
#: **부분 문자열 '수동'으로 판정하면 안 된다** — 로봇이 스스로 포기하고 사람을
#: 기다리는 사유가 '정지 + 수동 대기'·'수동 폴백 대기'라서, 정작 분석이 가장
#: 필요한 정지가 통째로 '사람이 세움'으로 빠진다 (0805 기록 10건이 그랬다).
_HUMAN_STOP_MARKERS: Tuple[str, ...] = (
    "수동 ESTOP", "원격 임무 취소", "원격 일시정지",
)


def _is_human_stop(reason: str) -> bool:
    return any(marker in reason for marker in _HUMAN_STOP_MARKERS)


def _cheapest_slot(records: Sequence[dict]) -> Optional[float]:
    """창 안 최소 슬롯 비용의 중앙값 — '갈 만한 방향이 있었나'의 코스트맵 쪽 답."""
    minima = []
    for record in records:
        finite = [c for c in (record.get("costs") or []) if c is not None]
        if finite:
            minima.append(min(finite))
    return statistics.median(minima) if minima else None


# ── 본체 ─────────────────────────────────────────────────────────────────────

def window_before(records: Sequence[dict], now: float,
                  window_sec: float = WINDOW_SEC) -> Tuple[dict, ...]:
    """`now` 기준 최근 `window_sec` 의 기록만 잘라 온다 (t 는 monotonic 초)."""
    return tuple(r for r in records
                 if r.get("t") is not None and now - r["t"] <= window_sec)


def analyze_stop(records: Sequence[dict], *, state_from: str, state_to: str,
                 reason: str, at: str = "", window_sec: float = WINDOW_SEC
                 ) -> StopAnalysis:
    """정지 직전 창을 보고 원인을 분류한다.

    `records` 는 트레이스 기록(오래된 것이 앞). 창을 자르는 것은 호출자 몫이거나
    window_before() 다 — 여기서는 받은 것을 그대로 근거로 쓴다.

    분류는 하나(cause)지만 findings 는 여럿일 수 있다. 실제로 '늦게 본 장애물'과
    '경계 소실'은 같이 나는 일이 흔하고, 둘 중 하나만 보여 주면 나머지를 못 고친다.
    """
    records = tuple(records)
    counters = {
        "blocked_all_ratio": round(_ratio(records, "blocked_all"), 3),
        "blocking_ratio": round(_ratio(records, "blocking"), 3),
        "sidewalk_ratio": round(_ratio(records, "sidewalk"), 3),
        "roadway_ratio": round(_ratio(records, "roadway"), 3),
    }
    proximity = _blocking_proximity(records)
    if proximity is not None:
        counters["blocking_proximity_median"] = round(proximity, 2)
    cheapest = _cheapest_slot(records)
    if cheapest is not None:
        counters["cheapest_slot_median"] = round(cheapest, 2)
    pairs = _transitions(records)
    thrash = sum(1 for a, b in pairs if a == "AVOID" and b == "DEAD_END")
    if thrash:
        counters["avoid_to_dead_end"] = thrash

    # 사람이 누른 정지는 분석 대상이 아니다 — 원인은 화면 앞에 있다.
    if _is_human_stop(reason):
        return StopAnalysis(at=at, state_from=state_from, state_to=state_to,
                            reason=reason, window_sec=window_sec,
                            frames=len(records), cause="사람이 세움",
                            counters=counters)

    findings = []

    if counters["roadway_ratio"] > 0 or _has_forbidden_slot(records):
        findings.append(Finding(
            code="ROADWAY",
            headline="차도가 코리도에 들어왔다",
            evidence=f"차도 감지 프레임 {counters['roadway_ratio']:.0%}",
            knob="perception.segmentation.post.roadway_risk_fraction"))

    if counters["blocked_all_ratio"] >= _BLOCKED_RATIO:
        findings.append(Finding(
            code="ALL_BLOCKED",
            headline="전 방향 막힘",
            evidence=f"창의 {counters['blocked_all_ratio']:.0%} 프레임이 blocked_all",
            knob="perception.segmentation.post.ego_gap_rows"))
        # 자유공간은 막혔다는데 코스트맵은 공짜 슬롯을 내놓는 상태 — 방향 선택의
        # 근거가 통째로 어긋나 있다. 장애물이 표에 없는 **미분류 픽셀**이라
        # 클래스 비용이 0으로 남기 때문이고, 실내 잡동사니·벽이 전부 여기 걸린다.
        if cheapest is not None and cheapest <= 0.01 and not _has_forbidden_slot(records):
            findings.append(Finding(
                code="COSTMAP_BLIND",
                headline="코스트맵은 '뚫려 있다'고 본다(미분류 장애물)",
                evidence=f"최소 슬롯비용 중앙값 {cheapest:.2f} 인데 자유공간은 막힘",
                knob="perception.segmentation.post.obstacle_min_fraction"))

    if proximity is not None and proximity >= _LATE_PROXIMITY:
        findings.append(Finding(
            code="LATE_BLOCKING",
            headline="장애물을 코앞에서 봤다",
            evidence=f"blocking 근접도 중앙값 {proximity:.2f} (성공한 회피는 0.74)",
            knob="perception.segmentation.post.obstacle_blocking_proximity"))

    if records and counters["sidewalk_ratio"] < _BOUNDARY_RATIO:
        findings.append(Finding(
            code="BOUNDARY_LOST",
            headline="인도 경계를 자주 놓쳤다",
            evidence=f"sidewalk 검출 {counters['sidewalk_ratio']:.0%}",
            knob="perception.segmentation.post.sidewalk_min_fraction"))

    if thrash >= _THRASH_CYCLES:
        findings.append(Finding(
            code="AVOID_THRASH",
            headline=f"회피→재정향 {thrash}회 반복",
            evidence="회피가 시간만 쓰고 못 지나갔다",
            knob="driving.avoid.timeout_sec"))

    if not records:
        cause = "기록 없음"
    elif not findings:
        cause = "원인 불명"
    else:
        cause = findings[0].headline
    return StopAnalysis(at=at, state_from=state_from, state_to=state_to,
                        reason=reason, window_sec=window_sec,
                        frames=len(records), cause=cause,
                        findings=tuple(findings), counters=counters)
