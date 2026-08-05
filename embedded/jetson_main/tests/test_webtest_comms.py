"""웹 대시보드(scripts/web_test.py)의 통신 배선 경계 테스트.

카메라·모델이 있어야 DrivingSession을 통째로 만들 수 있으므로, 여기서는 배선이
들어간 두 메서드만 떼어 확인한다 — 실기에서만 밟히는 경로라 회귀가 조용히 난다.

fastapi는 보드에만 있다(개발 PC에는 없다) — 없으면 이 파일 전체를 건너뛴다.
"""

import importlib.util
import os
import pathlib
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


def confirm_frames(config) -> int:
    return int(config["reporting"]["defect_confirm_frames"])


def cooldown_sec(config) -> float:
    return float(config["reporting"]["defect_cooldown_sec"])


def _screen_session(config, comms, tmp_path, verdict, image, manual=True):
    """상시 파손 보고 경로만 돌릴 수 있는 세션 (INSPECT 정차 없이).

    `manual`로 모드를 고른다 — 0805 개편 이후 이 경로는 **두 모드 공통**이라,
    같은 시나리오를 자율 모드로도 돌려 비대칭이 없는지 확인해야 한다.
    임계값은 config 단일 출처에서 읽는다(모듈 상수였던 것을 옮겼다).
    """
    session = _session(config, comms, manual=manual)
    session._inspect_worker = FakeWorker(verdict, image)
    session._defect_screen_confirm = web_test.reporting.ReportGate(
        confirm_frames=confirm_frames(config), cooldown_sec=0.0)
    session._defect_gate = web_test.reporting.ReportGate(
        confirm_frames=1, cooldown_sec=cooldown_sec(config))
    session._defect_verdict_at = 0.0
    session._report_dir = str(tmp_path)
    session._save_images = False
    session._gps = type("G", (), {"status": staticmethod(lambda: None)})()
    return session


def _manual_session(config, comms, tmp_path, verdict, image):
    return _screen_session(config, comms, tmp_path, verdict, image, manual=True)


def _tick(session, at):
    """워커가 새 판정을 낸 뒤의 루프 틱 1회."""
    session._inspect_worker.verdict_at = at
    session._report_defect_from_worker(now=at)


def _confirm_streak(session, config, at=100.0, step=0.2) -> float:
    """확정에 필요한 만큼 판정을 먹인다 → 마지막 시각."""
    for _ in range(confirm_frames(config)):
        _tick(session, at)
        at += step
    return at


@pytest.mark.parametrize("manual", [True, False], ids=["수동", "자율"])
def test_screen_defect_reports_in_both_modes(config, tmp_path, manual):
    """**두 모드 모두** 파손이 서버로 간다 — 0805 자율 모드 미보고의 회귀 방어.

    종전에는 이 경로가 수동 전용이고 자율 모드는 INSPECT 정차 경로에만 의존했다.
    세그가 블록을 못 잡아 TRACE→INSPECT에 도달하지 못하면(0805 오전: TRACE 15표본 /
    INSPECT 0회) 자율 모드에서 파손이 **구조적으로 한 건도** 나갈 수 없었다.
    이 테스트가 그 비대칭이 돌아오는 것을 막는다.
    """
    comms = FakeComms()
    raw = object()
    session = _screen_session(config, comms, tmp_path, FakeVerdict(), raw,
                              manual=manual)

    at = 100.0
    for _ in range(confirm_frames(config)):
        assert comms.inspections == []      # 확정 전에는 안 나간다
        _tick(session, at)
        at += 0.2

    assert len(comms.inspections) == 1
    verdict, image, captured_at = comms.inspections[0]
    assert image is raw
    assert captured_at == "2026-08-04T00:00:00.000Z"
    events = (tmp_path / "events.jsonl").read_text(encoding="utf-8")
    # 로컬 기록의 source로 경로·모드를 가릴 수 있어야 한다 (증거 사진 블러 해석용)
    assert ("webtest-screen-manual" if manual else "webtest-screen-auto") in events


def test_screen_defect_counts_verdicts_not_loop_ticks(config, tmp_path):
    """루프(10Hz)가 워커(5Hz)보다 빠르다 — 같은 판정을 두 번 세면 확정이 무의미해진다."""
    comms = FakeComms()
    session = _manual_session(config, comms, tmp_path, FakeVerdict(), object())

    for _ in range(5):
        session._inspect_worker.verdict_at = 100.0
        session._report_defect_from_worker(now=100.0)

    assert comms.inspections == []


def test_screen_defect_cooldown_bounds_the_report_rate(config, tmp_path):
    """파손 블록 앞에 서 있는 동안 상시 판별은 계속 참이다 — 쿨다운이 홍수를 막는다."""
    comms = FakeComms()
    session = _manual_session(config, comms, tmp_path, FakeVerdict(), object())

    at = 100.0
    for _ in range(20):                      # 4초 — 쿨다운(5초) 안이다
        _tick(session, at)
        at += 0.2
    assert len(comms.inspections) == 1

    at += cooldown_sec(config)
    _confirm_streak(session, config, at=at)
    assert len(comms.inspections) == 2


def test_screen_defect_ignores_a_stale_verdict(config, tmp_path):
    """검사 카메라가 멈춘 뒤 낡은 불량 판정으로 보고하지 않는다."""
    comms = FakeComms()
    session = _manual_session(config, comms, tmp_path, FakeVerdict(), object())

    session._inspect_worker.verdict_at = 100.0
    for offset in range(confirm_frames(config)):
        session._report_defect_from_worker(
            now=100.0 + web_test.INSPECT_STALE_SEC + 1.0 + offset)

    assert comms.inspections == []


def test_inspect_does_not_double_report_after_the_screen_path(config, tmp_path):
    """두 경로가 **같은 게이트를 공유**한다 — 같은 파손이 두 번 발행되면 안 된다.

    회귀 방어: 종전 _record_inspection은 서버 전송을 게이트 밖에 두었다('디스크 정책이
    결함 유실이 되면 안 된다'). 그 근거는 보고 경로가 INSPECT 하나였을 때 성립했다 —
    상시 경로가 생긴 뒤로는 게이트를 서버까지 걸지 않으면 중복 발행이 된다.
    """
    comms = FakeComms()
    session = _screen_session(config, comms, tmp_path, FakeVerdict(), object(),
                              manual=False)
    session.last_inspection = None

    at = _confirm_streak(session, config)
    assert len(comms.inspections) == 1          # 상시 경로가 보고했다

    # 곧바로 INSPECT 정차 판정이 같은 블록을 올린다 → 공유 쿨다운이 막아야 한다
    session._inspect_worker.verdict_at = at
    session._record_inspection(now=at)
    assert len(comms.inspections) == 1          # 여전히 1건


def test_the_screen_report_call_site_is_not_mode_gated():
    """루프의 **호출 지점**이 모드 조건 안에 있으면 안 된다 — 이게 원래 버그였다.

    위 테스트들은 `_report_defect_from_worker`를 직접 부르므로 함수의 동작만 본다.
    0805에 깨져 있던 것은 함수가 아니라 **호출 조건**(`if self.manual_mode:`)이었고,
    그래서 함수 단위 테스트로는 잡히지 않는다. 루프 본체는 카메라·스레드가 필요해
    이 하니스에서 구동할 수 없으므로, 조건 자체를 구문으로 잠근다.

    미설치 보고가 프레임만 보고 나가는 것과 같은 등급이어야 한다는 규칙의 표현이다.
    """
    import ast

    source = pathlib.Path(web_test.__file__).read_text(encoding="utf-8")
    tree = ast.parse(source)

    def calls_screen_report(node) -> bool:
        return any(isinstance(inner, ast.Attribute)
                   and inner.attr == "_report_defect_from_worker"
                   for inner in ast.walk(node))

    assert calls_screen_report(tree), "상시 보고 경로가 루프에서 호출되지 않는다"

    for node in ast.walk(tree):
        if not isinstance(node, ast.If):
            continue
        test_src = ast.unparse(node.test)
        if "manual_mode" not in test_src:
            continue
        # 모드 조건 안(then/else 어디든)에 상시 보고 호출이 있으면 비대칭이 돌아온 것
        for branch in (node.body, node.orelse):
            for statement in branch:
                assert not calls_screen_report(statement), (
                    f"파손 상시 보고가 모드 조건 안에 있다: if {test_src}")


def test_inspect_still_reports_when_the_screen_path_did_not(config, tmp_path):
    """상시 경로가 확정 전이면 INSPECT 정차 보고는 그대로 나간다 — 게이트 공유가
    정차 경로를 죽이면 안 된다 (더 깨끗한 사진을 내는 쪽이다)."""
    comms = FakeComms()
    session = _screen_session(config, comms, tmp_path, FakeVerdict(), object(),
                              manual=False)
    session.last_inspection = None

    _tick(session, 100.0)                       # 1프레임만 — 확정 전이다
    assert comms.inspections == []

    session._inspect_worker.verdict_at = 100.5
    session._record_inspection(now=100.5)
    assert len(comms.inspections) == 1
    events = (tmp_path / "events.jsonl").read_text(encoding="utf-8")
    assert "webtest-inspect" in events


def test_report_history_carries_the_frame_that_was_sent(config, tmp_path):
    """화면 이력에는 서버로 보낸 그 프레임이 JPEG으로 붙는다 (현장 오탐 확인용)."""
    comms = FakeComms()
    frame = np.zeros((48, 64, 3), dtype=np.uint8)
    session = _manual_session(config, comms, tmp_path, FakeVerdict(), frame)

    _confirm_streak(session, config)

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

    _confirm_streak(session, config)

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
