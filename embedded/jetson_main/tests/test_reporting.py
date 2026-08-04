"""이상 보고 — 사진 + GPS 좌표 태깅 (명세 4·5단계).

전송·웹 지도 핀(6단계)은 젯슨의 통신 코드가 이어받으므로, 여기서 고정하는 계약은
둘이다: **언제 보고하는가**(ReportGate)와 **어떤 형식으로 남기는가**(events.jsonl).
통신 코드가 그 파일을 읽으므로 필드 이름과 한 줄 = 한 건 규칙이 계약이다.

보고는 되돌릴 수 없는 외부 출력이라 주행 판단보다 엄격한 디바운스를 든다 — 한 프레임
오탐이 '이 횡단보도에 점자블록이 없다'는 기록을 남기면 지울 방법이 없다.
"""

import json

import pytest

import reporting
from driving.states import DriveState


class TestReportGate:
    """연속 프레임 확정 + 쿨다운 (순수 — 시계를 읽지 않고 now를 받는다)."""

    def gate(self, confirm: int = 3, cooldown: float = 30.0) -> reporting.ReportGate:
        return reporting.ReportGate(confirm_frames=confirm, cooldown_sec=cooldown)

    def test_single_frame_does_not_report(self):
        assert not self.gate().should_report(True, 0.0)

    def test_confirms_after_consecutive_frames(self):
        gate = self.gate(confirm=3)
        assert not gate.should_report(True, 0.0)
        assert not gate.should_report(True, 0.1)
        assert gate.should_report(True, 0.2)

    def test_streak_resets_on_negative_frame(self):
        # '연속'이 아니면 확정이 아니다 — 깜빡이는 오탐이 누적되어 보고되면 안 된다
        gate = self.gate(confirm=3)
        gate.should_report(True, 0.0)
        gate.should_report(True, 0.1)
        gate.should_report(False, 0.2)
        assert not gate.should_report(True, 0.3)
        assert not gate.should_report(True, 0.4)
        assert gate.should_report(True, 0.5)

    def test_cooldown_blocks_duplicate_reports(self):
        # 하나의 횡단보도는 접근하는 동안 수십 프레임에 걸쳐 계속 보인다 —
        # 쿨다운이 없으면 같은 지점을 수백 번 보고한다
        gate = self.gate(confirm=1, cooldown=30.0)
        assert gate.should_report(True, 0.0)
        for tick in range(1, 20):
            assert not gate.should_report(True, tick * 0.5)

    def test_reports_again_after_cooldown(self):
        gate = self.gate(confirm=1, cooldown=30.0)
        assert gate.should_report(True, 0.0)
        assert gate.should_report(True, 30.0)

    def test_confirm_restarts_after_a_report(self):
        # 쿨다운이 끝난 그 틱에 곧바로 재보고하지 않고 확정을 다시 채운다
        gate = self.gate(confirm=3, cooldown=10.0)
        for t in (0.0, 0.1, 0.2):
            reported = gate.should_report(True, t)
        assert reported
        assert not gate.should_report(True, 10.0)
        assert not gate.should_report(True, 10.1)
        assert gate.should_report(True, 10.2)

    def test_confirm_frames_zero_still_needs_one_frame(self):
        # 잘못된 설정(0)에도 '보지 않은 것을 보고'하지는 않는다
        gate = self.gate(confirm=0)
        assert gate.should_report(True, 0.0)
        assert not gate.should_report(False, 1000.0)


class TestWriteReport:
    """events.jsonl 형식 — 젯슨 통신 코드가 읽는 계약."""

    def read_events(self, directory):
        path = directory / reporting.EVENTS_FILE
        return [json.loads(line) for line in
                path.read_text(encoding="utf-8").splitlines()]

    def test_writes_one_line_per_report(self, tmp_path):
        out = str(tmp_path)
        reporting.write_report(out, reporting.KIND_DEFECT, None)
        reporting.write_report(out, reporting.KIND_MISSING, None)
        records = self.read_events(tmp_path)
        assert [r["kind"] for r in records] == [reporting.KIND_DEFECT,
                                                reporting.KIND_MISSING]

    def test_gps_fix_is_tagged(self, tmp_path):
        gps = {"fix": True, "lat": 37.501234, "lon": 127.039876, "sats": 9,
               "hdop": 1.2}
        reporting.write_report(str(tmp_path), reporting.KIND_DEFECT, gps)
        record = self.read_events(tmp_path)[0]
        assert record["fix"] is True
        assert record["lat"] == pytest.approx(37.501234)
        assert record["lon"] == pytest.approx(127.039876)
        assert record["sats"] == 9

    def test_missing_fix_still_records_the_event(self, tmp_path):
        # 좌표가 없다고 파손 사실을 버릴 이유는 없다 — 통신 코드가 fix를 보고 판단한다
        reporting.write_report(str(tmp_path), reporting.KIND_DEFECT,
                               {"fix": False, "lat": None, "lon": None})
        record = self.read_events(tmp_path)[0]
        assert record["fix"] is False
        assert record["lat"] is None

    def test_no_gps_reader_at_all(self, tmp_path):
        reporting.write_report(str(tmp_path), reporting.KIND_MISSING, None)
        record = self.read_events(tmp_path)[0]
        assert record["fix"] is False

    def test_extra_fields_are_merged(self, tmp_path):
        reporting.write_report(str(tmp_path), reporting.KIND_DEFECT, None,
                               extra={"reason": "불량 띠 2/3", "bands": 3})
        record = self.read_events(tmp_path)[0]
        assert record["reason"] == "불량 띠 2/3"
        assert record["bands"] == 3

    def test_creates_the_output_directory(self, tmp_path):
        nested = tmp_path / "reports" / "run1"
        reporting.write_report(str(nested), reporting.KIND_DEFECT, None)
        assert (nested / reporting.EVENTS_FILE).exists()

    def test_image_absent_when_not_provided(self, tmp_path):
        reporting.write_report(str(tmp_path), reporting.KIND_DEFECT, None)
        assert self.read_events(tmp_path)[0]["image"] is None

    def test_save_images_false_skips_encoding(self, tmp_path):
        # cv2가 없는 개발 PC에서도 좌표·판정 기록은 남아야 한다
        reporting.write_report(str(tmp_path), reporting.KIND_DEFECT, None,
                               image=object(), save_images=False)
        assert self.read_events(tmp_path)[0]["image"] is None

    def test_image_failure_does_not_lose_the_record(self, tmp_path):
        """사진 저장이 실패해도 보고는 남는다 — 보고 실패로 순찰이 죽으면 안 된다.

        개발 PC에는 cv2가 없으므로 이 호출은 실제로 인코딩 실패 경로를 탄다.
        """
        reporting.write_report(str(tmp_path), reporting.KIND_DEFECT, None,
                               image=object(), save_images=True)
        record = self.read_events(tmp_path)[0]
        assert record["kind"] == reporting.KIND_DEFECT
        assert record["image"] is None

    def test_unwritable_directory_returns_none_without_raising(self, tmp_path):
        # 예외를 올리면 main 루프가 죽는다 — 한 건을 놓치는 편이 낫다
        blocker = tmp_path / "blocked"
        blocker.write_text("not a directory", encoding="utf-8")
        assert reporting.write_report(str(blocker / "sub"),
                                      reporting.KIND_DEFECT, None) is None


class TestTraceSession:
    """추종 세션 계측 — 커버리지 근거(순수). 파손 보고와 별개 kind로 남는다.

    이 숫자가 답하는 질문은 하나다: 아무 보고도 안 나온 구간이 '깨끗한 구역'이었나
    '탐지가 아예 안 돌았나'. 0804 실외에서 그 구별이 안 됐다.
    """

    def test_session_spans_inspect_round_trip(self):
        # TRACE → INSPECT → TRACE는 한 세션이다 (FSM의 trace_started_at과 같은 경계)
        session = reporting.TraceSession()
        assert session.on_transition(DriveState.CRUISE, DriveState.TRACE, "발견", 0.0) is None
        session.count_detection(False)
        assert session.on_transition(DriveState.TRACE, DriveState.INSPECT, "후보", 1.0) is None
        assert session.on_transition(DriveState.INSPECT, DriveState.TRACE, "판정 완료", 3.0) is None
        session.count_detection(True)

        summary = session.on_transition(DriveState.TRACE, DriveState.CRUISE, "소실", 5.0)
        assert summary["duration_sec"] == 5.0
        assert summary["detections"] == 2 and summary["defect_candidates"] == 1
        assert summary["exit_state"] == DriveState.CRUISE.value

    def test_counts_reset_between_sessions(self):
        session = reporting.TraceSession()
        session.on_transition(DriveState.CRUISE, DriveState.TRACE, "발견", 0.0)
        session.count_detection(True)
        session.on_transition(DriveState.TRACE, DriveState.CRUISE, "소실", 1.0)

        session.on_transition(DriveState.CRUISE, DriveState.TRACE, "재발견", 2.0)
        assert session.detections == 0 and session.defect_candidates == 0

    def test_failed_inference_is_not_counted(self):
        # 추론이 못 돈 틱(None)은 '탐지했다'가 아니다 — 커버리지를 부풀리면 의미가 없다
        session = reporting.TraceSession()
        session.on_transition(DriveState.CRUISE, DriveState.TRACE, "발견", 0.0)
        session.count_detection(None)
        summary = session.on_transition(DriveState.TRACE, DriveState.IDLE, "인지 두절", 1.0)
        assert summary["detections"] == 0

    def test_inspect_exit_without_trace_closes_session(self):
        # INSPECT에서 TRACE로 돌아오지 않고 빠져도 세션은 닫힌다 (다음 세션에 누적 금지)
        session = reporting.TraceSession()
        session.on_transition(DriveState.CRUISE, DriveState.TRACE, "발견", 0.0)
        session.on_transition(DriveState.TRACE, DriveState.INSPECT, "후보", 1.0)
        summary = session.on_transition(DriveState.INSPECT, DriveState.ESTOP, "차도 위험", 2.0)
        assert summary is not None and session.entered_at is None
