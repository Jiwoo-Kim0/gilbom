"""정지 사후 분석 (driving/stop_analysis.py) — 순수 계층.

기준값의 출처는 0805 실주행 4032 프레임이다: 실패한 회피(→DEAD_END) 21회와
성공한 회피(→CRUISE) 31회가 blocked_all·근접도·경계검출에서 갈렸다. 여기서
검증하는 것은 '그 차이를 실제로 갈라내는가'다.
"""

import pytest

from driving.stop_analysis import (STOP_STATES, Finding, analyze_stop,
                                   window_before)


def frame(t=0.0, state="CRUISE", *, blocked_all=False, blocking=False,
          sidewalk=True, roadway=False, prox=None, costs=(0.0, 0.0, 0.0),
          reason=""):
    """트레이스 한 줄 — web_test._append_trace 가 남기는 형식 그대로."""
    record = {
        "t": t, "at": "2026-08-05T13:43:00.000Z", "state": state,
        "reason": reason, "blocked_all": blocked_all, "blocking": blocking,
        "sidewalk": sidewalk, "roadway": roadway,
        "costs": None if costs is None else list(costs),
    }
    if prox is not None:
        record["obstacles"] = [{"prox": prox, "bias": 0.0, "blocking": True}]
    return record


def stop(records, *, state_from="AVOID", reason="전 방향 막힘"):
    return analyze_stop(records, state_from=state_from, state_to="IDLE",
                        reason=reason)


def codes(analysis):
    return {finding.code for finding in analysis.findings}


class TestWindow:
    def test_keeps_only_the_recent_window(self):
        records = [frame(t=t) for t in range(0, 60)]
        window = window_before(records, now=59.0, window_sec=20.0)
        assert len(window) == 21              # 39.0 ~ 59.0
        assert window[0]["t"] == 39

    def test_records_without_time_are_dropped(self):
        # 계측이 방어적으로 t 를 빼먹은 줄 — 창 계산이 죽으면 안 된다
        records = [{"state": "CRUISE"}, frame(t=10.0)]
        assert len(window_before(records, now=10.0)) == 1


class TestManualStop:
    @pytest.mark.parametrize("reason", [
        "코리도가 차도로 덮임(roadway_critical) / 수동 ESTOP 요청 (모든 상태 공통)",
        "원격 임무 취소 (IDLE 외 모든 상태 공통)",
        "원격 일시정지 요청(임무 이동 상태 공통) → 브레이크",
    ])
    def test_human_stops_are_not_diagnosed(self, reason):
        """사람이 누른 정지는 원인이 화면 앞에 있다 — 인지 탓을 하면 안 된다."""
        analysis = stop([frame(t=1.0, blocked_all=True, sidewalk=False)],
                        reason=reason)
        assert analysis.cause == "사람이 세움"
        assert analysis.findings == ()

    def test_counters_are_still_collected(self):
        # 진단은 안 해도 숫자는 남긴다 — 나중에 되짚을 때 근거가 된다
        analysis = stop([frame(t=1.0, blocked_all=True)], reason="수동 ESTOP 요청")
        assert analysis.counters["blocked_all_ratio"] == 1.0

    @pytest.mark.parametrize("reason", [
        "후진 복구 상한(같은 상한이 계속 간다) → 정지 + 수동 대기",
        "회차 타임아웃(물리적으로 갇힘) → 정지 + 수동 대기",
        "인도 경계 소실·인지 두절이 유예창 이상 지속 → 정지 + 수동 폴백 대기 ",
    ])
    def test_waiting_for_a_human_is_not_a_human_stop(self, reason):
        """'수동 대기'는 로봇이 스스로 포기한 것이다 — 분석이 가장 필요한 정지다.

        부분 문자열 '수동'으로 판정하던 때 0805 기록의 이 정지 10건이 전부
        '사람이 세움'으로 빠졌다. 정지 사유 원문을 그대로 넣어 회귀를 막는다.
        """
        records = [frame(t=float(i), blocked_all=True) for i in range(10)]
        analysis = stop(records, reason=reason)
        assert analysis.cause != "사람이 세움"
        assert "ALL_BLOCKED" in codes(analysis)


class TestBlockedStop:
    def test_all_blocked_is_detected(self):
        records = [frame(t=float(i), blocked_all=True) for i in range(10)]
        analysis = stop(records)
        assert "ALL_BLOCKED" in codes(analysis)

    def test_occasional_block_is_not_a_cause(self):
        # 회피 중 한두 프레임 막히는 것은 정상이다 — 그것까지 원인이라 하면
        # 모든 정지가 '막힘'이 되어 분류가 쓸모없어진다
        records = [frame(t=float(i), blocked_all=(i < 2)) for i in range(20)]
        assert "ALL_BLOCKED" not in codes(stop(records))

    def test_costmap_blindness_is_reported_with_the_block(self):
        """자유공간은 막혔다는데 코스트맵은 공짜 슬롯을 내놓는 상태.

        미분류 픽셀(실내 잡동사니·벽)은 클래스 표에 없어 비용이 0이다. 방향
        선택이 그 0을 보고 이미 막힌 쪽을 고르는 것이 0805 실패의 실체였다.
        """
        records = [frame(t=float(i), blocked_all=True, costs=(0.0, 0.0, 0.0))
                   for i in range(10)]
        assert "COSTMAP_BLIND" in codes(stop(records))

    def test_forbidden_slot_is_not_costmap_blindness(self):
        # ∞ 슬롯(차도)이 있으면 코스트맵은 제 일을 한 것이다 — 원인은 차도다
        records = [frame(t=float(i), blocked_all=True, costs=(0.0, None, None))
                   for i in range(10)]
        assert "COSTMAP_BLIND" not in codes(stop(records))
        assert "ROADWAY" in codes(stop(records))


class TestLateDetection:
    def test_obstacle_seen_too_close(self):
        records = [frame(t=float(i), blocking=True, prox=0.95) for i in range(10)]
        assert "LATE_BLOCKING" in codes(stop(records))

    def test_normal_distance_is_not_flagged(self):
        # 성공한 회피의 실측 중앙값(0.74) — 이건 정상이다
        records = [frame(t=float(i), blocking=True, prox=0.74) for i in range(10)]
        assert "LATE_BLOCKING" not in codes(stop(records))


class TestBoundary:
    def test_boundary_loss_is_detected(self):
        records = [frame(t=float(i), sidewalk=(i % 5 == 0)) for i in range(20)]
        assert "BOUNDARY_LOST" in codes(stop(records))

    def test_stable_boundary_is_not_flagged(self):
        records = [frame(t=float(i), sidewalk=True) for i in range(20)]
        assert "BOUNDARY_LOST" not in codes(stop(records))


class TestThrash:
    def test_repeated_avoid_to_dead_end(self):
        records = []
        for cycle in range(3):
            base = cycle * 4.0
            records += [frame(t=base, state="CRUISE"),
                        frame(t=base + 1, state="AVOID"),
                        frame(t=base + 2, state="DEAD_END")]
        analysis = stop(records)
        assert "AVOID_THRASH" in codes(analysis)
        assert analysis.counters["avoid_to_dead_end"] == 3

    def test_single_cycle_is_not_thrash(self):
        records = [frame(t=0.0, state="AVOID"), frame(t=1.0, state="DEAD_END")]
        assert "AVOID_THRASH" not in codes(stop(records))


class TestOutput:
    def test_record_is_json_ready(self):
        import json
        records = [frame(t=float(i), blocked_all=True, blocking=True, prox=0.95)
                   for i in range(10)]
        record = stop(records).to_record()
        assert json.loads(json.dumps(record, ensure_ascii=False))["cause"]
        assert record["findings"][0]["knob"]          # 손댈 자리를 반드시 짚는다

    def test_headline_lists_every_finding(self):
        records = [frame(t=float(i), blocked_all=True, blocking=True,
                         prox=0.95, sidewalk=False) for i in range(10)]
        analysis = stop(records)
        assert len(analysis.findings) >= 2
        for finding in analysis.findings:
            assert finding.headline in analysis.headline()

    def test_empty_window_says_so(self):
        # 기록이 없는데 원인을 지어내면 안 된다
        analysis = stop([])
        assert analysis.cause == "기록 없음"
        assert analysis.findings == ()

    def test_unexplained_stop_is_admitted(self):
        # 전부 정상인데 멈췄다 — '원인 불명'이 정직한 답이다
        records = [frame(t=float(i)) for i in range(10)]
        assert stop(records).cause == "원인 불명"


class TestContract:
    def test_stop_states_match_the_fsm(self):
        from driving.states import DriveState
        for name in STOP_STATES:
            assert DriveState(name)              # 오타는 여기서 죽는다

    def test_finding_without_knob_is_allowed(self):
        assert Finding("X", "머리말", "근거").to_record() == {
            "code": "X", "headline": "머리말", "evidence": "근거"}
