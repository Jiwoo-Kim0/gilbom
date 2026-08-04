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
pytest.importorskip("cv2")

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
