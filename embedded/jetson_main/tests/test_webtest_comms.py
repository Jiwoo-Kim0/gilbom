"""웹 대시보드(scripts/web_test.py)의 통신 배선 경계 테스트.

카메라·모델이 있어야 DrivingSession을 통째로 만들 수 있으므로, 여기서는 배선이
들어간 두 메서드만 떼어 확인한다 — 실기에서만 밟히는 경로라 회귀가 조용히 난다.

fastapi는 보드에만 있다(개발 PC에는 없다) — 없으면 이 파일 전체를 건너뛴다.
"""

import importlib.util
import os
import time

import pytest

pytest.importorskip("fastapi")
cv2 = pytest.importorskip("cv2")
np = pytest.importorskip("numpy")

from driving.state_machine import StateMachine, StateMachineParams
from driving.states import BRAKE_COMMAND, DriveState
from hardware.mocks import MockDriveMotor, MockSteeringServo

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _load_web_test():
    path = os.path.join(_ROOT, "scripts", "web_test.py")
    spec = importlib.util.spec_from_file_location("webtest_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


web_test = _load_web_test()


class FakeComms:
    """CommunicationRuntime 중 세션이 실제로 부르는 것만."""

    def __init__(self, urgent=False):
        self.urgent = urgent
        self.before_calls = 0
        self.after_calls = []
        self.inspections = []
        self.missing_reports = []

    def poll_before_step(self, machine):
        self.before_calls += 1
        if self.urgent:
            machine.request_estop()
        return self.urgent

    def poll_after_step(self, machine, command, *, camera_ok=None):
        self.after_calls.append((machine.state, command, camera_ok))

    def report_inspection(self, verdict, image, captured_at):
        self.inspections.append((verdict, image, captured_at))

    def report_missing_braille(self, image, captured_at=None):
        self.missing_reports.append((image, captured_at))


class FakeWorker:
    def __init__(self, verdict, image, captured_at="2026-08-04T00:00:00.000Z"):
        self.latest_verdict = verdict
        self.latest_image = image
        self.latest_captured_at = captured_at
        self.verdict_at = time.monotonic()


class FakeVerdict:
    is_defect = True
    reason = "defect_full"
    band_count = 3
    defect_band_count = 1
    partial_count = 0


def _session(config, comms, *, manual=False):
    """카메라 없이 배선 메서드만 돌릴 수 있는 최소 세션."""
    session = web_test.DrivingSession.__new__(web_test.DrivingSession)
    session._comms = comms
    session._machine_params = StateMachineParams.from_config(config["driving"])
    session._log_transition = lambda *args: None
    session._machine = StateMachine(params=session._machine_params, now=0.0)
    servo = config["hardware"]["steering_servo"]
    session._motor = MockDriveMotor()
    session._servo = MockSteeringServo(
        center_deg=float(servo["center_deg"]),
        left_offset_limit_deg=float(servo["left_offset_limit_deg"]),
        right_offset_limit_deg=float(servo["right_offset_limit_deg"]))
    session._stop_requested = web_test.threading.Event()
    session.manual_mode = manual
    session.last_command = None
    # 보고 이력 (화면 표시용) — 보고 경로가 전부 지나간다
    session._cv2 = cv2
    session.recent_reports = []
    session._report_jpegs = {}
    session._report_seq = 0
    return session


def test_no_urgent_command_lets_the_normal_tick_continue(config):
    comms = FakeComms(urgent=False)
    session = _session(config, comms)

    assert session._consume_remote(now=1.0, period=0.0) is False
    assert comms.before_calls == 1
    assert comms.after_calls == []          # 정상 tick 은 루프 본체가 보고한다


def test_urgent_command_stops_without_waiting_for_inference(config):
    """긴급 명령은 세그 추론(수백 ms)을 기다리지 않고 그 자리에서 세운다."""
    comms = FakeComms(urgent=True)
    session = _session(config, comms)

    assert session._consume_remote(now=1.0, period=0.0) is True
    assert session._machine.state is DriveState.ESTOP
    assert session.last_command == BRAKE_COMMAND
    state, command, camera_ok = comms.after_calls[-1]
    assert state is DriveState.ESTOP        # 하드웨어 쓰기 뒤에 보고했다
    assert camera_ok is None                # 이 경로는 프레임을 읽지 않았다


def test_urgent_command_forces_manual_mode_off(config):
    """수동 모드에서도 원격 긴급정지가 먹어야 한다 — FSM 을 밟게 되돌린다."""
    comms = FakeComms(urgent=True)
    session = _session(config, comms, manual=True)

    assert session._consume_remote(now=1.0, period=0.0) is True
    assert session.manual_mode is False
    assert session._machine.state is DriveState.ESTOP


def test_defect_reports_the_unannotated_frame_to_the_server(config, tmp_path):
    """증거 사진은 배너·박스를 구운 화면용이 아니라 원본 프레임이어야 한다."""
    comms = FakeComms()
    session = _session(config, comms)
    raw = object()                          # 원본 프레임 자리 표시
    session._inspect_worker = FakeWorker(FakeVerdict(), raw)
    session._defect_gate = web_test.reporting.ReportGate(
        confirm_frames=1, cooldown_sec=0.0)
    session._report_dir = str(tmp_path)
    session._save_images = False
    session._gps = type("G", (), {"status": staticmethod(lambda: None)})()
    session.last_inspection = None

    session._record_inspection(now=time.monotonic())

    assert len(comms.inspections) == 1
    verdict, image, captured_at = comms.inspections[0]
    assert image is raw
    assert captured_at == "2026-08-04T00:00:00.000Z"
    assert session._machine  # 판정 통지까지 끝났다 (INSPECT 에 갇히지 않는다)


def test_defect_without_a_raw_frame_is_skipped_not_crashed(config, tmp_path):
    comms = FakeComms()
    session = _session(config, comms)
    session._inspect_worker = FakeWorker(FakeVerdict(), None)
    session._defect_gate = web_test.reporting.ReportGate(
        confirm_frames=1, cooldown_sec=0.0)
    session._report_dir = str(tmp_path)
    session._save_images = False
    session._gps = type("G", (), {"status": staticmethod(lambda: None)})()
    session.last_inspection = None

    session._record_inspection(now=time.monotonic())

    assert comms.inspections == []


class NormalVerdict(FakeVerdict):
    is_defect = False
    reason = "정상"
    defect_band_count = 0


def _manual_session(config, comms, tmp_path, verdict, image):
    """수동 파손 보고 경로만 돌릴 수 있는 세션 (INSPECT 정차 없이)."""
    session = _session(config, comms, manual=True)
    session._inspect_worker = FakeWorker(verdict, image)
    session._manual_defect_gate = web_test.reporting.ReportGate(
        confirm_frames=web_test.MANUAL_DEFECT_CONFIRM_FRAMES,
        cooldown_sec=web_test.MANUAL_DEFECT_COOLDOWN_SEC)
    session._manual_defect_verdict_at = 0.0
    session._report_dir = str(tmp_path)
    session._save_images = False
    session._gps = type("G", (), {"status": staticmethod(lambda: None)})()
    return session


def _tick(session, at):
    """워커가 새 판정을 낸 뒤의 루프 틱 1회."""
    session._inspect_worker.verdict_at = at
    session._report_manual_defect(now=at)


def test_manual_defect_reports_after_the_confirm_streak(config, tmp_path):
    """수동 조작 중에도 파손이 서버로 간다 — 사진은 원본 프레임이다."""
    comms = FakeComms()
    raw = object()
    session = _manual_session(config, comms, tmp_path, FakeVerdict(), raw)

    for index in range(web_test.MANUAL_DEFECT_CONFIRM_FRAMES):
        assert comms.inspections == []      # 확정 전에는 안 나간다
        _tick(session, 100.0 + 0.2 * index)

    assert len(comms.inspections) == 1
    verdict, image, captured_at = comms.inspections[0]
    assert image is raw
    assert captured_at == "2026-08-04T00:00:00.000Z"
    events = (tmp_path / "events.jsonl").read_text(encoding="utf-8")
    assert "webtest-manual" in events       # 로컬 기록도 남고 자동 경로와 구분된다


def test_manual_defect_counts_verdicts_not_loop_ticks(config, tmp_path):
    """루프(10Hz)가 워커(5Hz)보다 빠르다 — 같은 판정을 두 번 세면 확정이 무의미해진다."""
    comms = FakeComms()
    session = _manual_session(config, comms, tmp_path, FakeVerdict(), object())

    for _ in range(5):
        session._inspect_worker.verdict_at = 100.0
        session._report_manual_defect(now=100.0)

    assert comms.inspections == []


def test_manual_defect_cooldown_bounds_the_report_rate(config, tmp_path):
    """파손 블록 앞에 서 있는 동안 상시 판별은 계속 참이다 — 쿨다운이 홍수를 막는다."""
    comms = FakeComms()
    session = _manual_session(config, comms, tmp_path, FakeVerdict(), object())

    at = 100.0
    for _ in range(20):                      # 4초 — 쿨다운(5초) 안이다
        _tick(session, at)
        at += 0.2
    assert len(comms.inspections) == 1

    at += web_test.MANUAL_DEFECT_COOLDOWN_SEC
    for _ in range(web_test.MANUAL_DEFECT_CONFIRM_FRAMES):
        _tick(session, at)
        at += 0.2
    assert len(comms.inspections) == 2


def test_manual_defect_ignores_a_stale_verdict(config, tmp_path):
    """검사 카메라가 멈춘 뒤 낡은 불량 판정으로 보고하지 않는다."""
    comms = FakeComms()
    session = _manual_session(config, comms, tmp_path, FakeVerdict(), object())

    session._inspect_worker.verdict_at = 100.0
    for offset in range(web_test.MANUAL_DEFECT_CONFIRM_FRAMES):
        session._report_manual_defect(
            now=100.0 + web_test.INSPECT_STALE_SEC + 1.0 + offset)

    assert comms.inspections == []


def test_report_history_carries_the_frame_that_was_sent(config, tmp_path):
    """화면 이력에는 서버로 보낸 그 프레임이 JPEG으로 붙는다 (현장 오탐 확인용)."""
    comms = FakeComms()
    frame = np.zeros((48, 64, 3), dtype=np.uint8)
    session = _manual_session(config, comms, tmp_path, FakeVerdict(), frame)

    at = 100.0
    for _ in range(web_test.MANUAL_DEFECT_CONFIRM_FRAMES):
        _tick(session, at)
        at += 0.2

    assert len(session.recent_reports) == 1
    entry = session.recent_reports[0]
    assert entry["kind"] == web_test.reporting.KIND_DEFECT
    assert entry["camera"] == "검사 카메라"
    assert entry["server"] == "서버 전송 요청"
    assert entry["detail"] == "defect_full"
    assert entry["has_image"] and entry["bytes"] > 0
    assert session._report_jpegs[entry["seq"]][:2] == b"\xff\xd8"   # JPEG SOI


def test_report_history_without_comms_says_local_only(config, tmp_path):
    """통신을 안 켠 구성에서는 '로컬 기록만'이라고 화면에 남는다 — 조용히 숨기지 않는다."""
    session = _manual_session(config, None, tmp_path, FakeVerdict(),
                              np.zeros((48, 64, 3), dtype=np.uint8))

    at = 100.0
    for _ in range(web_test.MANUAL_DEFECT_CONFIRM_FRAMES):
        _tick(session, at)
        at += 0.2

    assert session.recent_reports[0]["server"] == "통신 꺼짐 — 로컬 기록만"


def test_report_history_drops_photos_of_evicted_entries(config, tmp_path):
    """이력에서 밀려난 건의 사진은 함께 버린다 — 주행 내내 쌓이면 메모리가 찬다."""
    session = _manual_session(config, FakeComms(), tmp_path, FakeVerdict(),
                              np.zeros((48, 64, 3), dtype=np.uint8))
    frame = np.zeros((48, 64, 3), dtype=np.uint8)

    for _ in range(web_test.REPORT_HISTORY + 3):
        session._note_report(web_test.reporting.KIND_DEFECT, "파손", frame,
                             "검사 카메라", None, None, "서버 전송 요청")

    assert len(session.recent_reports) == web_test.REPORT_HISTORY
    kept = {entry["seq"] for entry in session.recent_reports}
    assert set(session._report_jpegs) == kept
    assert session.recent_reports[0]["seq"] > session.recent_reports[-1]["seq"]  # 최신이 앞


def test_report_history_survives_an_unencodable_frame(config, tmp_path):
    """사진 인코딩이 실패해도 이력 자체는 남는다 — 보고 사실이 사진 때문에 사라지면 안 된다."""
    session = _manual_session(config, FakeComms(), tmp_path, FakeVerdict(), object())

    session._note_report(web_test.reporting.KIND_MISSING, "미설치", object(),
                         "주행 카메라", None, None, "서버 전송 요청")

    entry = session.recent_reports[0]
    assert entry["has_image"] is False and entry["bytes"] == 0


def test_manual_normal_verdict_is_not_reported(config, tmp_path):
    comms = FakeComms()
    session = _manual_session(config, comms, tmp_path, NormalVerdict(), object())

    at = 100.0
    for _ in range(5):
        _tick(session, at)
        at += 0.2

    assert comms.inspections == []
    assert not (tmp_path / "events.jsonl").exists()
