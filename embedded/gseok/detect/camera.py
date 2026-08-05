"""카메라 캡처와 증거 이미지 저장 — USB.  [F-004]

캡처는 driving_test/camera_vision.py 에서 가져왔다(원본은 읽기 전용).
⚠️ 이 프로세스가 /dev/video* 를 소유한다. driving_test 앱과 동시에 띄우지 말 것.
원본은 박스·텍스트를 그린 annotated 프레임을 저장하지만, 증거는 원본 프레임을 저장한다 —
서버가 다시 판독할 수 있어야 하고 우리가 그린 것은 판독을 방해한다.
개발 PC 에 cv2 나 카메라가 없어도 예외를 올리지 않는다. health() 가 FAULT 를 말한다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §5.3·§8.3
"""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path

from comms.codec import utc_now

log = logging.getLogger(__name__)

#: 연속 읽기 실패가 이만큼 쌓이면 장치가 빠진 것으로 본다 (계약 §5.3).
READ_FAILURE_LIMIT = 10
#: 마지막 프레임이 이보다 오래되면 DEGRADED.
STALE_SEC = 2.0

DEFAULTS = {
    "camera_index": 0,
    "camera_width": 640,
    "camera_height": 480,
    "evidence_dir": "var/evidence",
    #: 계약 §8 — JPEG 최대 500KB. 장변 1280px, 품질 80 에서 시작해 넘으면 낮춰 재인코딩한다.
    "evidence_max_bytes": 500 * 1024,
    "evidence_long_edge_px": 1280,
    "evidence_jpeg_quality": 80,
    "evidence_min_jpeg_quality": 40,
}


def _open_capture(index: int, width: int, height: int):
    """기본 캡처 팩토리. cv2 가 없는 개발 PC 에서는 여기서 실패한다."""
    import cv2

    capture = cv2.VideoCapture(index)
    capture.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    capture.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    if not capture.isOpened():
        raise RuntimeError(f"cannot open camera {index}")
    return capture


class Camera:
    """최신 프레임 한 장을 들고 있고, 증거 이미지를 계약 규격으로 저장한다."""

    def __init__(self, config: dict = None, *, capture_factory=_open_capture, imaging=None,
                 monotonic=time.monotonic, now=utc_now) -> None:
        settings = dict(DEFAULTS)
        settings.update({k: v for k, v in (config or {}).items() if k in DEFAULTS})
        self._settings = settings
        self._capture_factory = capture_factory
        #: cv2 대역. 테스트가 가짜 인코더를 넣어 규격 로직만 시험할 수 있다.
        self._imaging = imaging
        self._monotonic = monotonic
        self._now = now

        self._lock = threading.Lock()
        self._capture = None
        self._thread = None
        self._running = False
        self._frame = None
        self._frame_at = None
        self._failures = 0

    # -- 수명 ------------------------------------------------------------

    def start(self) -> "Camera":
        if self._running:
            return self
        self._running = True
        settings = self._settings
        try:
            self._capture = self._capture_factory(
                settings["camera_index"], settings["camera_width"], settings["camera_height"]
            )
        except Exception as exc:
            log.warning("camera: cannot open device %s (%s)", settings["camera_index"], exc)
            self._capture = None
        self._thread = threading.Thread(target=self._read_loop, name="camera", daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        self._running = False
        capture, self._capture = self._capture, None
        if capture is not None:
            try:
                capture.release()
            except Exception:
                pass

    def _read_loop(self) -> None:
        while self._running:
            if self._capture is None:
                time.sleep(0.5)
                continue
            try:
                ok, frame = self._capture.read()
            except Exception as exc:
                log.warning("camera: read failed (%s)", exc)
                ok, frame = False, None
            if not ok or frame is None:
                with self._lock:
                    self._failures += 1
                time.sleep(0.05)
                continue
            with self._lock:
                self._frame = frame
                self._frame_at = self._monotonic()
                self._failures = 0
            time.sleep(0.03)  # ~30fps

    # -- 읽기 ------------------------------------------------------------

    def read(self):
        """추론용 최신 프레임. 없으면 None."""
        with self._lock:
            return self._frame

    def capture_evidence(self, frame) -> tuple:
        """증거 이미지를 저장하고 (path, meta) 를 돌려준다.

        meta 는 events.report_incident() 가 그대로 쓰는 captured_at·image_width·
        image_height 다 (계약 §8.3). 저장에 실패하면 (None, meta) 다 — 이미지가 없어도
        결함 event 자체는 나가야 한다.
        """
        captured_at = self._now()
        if frame is None:
            # 프레임이 없으면 크기도 모른다. 계약 §3.1 — 모르는 값에 0 을 지어내지만,
            # 여기까지 오는 경로는 '이미지 없이 event 만' 이라 서버도 0 을 크기로 읽지 않는다.
            return None, {"captured_at": captured_at, "image_width": 0, "image_height": 0}
        # 인코딩이 실패해도 원본 크기는 안다 — 아는 값을 0 으로 보고하지 않는다.
        height, width = frame.shape[0], frame.shape[1]
        cv2 = self._imaging or self._load_imaging()
        if cv2 is None:
            return None, {
                "captured_at": captured_at,
                "image_width": width,
                "image_height": height,
            }

        settings = self._settings
        long_edge = settings["evidence_long_edge_px"]
        if max(height, width) > long_edge:
            scale = long_edge / float(max(height, width))
            width, height = int(width * scale), int(height * scale)
            frame = cv2.resize(frame, (width, height))

        quality = settings["evidence_jpeg_quality"]
        blob = None
        while quality >= settings["evidence_min_jpeg_quality"]:
            ok, encoded = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), quality])
            if not ok:
                break
            blob = bytes(encoded)
            if len(blob) <= settings["evidence_max_bytes"]:
                break
            # 계약 상한을 넘었다 — 품질을 낮춰 다시 굽는다. 서버가 413 을 줄 이유를 없앤다.
            quality -= 10
            blob = None

        meta = {
            "captured_at": captured_at,
            "image_width": width,
            "image_height": height,
        }
        if blob is None:
            log.warning("camera: could not fit the evidence under the size limit")
            return None, meta
        try:
            directory = Path(settings["evidence_dir"])
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / f"incident-{captured_at.replace(':', '').replace('.', '-')}.jpg"
            path.write_bytes(blob)
        except OSError as exc:
            log.warning("camera: cannot write the evidence image (%s)", exc)
            return None, meta
        return path, meta

    def _load_imaging(self):
        try:
            import cv2
        except Exception as exc:
            log.warning("camera: OpenCV is not available (%s)", exc)
            return None
        self._imaging = cv2
        return cv2

    # -- 보고 ------------------------------------------------------------

    def health(self) -> str:
        """계약 §5.3 — Camera Open·최근 Frame·연속 읽기 실패로 판정한다."""
        if not self._running:
            return "UNKNOWN"
        if self._capture is None:
            return "FAULT"
        with self._lock:
            failures = self._failures
            frame_at = self._frame_at
        if failures >= READ_FAILURE_LIMIT:
            return "FAULT"
        if frame_at is None:
            return "DEGRADED"  # 열렸는데 아직 한 장도 못 받았다
        idle = self._monotonic() - frame_at
        if idle > STALE_SEC or failures:
            return "DEGRADED"
        return "OK"


# --------------------------------------------------------------------------
# 모듈 싱글턴 — main.py 가 configure() 로 설정하고 start() 로 장치를 연다.
# --------------------------------------------------------------------------

_config = {}
_camera = None


def configure(config: dict = None) -> None:
    """설정만 받아 둔다. 장치는 건드리지 않는다."""
    global _config
    _config = dict(config or {})


def start() -> None:
    global _camera
    if _camera is None:
        _camera = Camera(_config)
    _camera.start()


def stop() -> None:
    if _camera is not None:
        _camera.stop()


def read():
    """추론용 프레임. 기동 전이거나 아직 한 장도 못 받았으면 None."""
    if _camera is None:
        return None
    return _camera.read()


def capture_evidence(frame) -> tuple:
    """(path, meta). 기동 전이면 저장하지 않는다."""
    if _camera is None:
        return None, {"captured_at": utc_now(), "image_width": 0, "image_height": 0}
    return _camera.capture_evidence(frame)


def health() -> str:
    """state.health.camera — Camera Open·최근 Frame·연속 읽기 실패로 판정 (계약 §5.3).

    기동 전에는 UNKNOWN 이다. 계약상 UNKNOWN 은 '검사 전' 이라는 뜻이라
    지금 상태를 정확히 나타낸다 — 가짜 OK 를 돌려주면 안 된다.
    """
    if _camera is None:
        return "UNKNOWN"
    return _camera.health()
