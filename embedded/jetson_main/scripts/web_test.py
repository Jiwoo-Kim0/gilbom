"""주행 테스트 웹 대시보드 — 야외 테스트 관제용 (tools/data_recorder 패턴 참고).

브라우저에서 http://<젯슨IP>:8200 접속:
  - 주행 카메라 화면에 세그멘테이션 마스크·코리도·FSM 상태 오버레이 (모델 검증용)
  - 검사 카메라(Brio 100) 미리보기 + 마지막 INSPECT 캡처·판정 결과
  - 시작 / ESTOP / ESTOP 해제 버튼, 상태·명령 실시간 표시

주행 루프는 main.py와 동일한 조립(load_config·build_devices·apply_command 재사용)을
백그라운드 스레드 1개에서 돌린다 — 하드웨어 명령은 여전히 그 스레드 한 곳에서만
나간다(single writer 유지). 웹 스레드는 최신 JPEG·상태 스냅샷을 읽고 요청 플래그를
세울 뿐이다(StateMachine.request_*는 다음 틱에 소비되는 불리언이라 GIL로 충분).

이 파일은 테스트 도구다: driving/ 순수성 계약(스레드·I/O 금지)은 건드리지 않고,
스레드는 전부 이 도구 안에만 존재한다. 실주행 산출물은 여전히 main.py다.

  scripts/webtest.sh                # dry-run + 실추론 (모터 로그만) — 기본
  scripts/webtest.sh --live         # 야외 실주행 (실물 모터·서보!)
  scripts/webtest.sh --live --comms-config comms.yaml    # + MQTT 원격 미션

--comms-config를 주면 서버 명령(START/PAUSE/ESTOP…)과 이 화면의 버튼이 **같은 FSM**을
민다. 카메라는 한 프로세스만 열 수 있으므로, 세그 오버레이를 보면서 원격 미션을
돌리려면 이 경로뿐이다(main.py와 동시 실행 불가). 안 주면 통신 코드는 import조차
되지 않는다 — paho·credential 없이 종전처럼 대시보드만 쓸 수 있다.
"""

import argparse
import logging
import os
import sys
import threading
import time

from fastapi import FastAPI
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_ROBOT_ROOT = os.path.dirname(_SCRIPT_DIR)
sys.path.insert(0, _ROBOT_ROOT)
sys.path.insert(0, _SCRIPT_DIR)

import reporting
from comms.codec import utc_now
from driving.states import DriveState
from hardware.jetson.touch import build_touch_sensor
from main import (apply_command, build_devices, build_real_perception,
                  load_config)
from driving.state_machine import StateMachine, StateMachineParams
from gps_reader import GpsReader
from xbox_controller import XboxControllerReader

logger = logging.getLogger("robot.webtest")

HOST = "0.0.0.0"
PORT = 8200                 # DataRecorder(8100)와 다른 포트 — 혼동 방지
PREVIEW_JPEG_QUALITY = 70
PREVIEW_MAX_FPS = 15
INSPECT_STALE_SEC = 2.0            # FSM INSPECT가 소비할 실시간 판정의 신선도 상한
INSPECT_DETECT_PERIOD = 0.2        # 상시 판별 주기(초) — 모델은 15Hz까지 나오지만
                                   # GPU를 세그와 나눠 쓰므로 5Hz로 캡: 주행 루프
                                   # 우선 (무캡 시 주행 15→10Hz 하락, 0731 실측)


def _find_thermal_zones() -> dict:
    """기동 시 한 번: 보드 온도 센서 이름 → sysfs temp 경로 매핑.

    tj-thermal은 다이 전체 최고 접점 온도(스로틀링 기준값)라 대표값으로 쓴다.
    """
    import glob

    wanted = {"cpu-thermal": "cpu", "gpu-thermal": "gpu", "tj-thermal": "tj"}
    zones = {}
    for zone in glob.glob("/sys/class/thermal/thermal_zone*"):
        try:
            with open(os.path.join(zone, "type")) as f:
                name = f.read().strip()
        except OSError:
            continue
        if name in wanted:
            zones[wanted[name]] = os.path.join(zone, "temp")
    return zones


_THERMAL_PATHS = _find_thermal_zones()


def _read_board_temp() -> dict:
    """°C 단위 보드 온도. 읽기 실패한 센서는 빼고 보낸다 (cv* 존은 유휴 시 EAGAIN)."""
    temps = {}
    for key, path in _THERMAL_PATHS.items():
        try:
            with open(path) as f:
                temps[key] = round(int(f.read().strip()) / 1000.0, 1)
        except (OSError, ValueError):
            pass
    return temps


def _draw_overlay(cv2, image, result, post, state_name, command, frame_summary,
                  centerline_samples=(), free_reach_ratios=None, free_space=None,
                  roadway_fraction=None):
    """세그 결과 + 코리도 + 중심선 + 자유공간 프로파일 + 주행 상태를 그린다 (관제 화면용).

    한글은 cv2.putText가 못 그리므로 오버레이 텍스트는 영문 고정.
    """
    from perception.segmentation import (corridor_bounds, evaluate_centerline,
                                         fit_centerline, free_space_notches,
                                         obstacle_band_y1)

    annotated = result.plot()  # 클래스별 마스크·라벨 (ultralytics 기본 시각화)
    height, width = annotated.shape[:2]

    # 진행 코리도 (차도 위험 판정 영역, 노란색) — 판정과 같은 경계를 그려야 하므로
    # 산식을 복제하지 않고 segmentation.corridor_bounds를 그대로 쓴다
    x0, x1, y0 = corridor_bounds(height, width, post)
    cv2.rectangle(annotated, (x0, y0), (x1, height - 1), (0, 255, 255), 2)

    # 장애물 판정 밴드(주황) — 코리도에서 최하단 여백을 뺀 구간. 코리도와 다른
    # 사각형이므로 따로 그린다: 두 임계(obstacle_bottom_margin_ratio와
    # roadway_risk_fraction)를 현장에서 재보정할 때 어느 영역이 어느 판정인지
    # 화면에서 구분돼야 한다
    y1 = obstacle_band_y1(height, y0, post)
    if y1 < height - 1:
        cv2.rectangle(annotated, (x0, y0), (x1, y1), (0, 165, 255), 1)

    # 중심선: 행별 표본(파란 점) + RANSAC 적합 곡선(초록) + 조향 목표 지점(초록 원).
    # 셋이 같이 보여야 lookahead_ratio·인라이어 임계를 현장에서 조정할 수 있다.
    band_top = height // 2
    span = max(1, (height - 1) - band_top)

    def to_pixel(y_ratio, x_ratio):
        return (int(round((x_ratio + 1.0) / 2.0 * (width - 1))),
                int(round((height - 1) - y_ratio * span)))

    for sample in centerline_samples:
        cv2.circle(annotated, to_pixel(*sample), 3, (255, 128, 0), -1)
    fit = fit_centerline(centerline_samples, post.centerline)
    if fit is not None:
        curve = [to_pixel(step / 20.0, evaluate_centerline(fit, step / 20.0))
                 for step in range(21)]
        for start, end in zip(curve, curve[1:]):
            cv2.line(annotated, start, end, (0, 255, 0), 2)
        aim = post.centerline.lookahead_ratio
        cv2.circle(annotated, to_pixel(aim, evaluate_centerline(fit, aim)),
                   7, (0, 255, 0), 2)

    # 자유공간 프로파일 — 슬롯별로 '주행가능이 어디까지 이어지나'를 수직 막대로 그린다.
    # 이것만이 원거리(밴드 밖) 장애물을 보는 통로이므로, notch_min_ratio를 현장에서
    # 보정하려면 이 화면이 필요하다: 4~5m 사람 앞에 서서 그 슬롯 막대가 이웃보다
    # 얼마나 짧아지는지를 눈으로 재고 임계를 그 아래로 맞춘다.
    #   회색 = 노치 없음 / 노란색 = 노치(비용 가산) / 빨간색 = blocking
    # 막대 끝의 가로선이 그 슬롯의 자유거리 도달 지점(= 그 방향이 막히는 행)이다.
    if free_reach_ratios and free_space is not None and free_space.enabled:
        notches = free_space_notches(free_reach_ratios)
        scan_from = obstacle_band_y1(height, y0, post) - 1
        slots = len(free_reach_ratios)
        for index, (reach, notch) in enumerate(zip(free_reach_ratios, notches)):
            left = int(round(index * width / slots))
            right = int(round((index + 1) * width / slots))
            top = int(round(scan_from * (1.0 - reach)))
            if reach < free_space.blocking_reach_ratio:
                color = (0, 0, 255)          # blocking — 절대 기준 초과
            elif notch >= free_space.notch_min_ratio:
                color = (0, 255, 255)        # 노치 — 코스트맵 비용 가산 (비껴간다)
            else:
                color = (140, 140, 140)      # 열려 있음
            cv2.line(annotated, (left + 1, top), (right - 1, top), color, 2)
            cv2.line(annotated, ((left + right) // 2, top),
                     ((left + right) // 2, scan_from), color, 1)

    lines = [f"{state_name}  throttle {command.throttle_pct:+.0f}%"
             f"  steer {command.steering_offset_deg:+.1f}deg"
             + ("  BRAKE" if command.brake else "")]
    if frame_summary is not None:
        # 차도는 두 단계다 — risk는 선회 이탈(ESCAPE), critical은 즉시 ESTOP.
        # 실외 보정 중 '어느 임계에 걸렸는가'가 곧 '왜 이렇게 움직였는가'다
        roadway = ("CRITICAL" if frame_summary.roadway_critical
                   else "risk" if frame_summary.roadway_encroachment_risk else "X")
        # 원시 비율도 낸다: '차도를 인도로 본다'를 진단할 때 필요한 값이다. 화면에
        # 차도가 보이는데 이 숫자가 0이면 세그가 그 노면을 sidewalk로 라벨한 것이고,
        # 그건 임계값 문제가 아니라 모델 문제다 (result.plot()의 라벨로 교차 확인)
        if roadway_fraction is not None:
            roadway += f"({roadway_fraction:.2f})"
        summary_text = (
            f"sidewalk {'O' if frame_summary.sidewalk_detected else 'X'}"
            f"  center {frame_summary.sidewalk_center_offset_ratio:+.2f}"
            f"  roadway {roadway}"
            f"  obstacle {'O' if frame_summary.has_blocking_obstacle() else 'X'}")
        if frame_summary.braille_detected:
            summary_text += f"  braille {frame_summary.braille_proximity:.2f}"
        if frame_summary.crosswalk_ahead:
            summary_text += ("  crosswalk MISSING-BLOCKS"
                             if frame_summary.braille_missing_at_crosswalk
                             else "  crosswalk ok")
        lines.append(summary_text)
        # 최대 노치를 숫자로도 낸다 — notch_min_ratio를 보정할 때 읽는 값이다
        # (4~5m 사람 앞에 서서 이 값을 보고 임계를 그 아래로 맞춘다)
        if free_reach_ratios and free_space is not None and free_space.enabled:
            peak = max(free_space_notches(free_reach_ratios))
            lines.append(f"free_space  max_notch {peak:.3f}"
                         f"  (min {free_space.notch_min_ratio:.2f})"
                         f"  min_reach {min(free_reach_ratios):.2f}"
                         f"  (block <{free_space.blocking_reach_ratio:.2f})")
    # 어두운 배경띠 + 흰 글씨 한 번 — 외곽선 이중 렌더링은 JPEG 압축과 겹쳐 뭉개진다
    band_height = 10 + len(lines) * 22
    annotated[:band_height, :] = annotated[:band_height, :] // 3
    for row, text in enumerate(lines):
        cv2.putText(annotated, text, (8, 24 + row * 22),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1, cv2.LINE_AA)
    return annotated


class InspectWorker(threading.Thread):
    """검사 카메라 전담 스레드 — 프레임마다 파손 판별을 돌리고 결과를 래치한다.

    자동/수동 모드와 무관하게 상시 돈다: '검사 카메라에 블록이 잡히면 즉시
    판별'이라는 테스트 요구. 검사 카메라와 검사 모델은 이 스레드만 만진다
    (한 장치·한 모델 = 한 독자). FSM INSPECT 정차 판정도 여기의 최신 결과를
    소비한다 — 모델을 두 곳에서 부르지 않기 위해서다.
    """

    def __init__(self, camera, inspector):
        super().__init__(daemon=True, name="inspect-worker")
        import cv2

        self._cv2 = cv2
        self._camera = camera
        self._inspector = inspector
        self._stop_requested = threading.Event()

        self.latest_jpeg = None          # 검출 박스가 그려진 최신 프레임 (화면용)
        self.latest_image = None         # **박스 없는 원본** BGR — 증거 사진용
        self.latest_captured_at = None   # 그 원본을 읽기 직전의 UTC 시각 (계약 §4)
        self.latest_verdict = None       # BrailleVerdict
        self.verdict_at = 0.0
        self.frame_at = 0.0
        self.measured_hz = 0.0

    def run(self):
        cv2 = self._cv2
        while not self._stop_requested.is_set():
            started = time.monotonic()
            captured_at = utc_now()   # 캡처 직전 — 추론 뒤 시각을 쓰면 사진과 어긋난다
            image = self._camera.read()
            if image is None:
                self._stop_requested.wait(0.5)
                continue
            self.frame_at = time.monotonic()
            try:
                verdict, annotated = self._inspector.inspect_annotated(image)
            except Exception:
                logger.exception("[검사] 실시간 판별 실패 — 다음 프레임 계속")
                self._stop_requested.wait(0.5)
                continue
            self.latest_verdict = verdict
            # 증거 사진은 **원본**이다 — 아래에서 배너를 그리는 annotated 가 아니다.
            # 박스를 구운 이미지를 증거로 올리면 서버가 좌표를 다시 그릴 수 없고,
            # 원본이 아니라는 이유로 증거 가치가 떨어진다. inspect_annotated 의
            # result.plot() 은 새 배열을 돌려주므로 image 는 손대지 않은 프레임이다.
            self.latest_image = image
            self.latest_captured_at = captured_at
            self.verdict_at = time.monotonic()

            # 상단 판정 배너 (영문 — cv2.putText는 한글 불가)
            if verdict.band_count == 0:
                banner, color = "NO BLOCK", (160, 160, 160)
            elif verdict.is_defect:
                banner, color = f"DEFECT  {verdict.reason}", (60, 60, 255)
            else:
                banner, color = f"OK  {verdict.reason}", (80, 200, 80)
            annotated[:34, :] = annotated[:34, :] // 3
            cv2.putText(annotated, banner, (8, 24), cv2.FONT_HERSHEY_SIMPLEX,
                        0.65, color, 2, cv2.LINE_AA)

            ok, jpeg = cv2.imencode(".jpg", annotated,
                                    [cv2.IMWRITE_JPEG_QUALITY, PREVIEW_JPEG_QUALITY])
            if ok:
                self.latest_jpeg = jpeg.tobytes()
            elapsed = time.monotonic() - started
            if elapsed > 0.001:
                self.measured_hz = 0.9 * self.measured_hz + 0.1 * (1.0 / max(elapsed, INSPECT_DETECT_PERIOD))
            self._stop_requested.wait(max(0.0, INSPECT_DETECT_PERIOD - elapsed))

    def stop(self):
        self._stop_requested.set()

    def status(self) -> dict:
        verdict = self.latest_verdict
        if verdict is None:
            return {"available": False}
        return {
            "available": True,
            "age_sec": round(time.monotonic() - self.verdict_at, 1),
            "hz": round(self.measured_hz, 1),
            "block_visible": verdict.band_count > 0,
            "is_defect": verdict.is_defect,
            "reason": verdict.reason,
            "band_count": verdict.band_count,
            "defect_band_count": verdict.defect_band_count,
            "partial_count": verdict.partial_count,
        }


class DrivingSession(threading.Thread):
    """main.py의 주행 루프를 관제 계측(오버레이 JPEG·상태 스냅샷)과 함께 돌린다.

    장치·카메라·검사기를 전부 이 스레드가 소유한다 — 웹 스레드는 절대 만지지 않는다.
    """

    def __init__(self, config: dict, config_root: str, live: bool,
                 comms_runtime=None):
        super().__init__(daemon=True, name="driving-session")
        import cv2  # 보드 전용 — 오버레이 렌더링에 사용

        self._cv2 = cv2
        self._config = config
        self._live = live
        # 통신 런타임(선택). 주면 서버 명령·상태 보고가 이 루프에서 함께 돈다 —
        # main.py 와 같은 배선이다. 카메라를 한 프로세스만 열 수 있으므로,
        # 화면을 보면서 원격 미션을 돌리려면 이 경로뿐이다.
        self._comms = comms_runtime
        self._stop_requested = threading.Event()

        # 조립은 생성 시점(서버 기동 전) — 카메라·모델 문제는 여기서 즉시 죽는다.
        # 검사 카메라는 주행 전용 테스트에서 빼고 다닐 수 있으므로 없으면 None.
        (self._provider, self._nav_camera, self._inspect_camera,
         self._inspector) = build_real_perception(config, config_root,
                                                  optional_inspect=True)
        self._motor, self._servo, self._imu = build_devices(config, live=live)
        self._machine_params = StateMachineParams.from_config(config["driving"])
        self._machine = StateMachine(params=self._machine_params,
                                     now=time.monotonic(),
                                     on_event=self._log_transition)

        # 터치센서 — 명세의 '터치 시 주행 탐지 시작'. 웹 '시작' 버튼과 같은 트리거다.
        # build_devices(Blinka) 뒤에 조립하는 순서는 main.py와 같아야 한다 —
        # 그 순서를 전제로 touch._select_board_mode가 GPIO 모드를 되돌린다.
        self._touch = build_touch_sensor(config)

        # Xbox 컨트롤러: 리더는 입력 래치만, 명령 적용은 아래 주행 루프가 한다.
        # A 버튼 = 수동/자동 전환 (웹 버튼과 동일 동작).
        self.manual_mode = False
        self._controller = XboxControllerReader(
            config["hardware"]["joystick"], on_manual_toggle=self.toggle_manual)
        self._controller.start()

        # 검사 카메라 상시 판별 — 모드와 무관하게 블록이 잡히면 즉시 판정한다.
        # 카메라 미연결(주행 전용 테스트)이면 워커 없이 돈다.
        self._inspect_worker = None
        if self._inspect_camera is not None:
            self._inspect_worker = InspectWorker(self._inspect_camera,
                                                 self._inspector)
            self._inspect_worker.start()

        # GPS (u-blox 7) — 위치 표시용. 뽑혀 있어도 무해 (connected=False 표시만).
        gps_config = config["hardware"]["gps"]
        self._gps = GpsReader(device=str(gps_config["device"]),
                              baud=int(gps_config["baud"]))
        self._gps.start()

        from perception.segmentation import PostParams
        self._post = PostParams.from_config(
            config["perception"]["segmentation"]["post"])

        # 보고(사진 + GPS 태깅) — main.py와 **같은 형식·같은 게이트**로 남긴다.
        # 야외 테스트에서 대시보드만 쓰면 명세 4·5단계가 검증되지 않으므로 여기도 붙인다:
        # 나가서 실제로 reports/events.jsonl이 쌓이는지 확인하는 것이 그 검증이다.
        report_config = config["reporting"]
        self._report_dir = os.path.join(config_root, str(report_config["output_dir"]))
        self._save_images = bool(report_config["save_images"])
        self._crosswalk_gate = reporting.ReportGate(
            confirm_frames=int(report_config["crosswalk_confirm_frames"]),
            cooldown_sec=float(report_config["crosswalk_cooldown_sec"]))
        self._defect_gate = reporting.ReportGate(
            confirm_frames=1,
            cooldown_sec=float(report_config["defect_cooldown_sec"]))
        # 추종 세션 계측 — main.py와 같은 형식으로 events.jsonl에 남긴다
        self._trace_session = reporting.TraceSession()
        self._counted_verdict_at = 0.0   # 중복 집계 방지용 마지막 집계 판정 시각

        # 조향 바 표시용 리밋 (config 단일 출처에서 그대로 읽어 UI에 넘긴다)
        servo_config = config["hardware"]["steering_servo"]
        self._steer_left_limit = float(servo_config["left_offset_limit_deg"])
        self._steer_right_limit = float(servo_config["right_offset_limit_deg"])

        # 웹 스레드가 읽는 스냅샷들 — 참조 통짜 교체만 하므로 락 불필요
        # (검사 카메라 쪽 스냅샷은 InspectWorker가 소유한다)
        self.nav_jpeg = None
        self.last_command = None         # 마지막 DriveCommand
        self.last_frame = None           # 마지막 SegmentationFrame (인지 요약 표시용)
        self.nav_frame_at = 0.0          # 카메라 생존 점 표시용
        self.last_inspection = None      # 마지막 INSPECT 정차 판정 {"verdict": ..., "at": ...}
        self.last_transition = None      # {"prev": ..., "new": ..., "reason": ...}
        self.touch_pressed_at = None     # 마지막 터치 누름 시각 (센서 생존·반응 표시용)
        self.last_trace_session = None   # 마지막으로 끝난 추종 세션 요약
        self.measured_hz = 0.0
        self.fatal_error = None

    # ---- 웹 스레드에서 부르는 요청들 (불리언 플래그 — 다음 틱에 소비) ----

    def request_start(self):
        self._machine.request_start()

    def request_estop(self):
        # 수동 모드에서는 FSM이 멈춰 있으므로 컨트롤러 쪽 래치를 직접 건다 —
        # 웹 비상 정지는 모드와 무관하게 항상 즉시 먹어야 한다.
        if self.manual_mode:
            self._controller.estop_latched = True
        self._machine.request_estop()

    def request_estop_release(self):
        self._machine.request_estop_release()

    def set_manual(self, manual: bool):
        """수동/자동 전환. 자동 복귀 시 FSM을 새로 만든다(IDLE) — 수동 개입
        이전 상태(CRUISE 등)로 슬그머니 재개하지 않기 위해서다. 재출발은
        언제나 명시적 '시작' 요청이어야 한다."""
        if manual == self.manual_mode:
            return
        if not manual:
            self._machine = StateMachine(params=self._machine_params,
                                         now=time.monotonic(),
                                         on_event=self._log_transition)
        self.manual_mode = manual
        logger.info("[MODE] %s 모드", "수동(Xbox)" if manual else "자동(FSM)")

    def toggle_manual(self):
        self.set_manual(not self.manual_mode)

    def stop(self):
        self._stop_requested.set()
        self._controller.stop()
        if self._inspect_worker is not None:
            self._inspect_worker.stop()
        self._gps.stop()

    # ---- 상태 조회 ----

    def _comms_status(self) -> dict:
        """대시보드용 통신 요약 — 브로커에 붙었는지와 서버가 보는 값이 무엇인지.

        관제 화면과 이 화면을 번갈아 보지 않고도 어긋남을 잡으려는 것이다:
        여기 mode 가 SAFE_STOP 인데 위 state 가 CRUISE 면 그 자체가 이상 신호다.
        """
        if self._comms is None:
            return {"enabled": False}
        snapshot = self._comms.state.snapshot()
        return {
            "enabled": True,
            "connected": bool(getattr(self._comms.client, "connected", False)),
            "mode": snapshot.mode,
            "reason_code": snapshot.reason_code,
            "mission_id": snapshot.mission_id,
            "mission_state": snapshot.mission_state,
            # 서버가 누른 명령이 실제로 도착했는지 — 상태만으로는 안 보인다.
            # 거절·만료는 상태를 전혀 바꾸지 않으므로 이 로그가 유일한 흔적이다.
            "recent": self._comms.recent_commands()[-8:][::-1],
        }

    def status(self) -> dict:
        context = self._machine.context
        now = time.monotonic()
        command = self.last_command
        frame = self.last_frame
        return {
            "live": self._live,
            "state": "MANUAL" if self.manual_mode else self._machine.state.value,
            "manual_mode": self.manual_mode,
            "comms": self._comms_status(),
            "controller": self._controller.status(),
            "touch": {
                "connected": self._touch is not None,
                # 브라우저에 monotonic 시각을 넘기는 것은 의미가 없다 — 경과 초로 준다
                "ago_sec": (None if self.touch_pressed_at is None
                            else round(now - self.touch_pressed_at, 1)),
            },
            # 시작 요청이 아직 안 먹었다(인도 미확인) — 눌렀는데 왜 안 나가는가의 답
            "start_pending": self._machine.start_pending,
            # 추종 커버리지 — 진행 중 세션의 실시간 카운트 + 마지막으로 끝난 세션
            "trace": {
                "active": self._trace_session.entered_at is not None,
                "elapsed_sec": (None if self._trace_session.entered_at is None else
                                round(now - self._trace_session.entered_at, 1)),
                "detections": self._trace_session.detections,
                "defect_candidates": self._trace_session.defect_candidates,
                "last_session": self.last_trace_session,
            },
            "reason": context.reason,
            "loop_hz": round(self.measured_hz, 1),
            "command": None if command is None else {
                "throttle_pct": command.throttle_pct,
                "steering_offset_deg": command.steering_offset_deg,
                "brake": command.brake,
                "steer_left_limit": self._steer_left_limit,
                "steer_right_limit": self._steer_right_limit,
            },
            "perception": None if frame is None else {
                "sidewalk_detected": frame.sidewalk_detected,
                "center_offset": round(frame.sidewalk_center_offset_ratio, 3),
                "roadway_risk": frame.roadway_encroachment_risk,
                "roadway_critical": frame.roadway_critical,
                "blocking_obstacle": frame.has_blocking_obstacle(),
                "braille_detected": frame.braille_detected,
                "braille_proximity": round(frame.braille_proximity, 3),
                "crosswalk_ahead": frame.crosswalk_ahead,
                "braille_missing_at_crosswalk": frame.braille_missing_at_crosswalk,
            },
            "cameras": {
                "nav": {"alive": now - self.nav_frame_at < 2.0},
                "inspect": {"alive": self._inspect_worker is not None
                            and now - self._inspect_worker.frame_at < 2.0},
            },
            "live_verdict": ({"available": False} if self._inspect_worker is None
                             else self._inspect_worker.status()),
            "gps": self._gps.status(),
            "board_temp": _read_board_temp(),
            "last_transition": self.last_transition,
            "last_inspection": self.last_inspection,
            "fatal_error": self.fatal_error,
        }

    def _log_transition(self, prev, new, reason, now):
        logger.info("[FSM] %s → %s (%s) t=%.3f", prev.value, new.value, reason, now)
        self.last_transition = {"prev": prev.value, "new": new.value,
                                "reason": reason, "at": now}
        summary = self._trace_session.on_transition(prev, new, reason, now)
        if summary is not None:
            logger.info("[추종] 세션 종료 — %.1f초, 탐지 %d회, 파손 후보 %d회",
                        summary["duration_sec"], summary["detections"],
                        summary["defect_candidates"])
            self.last_trace_session = summary
            reporting.write_report(self._report_dir, reporting.KIND_TRACE,
                                   self._gps.status(), save_images=False,
                                   extra=summary)

    # ---- 주행 루프 (main.py의 루프 + 계측) ----

    def run(self):
        cv2 = self._cv2
        driving = self._config["driving"]
        period = 1.0 / driving["loop_hz"]
        settle_sec = float(driving["inspect"]["settle_sec"])
        inspected_entry = None
        tick = 0
        try:
            while not self._stop_requested.is_set():
                now = time.monotonic()

                # 서버 명령 소비 — **추론보다 먼저**다. 세그 추론은 수백 ms 걸리므로
                # 뒤에 두면 원격 정지가 그만큼 늦게 먹는다.
                if self._comms is not None and self._consume_remote(now, period):
                    continue

                # 인지: 카메라 1장 → 추론 → 요약. 오버레이를 위해 provider 내부와
                # 같은 순서를 여기서 직접 밟는다 (원본 이미지·result가 필요해서).
                image = self._nav_camera.read()
                frame = None
                if image is not None:
                    result = self._provider._model.predict(
                        image, imgsz=self._provider._imgsz, conf=self._provider._conf,
                        retina_masks=True, verbose=False)[0]
                    from perception.segmentation import (_class_masks_from_result,
                                                         build_frame,
                                                         summarize_class_masks)
                    class_masks, shape = _class_masks_from_result(result)
                    # 코스트맵·자유공간도 provider와 **같은 인자로** 밟는다 — 빠뜨리면
                    # 대시보드 주행만 폴백 경로(비례 조향 / 근거리 밴드 판정)로 내려가
                    # drive.sh와 다르게 움직인다. 특히 자유공간을 빠뜨리면 원거리
                    # 장애물(보행자)이 대시보드에서만 안 잡힌다
                    costmap = self._provider._costmap
                    free_space = self._provider._free_space
                    summary = summarize_class_masks(
                        class_masks, shape, self._provider._classes, self._post,
                        costmap_slot_count=(costmap.slot_count if costmap else None),
                        free_space=free_space)
                    frame = build_frame(summary, self._post, timestamp=now,
                                        costmap=costmap, free_space=free_space)

                # 터치센서 — 누름 엣지 1회가 웹 '시작' 버튼과 같은 요청이다.
                # 엣지는 모드와 무관하게 소비한다(수동 중 누름이 자동 복귀 때 밀려서
                # 터지지 않게). 수동 모드에서는 FSM을 밟지 않으므로 요청은 걸지 않고,
                # 센서가 살아 있다는 표시(touch_pressed_at)만 남긴다.
                if self._touch is not None and self._touch.pressed():
                    self.touch_pressed_at = now
                    if self.manual_mode:
                        logger.info("[터치] 누름 — 수동 모드이므로 요청하지 않음")
                    else:
                        self.request_start()
                        self.request_estop_release()
                        logger.info("[터치] 시작/ESTOP 해제 요청")

                # 수동 모드: FSM을 밟지 않고 컨트롤러 명령을 직접 적용한다.
                # 인지(위 추론)는 계속 돌린다 — 오버레이로 카메라 조준 확인용.
                if self.manual_mode:
                    command = self._controller.drive_command(
                        self._steer_left_limit, self._steer_right_limit)
                    state_label = "MANUAL"
                else:
                    command = self._machine.step(frame, self._imu.read(), now)
                    state_label = self._machine.state.value
                apply_command(self._motor, self._servo, command)
                if self._comms is not None:
                    # 명령 result의 COMPLETED는 실제 하드웨어 쓰기 뒤에만 나간다.
                    self._comms.poll_after_step(self._machine, command,
                                                camera_ok=(image is not None))
                self.last_command = command
                self.last_frame = frame

                if image is not None:
                    self.nav_frame_at = now
                    annotated = _draw_overlay(cv2, image, result, self._post,
                                              state_label, command, frame,
                                              summary.centerline_samples,
                                              summary.free_reach_ratios,
                                              self._provider._free_space,
                                              summary.roadway_corridor_fraction)
                    ok, jpeg = cv2.imencode(".jpg", annotated,
                                            [cv2.IMWRITE_JPEG_QUALITY, PREVIEW_JPEG_QUALITY])
                    if ok:
                        self.nav_jpeg = jpeg.tobytes()

                # 검사 카메라는 InspectWorker가 상시 소유·판별한다(한 장치, 한 독자).
                # TRACE 상시 탐지도 그 최신 결과를 소비한다 — main.py는 같은 일을
                # 루프 안에서 detect_period_sec 주기로 하지만, 여기서는 워커가 이미
                # 상시 돌고 있으므로 신선한 판정을 읽어 통지만 하면 된다.
                if not self.manual_mode and self._machine.state is DriveState.TRACE:
                    self._report_trace_detection(now)

                # 횡단보도 점자블록 미설치 보고 — main.py와 같은 규칙(연속 프레임 확정 +
                # 쿨다운). 수동 모드에서도 남긴다: 사람이 몰고 다니며 미설치 구역을
                # 수집하는 것이 이 도구의 정당한 용법이고, 판정은 주행 상태와 무관하다
                if frame is not None and self._crosswalk_gate.should_report(
                        frame.braille_missing_at_crosswalk, now):
                    reporting.write_report(
                        self._report_dir, reporting.KIND_MISSING,
                        self._gps.status(), image=image,
                        save_images=self._save_images,
                        extra={"note": "횡단보도-인도 이음부 점자블록 미설치"})
                    # 서버 보고도 main.py와 같다 — 게이트를 공유한다(프레임마다
                    # event_id가 달라 서버 멱등 처리로는 중복을 못 묶는다).
                    if self._comms is not None:
                        try:
                            self._comms.report_missing_braille(image)
                        except Exception:
                            logger.exception("[미설치] 서버 보고 실패 — 주행은 계속한다")

                # 자동 모드에서 정차 후 settle_sec이 지난 시점의 워커 최신
                # 판정만 소비해 FSM에 통지한다 — 진입(entered_at)당 정확히 1회.
                if not self.manual_mode and self._machine.state is DriveState.INSPECT:
                    entry = self._machine.context.entered_at
                    if entry != inspected_entry and now - entry >= settle_sec:
                        inspected_entry = entry
                        self._record_inspection(now)

                tick += 1
                elapsed = time.monotonic() - now
                if elapsed > 0.001:
                    self.measured_hz = 0.9 * self.measured_hz + 0.1 * (1.0 / max(elapsed, period))
                time.sleep(max(0.0, period - elapsed))
        except Exception as error:
            logger.exception("주행 루프 치명 오류 — 안전 정지")
            self.fatal_error = f"{type(error).__name__}: {error}"
        finally:
            apply_command(self._motor, self._servo,
                          self._machine.step(None, None, time.monotonic()))
            if self._comms is not None:
                # 바퀴를 세운 뒤에 닫는다 — 진행 중 미션을 FAILED로 닫고
                # presence OFFLINE(GRACEFUL_SHUTDOWN)까지 보낸 뒤 소켓을 내린다.
                try:
                    self._comms.shutdown()
                except Exception:
                    logger.exception("통신 종료 실패 — 장치 정리는 계속한다")
            # 검사 워커가 읽기를 마친 뒤에 카메라를 닫는다 (읽는 중 close 금지)
            if self._inspect_worker is not None:
                self._inspect_worker.stop()
                self._inspect_worker.join(timeout=3.0)
            if self._touch is not None:
                self._touch.close()
            self._nav_camera.close()
            if self._inspect_camera is not None:
                self._inspect_camera.close()
            logger.info("주행 루프 종료 — 최종 상태: %s", self._machine.state.value)

    def _consume_remote(self, now: float, period: float) -> bool:
        """서버 명령을 FSM 요청으로 옮긴다 → 이번 tick을 여기서 끝냈으면 True.

        긴급 명령(ESTOP·PAUSE·CANCEL)은 새 세그 추론을 기다리지 않는다. 공통 가드가
        인지보다 우선이라 frame=None 으로도 정지 전이가 되므로, 그 자리에서 한 tick을
        돌려 바퀴를 세우고 result 를 내보낸 뒤 돌아간다 (main.py 와 같은 처리).

        **긴급 명령은 수동 모드를 강제 해제한다.** 수동 모드는 FSM을 밟지 않으므로
        그대로 두면 관제에서 누른 비상정지가 '컨트롤러를 잡고 있어서 안 먹었다'가
        된다. 원격 정지가 사람 손보다 뒤에 서는 일은 없어야 한다.
        """
        urgent_stop = self._comms.poll_before_step(self._machine)
        if not urgent_stop:
            return False
        if self.manual_mode:
            # set_manual(False)를 쓰지 않는다: 그쪽은 FSM 객체를 **새로 만드는데**,
            # 위 poll_before_step이 이미 지금 객체에 요청을 걸어 놨다. 갈아치우면
            # 그 요청이 통째로 사라져 원격 긴급정지가 조용히 무시된다.
            # 새 FSM을 만드는 이유(수동 개입 이전 상태로 슬그머니 재개 금지)는
            # 여기서는 성립하지 않는다 — 긴급 명령 3종(ESTOP·PAUSE·CANCEL)은 전부
            # 이번 tick에 정지 상태로 수렴하므로 재개될 여지가 없다.
            logger.warning("[MODE] 원격 긴급 명령 — 수동 모드를 해제하고 FSM에 넘긴다")
            self.manual_mode = False
        command = self._machine.step(None, None, now)
        apply_command(self._motor, self._servo, command)
        self._comms.poll_after_step(self._machine, command, camera_ok=None)
        self.last_command = command
        self._stop_requested.wait(max(0.0, period - (time.monotonic() - now)))
        return True

    def _report_trace_detection(self, now: float) -> None:
        """TRACE 상시 탐지 — 워커의 신선한 판정이 불량이면 FSM에 정차 판정을 요청한다.

        판정을 여기서 확정하지 않는 이유는 모션 블러다: 추종은 저속이어도 주행 중이라
        defect_partial(≈55px)부터 먼저 무너진다. 그래서 이 통지는 '멈춰 볼 가치가
        있는가'의 1차 스크리닝이고, 확정은 INSPECT 정차 후 _record_inspection이 한다.
        오래된 판정은 무시한다 — 검사 카메라 장애 시 낡은 결과로 정차하지 않게.
        """
        worker = self._inspect_worker
        if worker is None:
            return
        verdict = worker.latest_verdict
        if verdict is None or now - worker.verdict_at > INSPECT_STALE_SEC:
            return
        # 계측은 **판정이 갱신된 틱에만** 센다 — 루프는 매 틱 여길 지나지만 워커는
        # INSPECT_DETECT_PERIOD 주기로 돈다. 매 틱 세면 탐지 횟수가 부풀어
        # main.py 쪽 숫자와 비교가 안 된다.
        if worker.verdict_at != self._counted_verdict_at:
            self._counted_verdict_at = worker.verdict_at
            self._trace_session.count_detection(bool(verdict.is_defect))
        if verdict.is_defect:
            logger.info("[탐지] 추종 중 파손 후보 — %s → 정차 정밀 판정 요청",
                        verdict.reason)
            self._machine.notify_defect_found()

    def _record_inspection(self, now: float) -> None:
        """INSPECT 정차 판정 — 상시 판별 스레드의 최신 결과를 소비한다.

        정차 후 settle_sec을 기다렸으므로 워커의 최신 판정은 이미 정지 화면
        기준이다. 신선하지 않으면(검사 카메라 장애 등) 건너뛰고 주행을 재개한다.
        """
        worker = self._inspect_worker
        if worker is None:
            logger.info("[INSPECT] 검사 카메라 미연결 — 판정 생략, 주행 재개")
            self.last_inspection = {"error": "검사 카메라 미연결", "at": now}
            self._machine.notify_inspection_done()
            return
        verdict = worker.latest_verdict
        if verdict is None or now - worker.verdict_at > INSPECT_STALE_SEC:
            logger.warning("[INSPECT] 실시간 판정 없음/오래됨 — 건너뛰고 주행 재개")
            self.last_inspection = {"error": "검사 카메라 판정 없음", "at": now}
            self._machine.notify_inspection_done()
            return
        logger.info("[INSPECT] 정차 판정: %s — %s",
                    "불량" if verdict.is_defect else "정상", verdict.reason)
        self.last_inspection = {
            "is_defect": verdict.is_defect, "reason": verdict.reason,
            "band_count": verdict.band_count,
            "defect_band_count": verdict.defect_band_count,
            "partial_count": verdict.partial_count, "at": now,
        }
        # 파손 보고(사진 + GPS 태깅) — main.py와 같은 경로·같은 형식. 사진은 워커가
        # 검출 박스를 그려 둔 최신 프레임(latest_jpeg)이 아니라 원본이어야 하지만,
        # 워커는 인코딩된 JPEG만 보관하므로 여기서는 좌표·판정만 남긴다.
        # (INSPECT 정차 화면 자체는 대시보드의 검사 카메라 스트림으로 눈으로 확인한다)
        if verdict.is_defect and self._defect_gate.should_report(True, now):
            reporting.write_report(
                self._report_dir, reporting.KIND_DEFECT, self._gps.status(),
                image=None, save_images=self._save_images,
                extra={"reason": verdict.reason, "bands": verdict.band_count,
                       "defect_bands": verdict.defect_band_count,
                       "partials": verdict.partial_count,
                       "source": "webtest"})
        # 서버 보고 — 로컬 쿨다운과 분리한다(main.py 와 같은 이유: 디스크 정책 하나가
        # 결함 유실이 되면 안 된다. 서버 중복은 event_id 멱등 처리가 받는다).
        # 사진은 워커가 래치한 **원본** 프레임이고 시각은 그 캡처 직전 값이다.
        if self._comms is not None and verdict.is_defect:
            image = worker.latest_image
            if image is None:
                logger.warning("[INSPECT] 원본 프레임이 없어 서버 보고를 건너뛴다")
            else:
                try:
                    self._comms.report_inspection(
                        verdict, image, worker.latest_captured_at or utc_now())
                except Exception:
                    logger.exception("[INSPECT] 결함 보고 실패 — 주행은 계속한다")
        self._machine.notify_inspection_done()


def _mjpeg_stream(read_latest):
    """최신 JPEG 스냅샷 → MJPEG 제너레이터 (DataRecorder와 같은 방식)."""
    interval = 1.0 / PREVIEW_MAX_FPS
    while True:
        jpeg = read_latest()
        if jpeg is not None:
            yield (b"--frame\r\nContent-Type: image/jpeg\r\n"
                   b"Content-Length: " + str(len(jpeg)).encode() + b"\r\n\r\n"
                   + jpeg + b"\r\n")
        time.sleep(interval)


def create_app(session: DrivingSession) -> FastAPI:
    app = FastAPI(title="robot 주행 테스트 대시보드")

    @app.get("/")
    async def index():
        return FileResponse(os.path.join(_SCRIPT_DIR, "web_test.html"))

    @app.get("/stream/nav")
    async def stream_nav():
        return StreamingResponse(_mjpeg_stream(lambda: session.nav_jpeg),
                                 media_type="multipart/x-mixed-replace; boundary=frame")

    @app.get("/stream/inspect")
    async def stream_inspect():
        worker = session._inspect_worker
        return StreamingResponse(
            _mjpeg_stream(lambda: worker.latest_jpeg if worker else None),
            media_type="multipart/x-mixed-replace; boundary=frame")

    @app.get("/api/status")
    async def status():
        return session.status()

    @app.post("/api/start")
    async def start():
        session.request_start()
        return {"ok": True}

    @app.post("/api/estop")
    async def estop():
        session.request_estop()
        return {"ok": True}

    @app.post("/api/estop_release")
    async def estop_release():
        session.request_estop_release()
        return JSONResponse({"ok": True})

    @app.post("/api/mode/manual")
    async def mode_manual():
        session.set_manual(True)
        return {"ok": True}

    @app.post("/api/mode/auto")
    async def mode_auto():
        session.set_manual(False)
        return {"ok": True}

    return app


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default=os.path.join(_ROBOT_ROOT, "config.yaml"))
    parser.add_argument("--live", action="store_true",
                        help="실구동 모드 (기본은 dry-run: 모의 장치에 로그만)")
    parser.add_argument("--comms-config", default=None,
                        help="MQTT v2 설정 경로. 주면 화면을 보면서 원격 미션을 돌린다")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    if args.live:
        logger.warning("실구동 모드: 웹 '시작' 버튼을 누르면 실제 모터가 돕니다!")

    config = load_config(args.config)
    comms_runtime = None
    if args.comms_config:
        # 통신 의존성은 옵션을 준 경로에서만 import한다 — paho·credential 없이도
        # 대시보드 단독 사용이 종전 그대로 된다 (main.py 와 같은 규칙).
        from communication import CommunicationRuntime
        comms_runtime = CommunicationRuntime.build(
            args.comms_config, config, live=args.live)
        comms_runtime.start()
        logger.warning("MQTT 원격 미션 모드 — 서버 START_MISSION 과 웹 '시작' 버튼이 "
                       "같은 FSM을 민다")

    session = DrivingSession(config, os.path.dirname(os.path.abspath(args.config)),
                             live=args.live, comms_runtime=comms_runtime)
    session.start()

    import uvicorn
    try:
        # app 객체를 직접 넘긴다 — 문자열("main:app")이면 모듈 재임포트로
        # 카메라·모터가 두 번 초기화된다 (DataRecorder에서 실측된 함정).
        # timeout_graceful_shutdown: 열려 있는 MJPEG 스트림(무한 응답)이 graceful
        # 종료를 영원히 붙들어 재시작이 막히는 것을 끊는다 (0731 실측 — SIGTERM
        # 이 안 먹고 SIGINT/SIGKILL이 필요했던 원인).
        uvicorn.run(create_app(session), host=HOST, port=PORT, log_level="warning",
                    timeout_graceful_shutdown=3)
    finally:
        session.stop()
        session.join(timeout=5.0)


if __name__ == "__main__":
    main()
