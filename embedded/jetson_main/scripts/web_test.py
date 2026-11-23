"""주행 테스트 웹 대시보드 — 야외 테스트 관제용 (tools/data_recorder 패턴 참고).

브라우저에서 http://<젯슨IP>:8200 접속:
  - 주행 카메라 화면에 세그멘테이션 마스크·코리도·FSM 상태 오버레이 (모델 검증용)
  - 검사 카메라(Brio 100) 미리보기 + 마지막 INSPECT 캡처·판정 결과
  - 시작 / ESTOP / ESTOP 해제 버튼, 상태·명령 실시간 표시
  - 보고 이력: 파손·미설치를 서버로 보낼 때마다 **그때 보낸 사진**·좌표·전송 상태
    (현장에서 보고가 나갔는지, 오탐이 아닌지를 폰 화면만 보고 판단할 수 있게)

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
import collections
import json
import logging
import math
import os
import sys
import threading
import time

from fastapi import FastAPI, Response
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_ROBOT_ROOT = os.path.dirname(_SCRIPT_DIR)
sys.path.insert(0, _ROBOT_ROOT)
sys.path.insert(0, _SCRIPT_DIR)

import reporting
from comms.codec import utc_now
from driving import stop_analysis
from driving.start_interlock import StartInterlock
from driving.states import DriveState
from hardware.jetson.touch import build_touch_sensor
from main import (StartupZeroing, apply_command, build_devices,
                  build_real_perception, load_config)
from driving.state_machine import StateMachine, StateMachineParams
from gps_reader import GpsReader
from xbox_controller import XboxControllerReader

logger = logging.getLogger("robot.webtest")


def make_run_dir(config_root: str) -> str:
    """이번 실행의 기록 자리 — var/runs/<YYYYmmdd_HHMMSS>/ 를 만들고 돌려준다.

    var/runs/latest 심볼릭 링크도 여기로 걸어 둔다. 현장에서 방금 끝난 주행을
    이름 없이 집을 수 있어야 하기 때문이다 (`tail var/runs/latest/stops.jsonl`).
    링크 생성이 실패해도(권한·파일시스템) 주행은 그대로 간다 — 편의 기능이다.
    """
    run_dir = os.path.join(config_root, "var", "runs",
                           time.strftime("%Y%m%d_%H%M%S"))
    os.makedirs(run_dir, exist_ok=True)
    link = os.path.join(config_root, "var", "runs", "latest")
    try:
        if os.path.islink(link) or os.path.exists(link):
            os.unlink(link)
        os.symlink(os.path.basename(run_dir), link)
    except OSError:
        logger.debug("var/runs/latest 링크 생성 실패", exc_info=True)
    return run_dir


HOST = "0.0.0.0"
PORT = 8200                 # DataRecorder(8100)와 다른 포트 — 혼동 방지
PREVIEW_JPEG_QUALITY = 70
PREVIEW_MAX_FPS = 15
INSPECT_STALE_SEC = 2.0            # FSM INSPECT가 소비할 실시간 판정의 신선도 상한
INSPECT_DETECT_PERIOD = 0.2        # 상시 판별 주기(초) — 모델은 15Hz까지 나오지만
                                   # GPU를 세그와 나눠 쓰므로 5Hz로 캡: 주행 루프
                                   # 우선 (무캡 시 주행 15→10Hz 하락, 0731 실측)

# 수동 조작 중 파손 보고의 디바운스. config.yaml이 아니라 여기 두는 이유는 이 경로가
# 이 테스트 도구에만 있기 때문이다 — main.py의 파손 보고는 INSPECT 정차가 블록당
# 1회를 보장하므로 이 값이 필요 없다.
MANUAL_DEFECT_CONFIRM_FRAMES = 2   # 연속 판정 수. 5Hz라 0.4초 — 정차 없이 움직이는
                                   # 프레임을 쓰므로 모션 블러 오탐 1프레임을 걸러낸다.
#: 화면에 남기는 보고 이력 개수. 사진을 원본 해상도 JPEG으로 함께 들고 있으므로
#: (건당 수십~수백 KB) 짧게 끊는다 — 전체 기록은 reports/events.jsonl 이 갖는다.
REPORT_HISTORY = 5

MANUAL_DEFECT_COOLDOWN_SEC = 5.0   # 같은 대상 중복 보고 상한. defect_cooldown_sec(2초)를
                                   # 쓰지 않는 이유: 그 값은 INSPECT 정차 1회당 1건을
                                   # 전제하는데, 상시 판별은 파손 블록 앞에 서 있는 동안
                                   # 계속 참이라 2초면 분당 30건이 서버로 나간다. 대신
                                   # 5초 안에 지나친 두 번째 블록은 놓친다 — 수집할 때는
                                   # 블록마다 잠깐 멈추는 편이 확실하다.


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
        # 빈 프레임 — 세그가 아무것도 못 잡은 틱. 화면에 이 글자가 자주 뜨면
        # 그 장면의 회피·막다른길 판정은 전부 의심 대상이다 (BlankFrameGate)
        if frame_summary.perception_blank:
            summary_text += "  BLANK-SEG"
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
                 comms_runtime=None, start_interlock=None, run_dir=None):
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
        # 출발 인터록 — 웹 '시작'·서버 START_MISSION 만으로는 못 달리고 로봇 몸체의
        # 터치가 있어야 출발한다 (driving/start_interlock.py). 원격 미션 모드에서는
        # 통신 쪽 명령 처리도 **같은 객체**를 본다 (main 이 넘긴다).
        self._interlock = start_interlock or StartInterlock.disabled()
        if self._touch is None:
            self._interlock.disable("터치 센서 없음")
        # 출발 직전 자이로 영점 — main.py 와 **같은 구현**을 쓴다. 대시보드로 시연할 때만
        # 영점 없이 출발하면 회전·회차 판정이 그 화면에서만 흐른다.
        # 출발 신호가 무엇이든(터치·웹 버튼·서버) 이 2초를 지나고 나서야 바퀴가 돈다.
        self._zeroing = StartupZeroing(
            self._imu, float(config["hardware"]["imu"]["zero_sec"]),
            self._start_after_zeroing)

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
            self._inspect_worker = InspectWorker(
                self._inspect_camera, self._inspector)
            self._inspect_worker.start()

        # GPS (u-blox 7) — 위치 표시용. 뽑혀 있어도 무해 (connected=False 표시만).
        # 원격 미션 모드에서는 통신 런타임의 리더를 **공유**한다 — 같은 시리얼
        # 장치를 두 리더가 열면 NMEA 문장을 나눠 읽어 양쪽 좌표가 다 깨진다.
        # 공유한 리더의 수명(start/stop)은 런타임 소유다.
        self._owns_gps = comms_runtime is None
        if comms_runtime is not None:
            self._gps = comms_runtime.gps_reader
        else:
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
        # 주행 경로 로그 — 오프라인 분석용 JSONL. reports/ 가 아니라 var/ 에 두는 이유:
        # reports/ 는 '결함 보고'라는 산출물의 자리이고, 이건 계측 기록이다.
        # **실행마다 디렉터리를 판다** (var/runs/<run_id>/): 종전에는 모든 실행이
        # 한 파일에 이어 붙어서, 어제 주행과 오늘 주행이 섞인 채로 분석됐다.
        self._run_dir = run_dir or make_run_dir(config_root)
        self._trace_path = os.path.join(self._run_dir, "trace.jsonl")
        self._stops_path = os.path.join(self._run_dir, "stops.jsonl")
        self._traced_at = 0.0
        self._traced_transition = None
        # 정지 분석용 창 — 최근 기록만 메모리에 들고 있는다. 파일을 되읽지 않는
        # 이유는 주행 스레드에서 도는 계측이라서다(디스크를 기다리면 루프가 흔들린다).
        # 0.5초 간격 + 전이줄이라 240개면 20초 창(stop_analysis.WINDOW_SEC)에 넉넉하다.
        self._trace_window = collections.deque(maxlen=240)
        self._pending_stop = None       # 이번 tick 의 트레이스가 쌓인 뒤 분석할 정지
        self.recent_stops = []          # 최신이 앞 — status() 로 화면·API 에 나간다
        self._save_images = bool(report_config["save_images"])
        self._crosswalk_gate = reporting.ReportGate(
            confirm_frames=int(report_config["crosswalk_confirm_frames"]),
            cooldown_sec=float(report_config["crosswalk_cooldown_sec"]))
        self._defect_gate = reporting.ReportGate(
            confirm_frames=1,
            cooldown_sec=float(report_config["defect_cooldown_sec"]))
        # 수동 조작 중 파손 보고 전용 게이트 — 자동 경로(_defect_gate)와 나눈다.
        # 공유하면 수동으로 한 번 보고한 직후 자동으로 바꿨을 때 첫 INSPECT 판정이
        # 쿨다운에 걸려 조용히 삼켜진다(모드 전환은 언제든 일어난다).
        self._manual_defect_gate = reporting.ReportGate(
            confirm_frames=MANUAL_DEFECT_CONFIRM_FRAMES,
            cooldown_sec=MANUAL_DEFECT_COOLDOWN_SEC)
        self._manual_defect_verdict_at = 0.0   # 게이트에 먹인 마지막 판정 시각
        # 보고 이력 — 현장에서 '방금 무엇을 어떤 사진으로 보냈나'를 화면으로 확인하는 창.
        # 주행 스레드만 쓰고 웹 스레드는 읽기만 한다: 갱신은 새 리스트/딕트를 만들어
        # 통째로 바꿔치기하므로(GIL) 락이 없어도 읽는 쪽이 반쪽 상태를 보지 않는다.
        self.recent_reports = []           # 최신이 앞 (사진 제외 — status()가 그대로 낸다)
        self._report_jpegs = {}            # seq → 보고에 쓴 사진의 JPEG (화면 표시용)
        self._report_seq = 0
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

    # ---- 주행 스레드에서 부르는 콜백 ----

    def _start_after_zeroing(self) -> None:
        """정지 자이로 영점이 끝났다 — 이제서야 출발 요청이 나간다.

        `self._machine.request_start` 를 바로 넘기지 않는 이유는 수동/자동 전환이
        FSM 객체를 **새로 만들기** 때문이다(set_manual) — 바인딩해 두면 영점이 끝난
        요청이 버려진 옛 FSM으로 들어간다.
        """
        self._machine.request_start()

    # ---- 웹 스레드에서 부르는 요청들 (불리언 플래그 — 다음 틱에 소비) ----

    def request_start(self, source: str = "웹 시작 버튼"):
        """출발 신호 하나 접수 → 정지 자이로 영점 → 그것이 끝나면 FSM 출발 요청.

        웹 '시작'·터치·서버 명령이 전부 여기로 온다 — 같은 급의 출발 버튼이다.
        인터록(hardware.touch.required_for_start)이 켜진 구성에서만 논리곱을 먼저
        지나고, 보류되면 화면에 status()['touch']['waiting'] 로 뜬다.
        영점 진행 중은 status()['touch']['zeroing'] 이다 — '눌렀는데 왜 2초 안
        가는가'의 답이 화면에 있어야 한다.
        """
        if self._interlock.arm(source=source):
            self._zeroing.begin(time.monotonic(), source)

    def request_estop(self):
        # 수동 모드에서는 FSM이 멈춰 있으므로 컨트롤러 쪽 래치를 직접 건다 —
        # 웹 비상 정지는 모드와 무관하게 항상 즉시 먹어야 한다.
        if self.manual_mode:
            self._controller.estop_latched = True
        # 서버에는 관제가 누른 EMERGENCY_STOP 과 **같은 사건**으로 보고한다. 이 알림이
        # 없으면 투영기가 로봇 고장(SAFE_STOP/INTERNAL_ERROR)으로 보고하고, 그러면
        # 관제의 CLEAR·RESUME·START 가 전부 거부되어 이 버튼 한 번에 원격 조작이
        # 통째로 잠긴다. FSM 요청보다 먼저 부른다 — 주행 루프가 그 요청을 소비하는
        # tick 에 출처가 이미 보여야 한다.
        if self._comms is not None:
            self._comms.note_operator_estop()
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
        if self._owns_gps:   # 공유 리더는 런타임(shutdown)이 세운다
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
                # 출발 인터록: 이 구성이 터치를 요구하는가 / 지금 그 터치를 기다리는가.
                # '시작을 눌렀는데 왜 안 가는가'의 답이라 화면에 반드시 있어야 한다.
                **self._interlock.status(),
                # 터치 뒤 자이로 영점 중이면 그 사실도 — 몇 초 안 가는 것이 정상이다
                "zeroing": self._zeroing.busy,
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
            # 정지 사후 분석 — 최근 8건. '왜 섰나'를 화면 앞에서 바로 본다
            # (전문은 var/runs/<run_id>/stops.jsonl).
            "stops": self.recent_stops,
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
                "perception_blank": frame.perception_blank,
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
            "reports": self.recent_reports,
            "fatal_error": self.fatal_error,
            # yaw 부호 확인용 — 봇을 손으로 좌(반시계)로 돌렸을 때 yaw_deg가 +로
            # 가야 DEAD_END 완료 판정(좌회전=yaw+)이 성립한다. 0804 실측에서는
            # 좌조향 회차 중 누적이 음수로 갔다.
            "imu": (None if getattr(self, "last_imu", None) is None else {
                "yaw_deg": round(self.last_imu.yaw_deg, 1),
                "pitch_deg": round(self.last_imu.pitch_deg, 1),
            }),
        }

    #: 주행 경로 로그 기록 간격(초). 10Hz 루프를 그대로 받아쓰면 1분에 600줄이라
    #: 분석보다 파일 관리가 일이 된다. 상태 전이는 간격과 무관하게 항상 남긴다.
    TRACE_INTERVAL_SEC = 0.5

    def _append_trace(self, now, command, frame, reading):
        """주행 경로 1줄을 var/drive_trace.jsonl 에 남긴다.

        나중에 '그때 왜 그렇게 움직였나'를 되짚을 수 있는 최소 집합이다:
        시각·상태·국면·명령(스로틀/조향)·자세(yaw/pitch)·인지 요약.
        오프라인 분석용이라 순수 계층이 아니라 여기(계측 계층)에서만 쓴다.

        ※ 계측이 주행을 죽이면 안 된다 — 0804에 로그 한 줄의 KeyError로 주행
          루프가 통째로 죽은 적이 있다. 그래서 전부 getattr 로 방어하고 예외를
          삼킨다. 로그가 빠지는 것이 주행이 멈추는 것보다 낫다.
        """
        transition = self.last_transition
        changed = transition is not None and transition is not self._traced_transition
        if not changed and now - self._traced_at < self.TRACE_INTERVAL_SEC:
            return
        self._traced_at = now
        self._traced_transition = transition
        try:
            context = self._machine.context
            phase = (context.dead_end_phase.name
                     if self._machine.state is DriveState.DEAD_END
                     else context.avoid_phase.name
                     if self._machine.state is DriveState.AVOID else None)
            record = {
                "t": round(now, 2),
                "at": utc_now(),
                "state": self._machine.state.value,
                "phase": phase,
                "reason": context.reason,
                "throttle": command.throttle_pct,
                "steer": round(command.steering_offset_deg, 1),
                "brake": command.brake,
                "yaw": None if reading is None else round(reading.yaw_deg, 1),
                "pitch": None if reading is None else round(reading.pitch_deg, 1),
                "attempt": getattr(context, "dead_end_attempt", None),
                "dir": getattr(context, "dead_end_direction", None),
            }
            if frame is not None:
                costs = frame.cost_profile
                record.update({
                    "sidewalk": frame.sidewalk_detected,
                    "offset": round(frame.sidewalk_center_offset_ratio, 3),
                    "blocked_all": frame.all_directions_blocked,
                    "blocking": frame.has_blocking_obstacle(),
                    "roadway": frame.roadway_encroachment_risk,
                    "braille": frame.braille_detected,
                    # 세그가 이 틱에 아무것도 못 잡았는가 — 회피·막다른길 오판의
                    # 최대 원인이라 로그에서 바로 세어질 수 있어야 한다
                    "blank": frame.perception_blank,
                    # 슬롯별 통행 비용 — 방향 선택의 근거 그 자체다. inf는 JSON에
                    # 실을 수 없으므로 null 로 눕힌다(=절대 금지 방향).
                    "costs": None if costs is None else [
                        None if math.isinf(c) else round(c, 2) for c in costs],
                    # 가장 가까운 blocking 장애물의 거리 대용(근접도)과 좌우 치우침
                    "obstacles": [
                        {"prox": round(o.proximity, 2),
                         "bias": round(o.lateral_bias, 2), "blocking": o.blocking}
                        for o in frame.obstacles],
                })
            # 창을 먼저 채운다 — 디스크가 실패해도 정지 분석은 살아 있어야 한다.
            self._trace_window.append(record)
            with open(self._trace_path, "a", encoding="utf-8") as stream:
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")
        except Exception:                       # 계측이 주행을 죽이지 않는다
            logger.debug("주행 경로 로그 기록 실패", exc_info=True)

    def _analyze_stop(self, prev, new, reason, now):
        """정지 한 번을 그 자리에서 분석해 stops.jsonl 에 남긴다 (driving/stop_analysis.py).

        현장에서는 '멈췄다'까지만 보이고 원인은 로그 수백 줄 안에 있다. 그것을
        정지 시점에 한 줄로 접어 두는 것이 목적이다 — 돌아와서 되짚을 때도,
        서 있는 로봇 앞에서 다음 한 번을 어떻게 바꿀지 정할 때도 같은 것을 본다.

        ※ _append_trace 와 같은 원칙: 계측이 주행을 죽이지 않는다. 전부 삼킨다.
        """
        try:
            window = stop_analysis.window_before(self._trace_window, now)
            analysis = stop_analysis.analyze_stop(
                window, state_from=prev.value, state_to=new.value,
                reason=reason, at=utc_now())
            logger.info("[정지분석] %s → %s : %s", prev.value, new.value,
                        analysis.headline())
            for finding in analysis.findings:
                logger.info("[정지분석]   · %s (%s)%s", finding.headline,
                            finding.evidence,
                            f" → {finding.knob}" if finding.knob else "")
            record = analysis.to_record()
            with open(self._stops_path, "a", encoding="utf-8") as stream:
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            # 통짜 교체 — 웹 스레드가 반쪽 리스트를 보지 않는다 (다른 스냅샷과 같은 규칙)
            self.recent_stops = ([record] + self.recent_stops)[:8]
        except Exception:
            logger.debug("정지 분석 실패", exc_info=True)

    def _log_transition(self, prev, new, reason, now):
        logger.info("[FSM] %s → %s (%s) t=%.3f", prev.value, new.value, reason, now)
        self.last_transition = {"prev": prev.value, "new": new.value,
                                "reason": reason, "at": now}
        if (new.value in stop_analysis.STOP_STATES
                and prev.value not in stop_analysis.STOP_STATES):
            # 정지로 들어가는 전이만 본다. 정지끼리의 전이(IDLE→ESTOP 등)는
            # 주행이 끝난 뒤의 일이라 분석할 창이 없다.
            #
            # 여기서 바로 분석하지 않고 미뤄 두는 이유: 이 훅은 machine.step()
            # **안에서** 불리고, 정지를 만든 그 프레임은 아직 트레이스에 안 들어갔다.
            # 지금 분석하면 정작 원인이 된 프레임(차도가 코리도를 덮은 그 장면)이
            # 창에서 빠져 '원인 불명'이 된다. 이번 tick 의 _append_trace 뒤에 낸다.
            self._pending_stop = (prev, new, reason, now)
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

                # 인지: 카메라 1장 → 추론 → 요약. **전부 provider 를 지난다** —
                # 오버레이에 필요한 result·summary 까지 한 번에 받아온다
                # (analyze_full). 여기서 predict 를 직접 부르던 종전 코드는 전역
                # conf 로 추론하고 클래스별 하한을 빠뜨려, 이 화면의 점자블록 재현율이
                # drive.sh 와 달랐다 — 화면으로 보정한 값이 실주행에 안 맞는다는 뜻이다.
                image = self._nav_camera.read()
                frame = None
                if image is not None:
                    result, summary, frame = self._provider.analyze_full(image)

                # 터치센서 — 누름 엣지 1회가 웹 '시작' 버튼을 **대신한다**.
                # 엣지는 모드와 무관하게 소비한다(수동 중 누름이 자동 복귀 때 밀려서
                # 터지지 않게). 수동 모드에서는 FSM을 밟지 않으므로 요청은 걸지 않고,
                # 센서가 살아 있다는 표시(touch_pressed_at)만 남긴다.
                if self._touch is not None and self._touch.pressed():
                    self.touch_pressed_at = now
                    if self.manual_mode:
                        logger.info("[터치] 누름 — 수동 모드이므로 요청하지 않음")
                    else:
                        # 곧바로 출발시키지 않는다: 정지 자이로 영점 2초를 먼저 돌리고
                        # 그것이 끝나야 출발 요청이 나간다 (main.StartupZeroing).
                        self.request_estop_release()
                        logger.info("[터치] 눌림 — ESTOP 해제 + 출발(영점 시작)")
                        self._interlock.touched()
                        self.request_start(source="터치")
                if not self.manual_mode:
                    self._zeroing.poll(now)
                    # 인터록이 켜진 구성에서, 보류돼 있던 출발 신호를 터치가 뒤늦게
                    # 확정하는 경로. 여기서도 바로 달리지 않고 영점을 먼저 지난다.
                    if self._interlock.poll():
                        self._zeroing.begin(now, "터치 확인")
                    if self._machine.state is not DriveState.IDLE:
                        self._zeroing.rearm()   # 움직였다 → 다음 출발엔 다시 잰다

                # 수동 모드: FSM을 밟지 않고 컨트롤러 명령을 직접 적용한다.
                # 인지(위 추론)는 계속 돌린다 — 오버레이로 카메라 조준 확인용.
                if self.manual_mode:
                    command = self._controller.drive_command(
                        self._steer_left_limit, self._steer_right_limit)
                    state_label = "MANUAL"
                else:
                    reading = self._imu.read()
                    self.last_imu = reading      # 상태 API 노출용 (yaw 부호 확인)
                    command = self._machine.step(frame, reading, now)
                    state_label = self._machine.state.value
                    # 재정향 추적 — 국면 교대와 회전량을 남긴다. 완료는 시각이므로
                    # 회전량은 진단용이지 성공 기준이 아니다(상한 판정에만 쓰인다).
                    # 순수 계층은 I/O를 못 하므로 여기(계측 계층)에서만 찍는다.
                    # ※ config·params 어느 쪽도 참조하지 않는다 — 0804에 이 줄만 옛 키
                    #   (target_yaw_deg)를 붙들고 있다가 재정향 진입 순간 KeyError로
                    #   주행 루프를 통째로 죽였다. 계측이 주행을 죽이면 안 되므로
                    #   여기서는 context와 reading만 읽는다(둘 다 이미 손에 있다).
                    # 후진 복구 추적 — 상태 전이가 아니라 가드 안의 명령이라 [FSM]
                    # 로그에 안 나온다. 로그가 없으면 '경계 소실이 안 떴다'는 관찰에서
                    # 후진이 실제로 걸린 것인지 장면이 달랐던 것인지 구분할 수 없다.
                    if (command.throttle_pct < 0
                            and self._machine.state is not DriveState.DEAD_END):
                        percep = self.last_frame
                        logger.info("[후진복구] %d%% · 인도 %s · 장애물 %s",
                                    command.throttle_pct,
                                    "안보임" if percep is None or not percep.sidewalk_detected
                                    else "보임",
                                    "있음" if percep is not None
                                    and percep.has_blocking_obstacle() else "없음")

                    if self._machine.state is DriveState.DEAD_END and tick % 5 == 0:
                        context = self._machine.context
                        entry = context.dead_end_entry_yaw_deg
                        spun = ("yaw 없음" if reading is None or entry is None
                                else f"회전 {reading.yaw_deg - entry:+.0f}°")
                        logger.info("[재정향] %s · 국면=%s · 방향=%s · 경과 %.1f초",
                                    spun, context.dead_end_phase.name,
                                    "좌" if context.dead_end_direction < 0 else "우",
                                    now - context.entered_at)
                apply_command(self._motor, self._servo, command)
                if self._comms is not None:
                    # 명령 result의 COMPLETED는 실제 하드웨어 쓰기 뒤에만 나간다.
                    self._comms.poll_after_step(self._machine, command,
                                                camera_ok=(image is not None))
                self.last_command = command
                self.last_frame = frame
                self._append_trace(now, command, frame, reading if not self.manual_mode
                                   else None)
                if self._pending_stop is not None:
                    # 정지를 만든 프레임까지 창에 들어온 뒤에 분석한다 (_log_transition 참조)
                    self._analyze_stop(*self._pending_stop)
                    self._pending_stop = None

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

                # 수동 조작 중 파손 보고 — 자동 경로는 INSPECT 정차 판정이 확정하지만
                # 수동에는 그 정차가 없다(FSM이 IDLE이라 INSPECT로 가지 않는다).
                # 미설치와 같은 이유로 여기도 붙인다: 사람이 몰고 다니며 파손을
                # 수집하는 것이 이 도구의 정당한 용법이다.
                if self.manual_mode:
                    self._report_manual_defect(now)

                # 횡단보도 점자블록 미설치 보고 — main.py와 같은 규칙(연속 프레임 확정 +
                # 쿨다운). 수동 모드에서도 남긴다: 사람이 몰고 다니며 미설치 구역을
                # 수집하는 것이 이 도구의 정당한 용법이고, 판정은 주행 상태와 무관하다
                if frame is not None and self._crosswalk_gate.should_report(
                        frame.braille_missing_at_crosswalk, now):
                    gps = self._gps.status()
                    local_image = reporting.write_report(
                        self._report_dir, reporting.KIND_MISSING,
                        gps, image=image,
                        save_images=self._save_images,
                        extra={"note": "횡단보도-인도 이음부 점자블록 미설치"})
                    # 서버 보고도 main.py와 같다 — 게이트를 공유한다(프레임마다
                    # event_id가 달라 서버 멱등 처리로는 중복을 못 묶는다).
                    server = "통신 꺼짐 — 로컬 기록만"
                    if self._comms is not None:
                        try:
                            self._comms.report_missing_braille(image)
                            server = "서버 전송 요청"
                        except Exception:
                            logger.exception("[미설치] 서버 보고 실패 — 주행은 계속한다")
                            server = "전송 실패 — 로그 확인"
                    self._note_report(
                        reporting.KIND_MISSING, "미설치", image, "주행 카메라",
                        gps, local_image, server,
                        detail="횡단보도-인도 이음부 점자블록 미설치")

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

    def _note_report(self, kind: str, label: str, image, camera: str,
                     gps: dict, local_image, server: str, detail: str = "") -> None:
        """보고 1건을 화면 이력에 남긴다 — 실제로 보낸 사진을 그대로 들고.

        현장에서 '보고가 나갔나'를 확인할 방법이 로그뿐이면 폰으로 대시보드만 보고
        있는 사람은 알 수가 없다. 그래서 판정·좌표·전송 상태에 **그때 보낸 프레임**을
        붙여 둔다: 오탐이면 화면에서 바로 보이고, 잘 찍혔으면 안심하고 계속 간다.

        사진은 서버로 간 것과 같은 원본을 별도 JPEG으로 인코딩한 것이다(증거 파일을
        다시 읽지 않는다 — 업로드 후 지워질 수도 있고, 여기서 파일 I/O를 하면 주행
        루프가 그만큼 늦는다). 인코딩 실패는 이력만 사진 없이 남긴다.
        """
        self._report_seq += 1
        seq = self._report_seq
        jpeg = None
        if image is not None:
            try:
                ok, buffer = self._cv2.imencode(
                    ".jpg", image, [self._cv2.IMWRITE_JPEG_QUALITY, PREVIEW_JPEG_QUALITY])
                jpeg = buffer.tobytes() if ok else None
            except Exception:
                logger.warning("[보고] 이력 사진 인코딩 실패 — 이력만 남긴다", exc_info=True)
        gps = gps or {}
        entry = {
            "seq": seq,
            "kind": kind,
            "label": label,
            "at": time.strftime("%H:%M:%S"),
            "camera": camera,
            "detail": detail,
            "server": server,
            "fix": bool(gps.get("fix")),
            "lat": gps.get("lat"),
            "lon": gps.get("lon"),
            "sats": gps.get("sats"),
            "local_image": local_image,
            "has_image": jpeg is not None,
            "bytes": len(jpeg) if jpeg else 0,
        }
        reports = ([entry] + self.recent_reports)[:REPORT_HISTORY]
        # 이력에서 밀려난 사진은 같이 버린다 — 안 그러면 주행 내내 메모리에 쌓인다.
        jpegs = {r["seq"]: self._report_jpegs.get(r["seq"]) for r in reports}
        if jpeg is not None:
            jpegs[seq] = jpeg
        self._report_jpegs = jpegs
        self.recent_reports = reports

    def _send_defect_to_server(self, worker, verdict, tag: str) -> str:
        """파손 1건을 통신 런타임에 넘긴다 → 화면에 띄울 전송 상태 문자열.

        돌려주는 값은 '업로드 완료'가 아니라 '워커에 태웠다'다 — 사진 업로드는 통신
        런타임의 백그라운드 스레드가 하고 이 도구는 그 완료를 관측하지 않는다(관측하려면
        outbox·evidence를 들여다봐야 하는데, 그건 주행 루프가 할 일이 아니다).
        """
        if self._comms is None:
            return "통신 꺼짐 — 로컬 기록만"
        image = worker.latest_image
        if image is None:
            logger.warning("%s 원본 프레임이 없어 서버 보고를 건너뛴다", tag)
            return "원본 프레임 없음 — 전송 안 함"
        try:
            self._comms.report_inspection(
                verdict, image, worker.latest_captured_at or utc_now())
        except Exception:
            logger.exception("%s 결함 보고 실패 — 주행은 계속한다", tag)
            return "전송 실패 — 로그 확인"
        return "서버 전송 요청"

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

    def _report_manual_defect(self, now: float) -> None:
        """수동 조작 중 파손 1건을 보고한다 — 로컬 events.jsonl + 서버(사진 포함).

        자동 모드의 확정은 INSPECT 정차 후 _record_inspection이 하고 이 함수는 그
        경로를 대신한다(수동에서는 FSM이 IDLE이라 INSPECT로 가지 않는다). 판정·사진은
        같은 워커의 같은 필드를 쓰므로 서버로 나가는 payload는 자동 경로와 동일하다 —
        `source`만 로컬 기록에서 구분된다.

        게이트를 로컬·서버 **양쪽에** 건다. 자동 경로는 서버 보고를 로컬 쿨다운과
        분리하지만(디스크 정책이 결함 유실이 되면 안 된다), 여기서는 상시 판별이
        파손 블록 앞에 서 있는 동안 계속 참이라 게이트 없이는 5Hz로 event와 사진
        업로드가 쏟아진다. 중복 방지의 책임이 FSM에서 이 게이트로 옮겨온 것이다.

        게이트는 **판정이 갱신된 틱에만** 먹인다: 주행 루프(10Hz)가 워커(5Hz)보다
        빠르므로 매 틱 먹이면 판정 하나가 연속 2프레임으로 세어져 확정이 무의미해진다
        (_report_trace_detection의 계측이 _counted_verdict_at으로 막는 것과 같은 함정).
        """
        worker = self._inspect_worker
        if worker is None:
            return
        verdict = worker.latest_verdict
        if verdict is None or now - worker.verdict_at > INSPECT_STALE_SEC:
            # 낡은 판정으로 보고하지 않는다 — 검사 카메라가 멈춘 뒤 마지막 불량
            # 판정이 쿨다운마다 재보고되면 있지도 않은 결함이 서버에 쌓인다.
            return
        if worker.verdict_at == self._manual_defect_verdict_at:
            return
        self._manual_defect_verdict_at = worker.verdict_at
        if not self._manual_defect_gate.should_report(bool(verdict.is_defect), now):
            return
        logger.info("[수동] 파손 보고 — %s", verdict.reason)
        gps = self._gps.status()
        local_image = reporting.write_report(
            self._report_dir, reporting.KIND_DEFECT, gps,
            image=None, save_images=self._save_images,
            extra={"reason": verdict.reason, "bands": verdict.band_count,
                   "defect_bands": verdict.defect_band_count,
                   "partials": verdict.partial_count,
                   "source": "webtest-manual"})
        server = self._send_defect_to_server(worker, verdict, "[수동]")
        self._note_report(
            reporting.KIND_DEFECT, "파손 (수동)", worker.latest_image,
            "검사 카메라", gps, local_image, server, detail=verdict.reason)

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
        # 파손 보고(좌표 + GPS 태깅) — main.py와 같은 경로·같은 형식. 로컬에는 사진을
        # 복사하지 않는다: 서버로 가는 증거 사진은 통신 런타임이 var/evidence 에 규격대로
        # 쓰고, 그 프레임 자체는 아래 _note_report 가 대시보드에 띄운다.
        gps = self._gps.status()
        local_image = None
        if verdict.is_defect and self._defect_gate.should_report(True, now):
            local_image = reporting.write_report(
                self._report_dir, reporting.KIND_DEFECT, gps,
                image=None, save_images=self._save_images,
                extra={"reason": verdict.reason, "bands": verdict.band_count,
                       "defect_bands": verdict.defect_band_count,
                       "partials": verdict.partial_count,
                       "source": "webtest"})
        # 서버 보고 — 로컬 쿨다운과 분리한다(main.py 와 같은 이유: 디스크 정책 하나가
        # 결함 유실이 되면 안 된다. 서버 중복은 event_id 멱등 처리가 받는다).
        # 사진은 워커가 래치한 **원본** 프레임이고 시각은 그 캡처 직전 값이다.
        if verdict.is_defect:
            server = self._send_defect_to_server(worker, verdict, "[INSPECT]")
            self._note_report(
                reporting.KIND_DEFECT, "파손 (INSPECT 정차)", worker.latest_image,
                "검사 카메라", gps, local_image, server, detail=verdict.reason)
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

    @app.get("/report/{seq}.jpg")
    async def report_image(seq: int):
        """보고 이력에 붙은 사진 — 그 건에서 실제로 서버로 보낸 프레임이다.

        이력에서 밀려나면 사진도 함께 버려지므로 404 가 정상 응답이다(화면은
        has_image 를 보고 링크를 걸지만, 그 사이 새 보고가 들어올 수 있다).
        """
        jpeg = session._report_jpegs.get(seq)
        if jpeg is None:
            return JSONResponse({"error": "만료된 보고 사진"}, status_code=404)
        return Response(content=jpeg, media_type="image/jpeg")

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
    config_root = os.path.dirname(os.path.abspath(args.config))
    # 이번 실행의 기록 자리를 여기서 정한다 — 콘솔 로그도 트레이스와 **같은
    # 디렉터리**에 남겨야 사후 분석이 한 곳에서 끝난다. 종전에는 콘솔이 터미널
    # 스크롤백에만 있어서, 주행이 끝나고 창을 닫으면 그대로 사라졌다.
    run_dir = make_run_dir(config_root)
    console = logging.FileHandler(os.path.join(run_dir, "console.log"),
                                  encoding="utf-8")
    console.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logging.getLogger().addHandler(console)
    logger.info("기록: %s", run_dir)
    # 출발 인터록은 통신보다 먼저 만든다 — 서버 START_MISSION 도 이 게이트를
    # 지나야 하므로 통신 런타임이 **같은 객체**를 받아야 한다.
    interlock = StartInterlock.from_config(config["hardware"].get("touch"))
    comms_runtime = None
    if args.comms_config:
        # 통신 의존성은 옵션을 준 경로에서만 import한다 — paho·credential 없이도
        # 대시보드 단독 사용이 종전 그대로 된다 (main.py 와 같은 규칙).
        from communication import CommunicationRuntime
        comms_runtime = CommunicationRuntime.build(
            args.comms_config, config, live=args.live, start_interlock=interlock)
        comms_runtime.start()
        logger.warning("MQTT 원격 미션 모드 — 서버 START_MISSION 과 웹 '시작' 버튼이 "
                       "같은 FSM을 민다")

    session = DrivingSession(config, config_root, live=args.live,
                             comms_runtime=comms_runtime, start_interlock=interlock,
                             run_dir=run_dir)
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
