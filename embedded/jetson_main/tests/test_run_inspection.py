"""run_inspection(main.py)의 검사 캡처 순서 — 낡은 프레임 폐기(flush) 검증.

검사 카메라는 INSPECT 때만 읽히므로 V4L2 내부 버퍼(BUFFERSIZE=1)에 직전 검사 직후
채워진 낡은 프레임이 남는다. 버리지 않으면 블록 N 보고에 블록 N-1 사진이 실린다.
run_inspection은 duck-typing이라 cv2·ultralytics 없이 스텁으로 검증된다 —
프레임은 정체가 필요 없어 평범한 sentinel 문자열을 쓴다.
"""

import pytest

from driving.state_machine import StateMachine, StateMachineParams
from driving.states import DriveState
import reporting
from main import detection_due, run_detection, run_inspection
from perception.braille import BrailleVerdict
from perception.interfaces import SegmentationFrame


class StubCamera:
    """지정한 순서대로 프레임을 돌려주는 카메라. 시퀀스가 끝나면 None(캡처 실패)."""

    def __init__(self, frames):
        self._frames = list(frames)
        self.reads = 0

    def read(self):
        self.reads += 1
        return self._frames.pop(0) if self._frames else None


class StubInspector:
    """판정 대신 어떤 이미지를 받았는지 기록한다."""

    def __init__(self, is_defect: bool = False):
        self.inspected = []
        self._is_defect = is_defect

    def inspect(self, image):
        self.inspected.append(image)
        return BrailleVerdict(timestamp=0.0, is_defect=self._is_defect,
                              reason="스텁 판정", band_count=1,
                              defect_band_count=1 if self._is_defect else 0,
                              partial_count=0)


class StubMachine:
    def __init__(self):
        self.done_calls = 0
        self.defect_calls = 0
        self.seen_calls = 0

    def notify_inspection_done(self):
        self.done_calls += 1

    def notify_defect_found(self):
        self.defect_calls += 1

    def notify_braille_seen(self):
        self.seen_calls += 1


class TestTraceScreenReports:
    """상시 탐지 경로가 **직접 보고한다** — 0805 자율 모드 미보고의 수정.

    종전에는 파손 보고가 INSPECT 정차 경로에만 있었다. 그래서 세그가 블록을 못 잡아
    TRACE→INSPECT에 도달하지 못하면 파손을 눈으로 보고 있어도 한 건도 보고되지 않았다
    (0805 오전 실측: TRACE 15표본 / INSPECT 0회). 미설치 보고가 FSM과 무관하게 나가는
    것과 같은 등급으로 맞춘 것이 이 경로다.
    """

    def gates(self, params_confirm: int):
        """(확정 게이트, 공유 출구 게이트) — main.py의 조립과 같은 모양."""
        return (reporting.ReportGate(confirm_frames=params_confirm, cooldown_sec=0.0),
                reporting.ReportGate(confirm_frames=1, cooldown_sec=5.0))

    def collect(self):
        reports = []
        return reports, (lambda verdict, image, captured_at, source:
                         reports.append((image, source)))

    def test_reports_after_the_confirm_streak(self):
        confirm, _ = self.gates(2)
        reports, on_defect = self.collect()
        machine = StubMachine()
        camera = StubCamera(["frame0", "frame1"])
        inspector = StubInspector(is_defect=True)

        run_detection(camera, inspector, machine, screen_confirm=confirm,
                      on_defect=on_defect, clock=lambda: 100.0)
        assert reports == []                      # 1프레임으로는 보고하지 않는다
        run_detection(camera, inspector, machine, screen_confirm=confirm,
                      on_defect=on_defect, clock=lambda: 100.2)
        assert [source for _, source in reports] == ["trace-screen"]
        assert reports[0][0] == "frame1"          # 판정에 쓴 그 원본 프레임이다
        assert machine.defect_calls == 2          # 정차 요청은 매번 나간다

    def test_normal_verdict_resets_the_streak(self):
        """정상 판정도 게이트에 먹여야 한다 — 안 먹이면 띄엄띄엄 잡힌 오탐이 확정된다."""
        confirm, _ = self.gates(2)
        reports, on_defect = self.collect()
        machine = StubMachine()

        run_detection(StubCamera(["a"]), StubInspector(is_defect=True), machine,
                      screen_confirm=confirm, on_defect=on_defect,
                      clock=lambda: 100.0)
        run_detection(StubCamera(["b"]), StubInspector(is_defect=False), machine,
                      screen_confirm=confirm, on_defect=on_defect,
                      clock=lambda: 100.2)       # 끊겼다 → streak 리셋
        run_detection(StubCamera(["c"]), StubInspector(is_defect=True), machine,
                      screen_confirm=confirm, on_defect=on_defect,
                      clock=lambda: 100.4)
        assert reports == []                      # 연속 2회가 아니므로 확정 안 됨

    def test_without_a_gate_it_only_notifies(self):
        """게이트를 안 주면 종전처럼 통지만 한다 — 보고 정책을 모르는 호출자용 기본값."""
        machine = StubMachine()
        result = run_detection(StubCamera(["frame"]), StubInspector(is_defect=True),
                              machine)
        assert result is True
        assert machine.defect_calls == 1
        assert machine.seen_calls == 1             # 블록을 봤으니 추종 유지 신호도 낸다

    def test_the_loop_wires_the_report_hook(self):
        """main 루프가 run_detection에 **보고 훅을 실제로 넘기는지** 구문으로 확인한다.

        위 테스트들은 run_detection에 훅을 직접 넘기므로 함수의 동작만 본다. 0805에
        비어 있던 것은 함수가 아니라 **호출 지점의 인자**였고(보고 훅이 없어서 상시
        경로가 통지만 했다), 그건 함수 단위 테스트로 잡히지 않는다. main 루프는
        카메라·모터가 필요해 이 하니스에서 구동할 수 없으므로 배선을 구문으로 잠근다.
        """
        import ast
        import pathlib

        import main as main_module

        tree = ast.parse(pathlib.Path(main_module.__file__).read_text(encoding="utf-8"))
        wired = [
            node for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name) and node.func.id == "run_detection"
            and {kw.arg for kw in node.keywords} >= {"screen_confirm", "on_defect"}
        ]
        assert wired, "main 루프가 run_detection에 보고 훅을 넘기지 않는다 (통지만 한다)"

    def test_shared_gate_blocks_the_inspect_path_afterwards(self):
        """상시 경로가 보고한 뒤 INSPECT 경로는 **같은 게이트**에 막힌다 (중복 방지)."""
        confirm, shared = self.gates(1)
        reports, on_defect = self.collect()

        def gated(verdict, image, captured_at, source="inspect"):
            if shared.should_report(True, 100.0):
                reports.append((image, source))

        run_detection(StubCamera(["moving"]), StubInspector(is_defect=True),
                      StubMachine(), screen_confirm=confirm, on_defect=gated,
                      clock=lambda: 100.0)
        assert [source for _, source in reports] == ["trace-screen"]

        run_inspection(StubCamera(["stopped"]), StubInspector(is_defect=True),
                       StubMachine(), on_defect=gated)
        assert len(reports) == 1                  # 쿨다운이 두 번째를 막았다


class TestCaptureFlush:
    def test_flush_discards_stale_frames(self):
        # flush=N이면 판정에 쓰이는 것은 N+1번째 프레임이다
        camera = StubCamera(["stale0", "stale1", "stale2", "fresh"])
        inspector, machine = StubInspector(), StubMachine()
        run_inspection(camera, inspector, machine, flush_frames=3)
        assert inspector.inspected == ["fresh"]
        assert camera.reads == 4
        assert machine.done_calls == 1

    def test_zero_flush_uses_first_frame(self):
        # 기본값(0)은 종전 동작 그대로 — flush는 켜야만 도는 경로다
        camera = StubCamera(["first", "second"])
        inspector = StubInspector()
        run_inspection(camera, inspector, StubMachine())
        assert inspector.inspected == ["first"]
        assert camera.reads == 1

    def test_flush_ignores_failed_reads(self):
        # 버리는 read가 실패해도(None) 그 자체는 판정 대상이 아니므로 계속 간다
        camera = StubCamera([None, "stale", None, "fresh"])
        inspector = StubInspector()
        run_inspection(camera, inspector, StubMachine(), flush_frames=3)
        assert inspector.inspected == ["fresh"]

    def test_final_read_failure_skips_verdict_but_notifies(self):
        # 본 캡처가 실패하면 판정은 생략하되 완료 통지는 한다 — INSPECT에 갇히면
        # 타임아웃(config driving.inspect.timeout_sec)까지 미션이 멈춘다
        camera = StubCamera(["stale0", "stale1", "stale2"])   # 4번째 read = None
        inspector, machine = StubInspector(), StubMachine()
        run_inspection(camera, inspector, machine, flush_frames=3)
        assert inspector.inspected == []
        assert machine.done_calls == 1

    def test_mock_path_never_touches_camera(self):
        # 개발 PC(mock) 경로는 검사기가 없다 — flush보다 먼저 반환돼야 한다
        camera = StubCamera(["frame"])
        machine = StubMachine()
        run_inspection(camera, None, machine, flush_frames=3)
        assert camera.reads == 0
        assert machine.done_calls == 1


class TestDefectReportHook:
    """이상 판정 → 보고 훅 (명세 4단계 '이상 시에 사진 촬영 + gps 태깅').

    run_inspection이 보고 정책(경로·쿨다운·좌표)을 모르게 훅으로 받는다 — 그래야
    이 함수가 파일시스템·GPS를 몰라도 되고, 보고 실패가 판정 완료 통지를 막지 않는다.
    """

    def test_defect_calls_hook_with_verdict_and_image(self):
        camera = StubCamera(["fresh"])
        inspector, machine = StubInspector(is_defect=True), StubMachine()
        reported = []
        run_inspection(camera, inspector, machine, flush_frames=0,
                       on_defect=lambda verdict, image, captured_at: reported.append((verdict, image)))
        assert len(reported) == 1
        verdict, image = reported[0]
        assert verdict.is_defect
        # 사진은 **판정에 쓴 그 프레임**이어야 한다 — 다시 읽으면 그새 로봇이 움직인다
        assert image == "fresh"
        assert machine.done_calls == 1

    def test_normal_verdict_does_not_report(self):
        camera = StubCamera(["fresh"])
        inspector, machine = StubInspector(is_defect=False), StubMachine()
        reported = []
        run_inspection(camera, inspector, machine, flush_frames=0,
                       on_defect=lambda verdict, image, captured_at: reported.append(verdict))
        assert reported == []
        assert machine.done_calls == 1

    def test_capture_failure_reports_nothing_but_still_notifies(self):
        # INSPECT에 갇혀 있는 것보다 쿨다운을 걸고 지나가는 편이 낫다
        camera = StubCamera([])
        inspector, machine = StubInspector(is_defect=True), StubMachine()
        reported = []
        run_inspection(camera, inspector, machine, flush_frames=0,
                       on_defect=lambda verdict, image, captured_at: reported.append(verdict))
        assert reported == []
        assert machine.done_calls == 1

    def test_no_hook_still_notifies(self):
        camera = StubCamera(["fresh"])
        inspector, machine = StubInspector(is_defect=True), StubMachine()
        run_inspection(camera, inspector, machine, flush_frames=0)
        assert machine.done_calls == 1


class TestDetectionScheduling:
    """탐지 추론은 블록에 **도착한 뒤에만** 돈다 — 접근 구간에서는 생략한다.

    TRACE가 발견 즉시 시작되면서(블록 최우선 이동) 접근 구간이 TRACE 안에 들어왔다.
    그 구간에서 imgsz 960 추론을 돌리면 순찰 속도로 달리는 동안 루프가 수백 ms씩
    멈춘다 = 눈먼 채 최고 속도 주행. 하향 카메라에 아직 블록이 없으므로 얻는 것도
    없다. 근거 전문은 main.detection_due().
    """

    def frame(self, proximity: float) -> SegmentationFrame:
        return SegmentationFrame(timestamp=0.0, sidewalk_detected=True,
                                 braille_detected=True,
                                 braille_proximity=proximity)

    def test_skipped_while_approaching(self, params):
        arrived = params.inspect_enter_proximity
        assert not detection_due(self.frame(0.0), arrived, False)
        assert not detection_due(self.frame(arrived / 2), arrived, False)

    def test_runs_once_arrived(self, params):
        arrived = params.inspect_enter_proximity
        assert detection_due(self.frame(arrived), arrived, False)   # 등호 경계 포함
        assert detection_due(self.frame(1.0), arrived, False)

    def test_no_frame_is_not_due(self, params):
        # 인지 두절 틱에 카메라를 읽으러 가지 않는다 (가드가 이미 정지시킨 상태다)
        assert not detection_due(None, params.inspect_enter_proximity, False)

    def test_arrival_latch_survives_a_segmentation_miss(self, params):
        """도착한 뒤에는 **주행캠이 블록을 놓쳐도** 탐지를 계속 돌린다.

        근접도는 세그가 블록을 잡고 있을 때만 유효한 값이다(미검출 틱에는 0.0). 래치가
        없으면 세그가 놓치는 순간 탐지가 멈추고 → 탐지캠 유지 신호(notify_braille_seen)가
        끊기고 → 추종이 '블록 소실 확정'으로 끝난다. 즉 세그를 보완하려고 만든 신호가
        세그에 의존해 같이 죽는다 — 그것을 막는 것이 이 래치다.
        """
        arrived = params.inspect_enter_proximity
        lost = SegmentationFrame(timestamp=0.0, sidewalk_detected=True)  # 블록 미검출
        assert lost.braille_proximity == 0.0        # 근접도가 0으로 떨어진다
        assert not detection_due(lost, arrived, False)   # 래치 없으면 멈춘다
        assert detection_due(lost, arrived, True)        # 래치가 있으면 계속 돈다
        # 인지 두절(frame=None)에도 래치가 살아 있으면 돈다 — 상태 이탈 시 호출부가
        # 래치를 풀므로(TRACE를 떠나면 False), 여기서 프레임을 다시 요구하지 않는다.
        assert detection_due(None, arrived, True)


def inspecting_machine(params: StateMachineParams, t: float) -> StateMachine:
    """실제 StateMachine을 INSPECT 상태로 올려놓는다.

    경로는 실주행과 같다: 발견 확정 → TRACE(저속 추종 + 상시 탐지) →
    탐지가 파손 후보를 보고(notify_defect_found) → INSPECT 정차 정밀 판정.
    """
    def near(when: float) -> SegmentationFrame:
        return SegmentationFrame(timestamp=when, sidewalk_detected=True,
                                 braille_detected=True,
                                 braille_center_offset_ratio=0.0,
                                 braille_proximity=params.inspect_enter_proximity)

    machine = StateMachine(params=params, now=0.0)
    machine.request_start()
    machine.step(SegmentationFrame(timestamp=0.0, sidewalk_detected=True), None, 0.0)
    seen_at = t - params.inspect_confirm_sec * 2
    machine.step(near(seen_at), None, seen_at)
    machine.step(near(t - 0.05), None, t - 0.05)
    assert machine.state is DriveState.TRACE
    machine.notify_defect_found()
    machine.step(near(t), None, t)
    assert machine.state is DriveState.INSPECT
    return machine


def test_notification_releases_real_state_machine(params):
    # 스텁 machine이 실물과 같은 계약인지 확인 — 통지가 INSPECT를 실제로 푼다.
    # 블록이 아직 보이므로 복귀는 순찰이 아니라 추종이다 (유도선의 나머지를 이어 검사).
    machine = inspecting_machine(params, t=1.0)
    run_inspection(StubCamera(["s0", "s1", "s2", "fresh"]), StubInspector(),
                   machine, flush_frames=3)
    machine.step(SegmentationFrame(timestamp=1.1, sidewalk_detected=True,
                                   braille_detected=True,
                                   braille_center_offset_ratio=0.0,
                                   braille_proximity=params.inspect_enter_proximity),
                 None, 1.1)
    assert machine.state is DriveState.TRACE
