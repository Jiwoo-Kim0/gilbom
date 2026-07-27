"""점자블록 결함 추론 — YOLO.  [F-004, F-005]

추론은 driving_test/camera_vision.py 에서 가져왔다(원본은 읽기 전용).
중복 제거가 여기 책임이다. 원본의 2.5초 캡처 쿨다운은 파일 저장 주기일 뿐이고,
계약이 말하는 '확정' 은 tracking·cooldown 을 통과한 관측이다 (§8.1, §11.3).
개발 PC 에 ultralytics·모델 파일이 없어도 예외를 올리지 않는다 — infer() 가 None 이다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §8.3·§11.3

⚠️ 전처리(imgsz·정규화·RGB/BGR·letterbox)는 학습 때 설정을 그대로 써야 한다.
   ultralytics 가 .pt 안의 설정을 따라가므로 여기서 임의로 손대지 않는다.
"""

from __future__ import annotations

import logging
import threading
import time

log = logging.getLogger(__name__)

#: incident.model 에 그대로 실린다 (계약 §8.3). config 의 detect.model_name/version 이 덮어쓴다.
MODEL_NAME = "tbs-braille-yolo"
MODEL_VERSION = "0.0.0-stub"

#: 모델 클래스명 → 계약 enum (§8.3).
#: ⚠️ 클래스 이름·순서가 아직 미확정이다. 확정되면 config.yaml 의 detect.classes 만 고친다 —
#:    코드를 고칠 일이 없도록 매핑을 이 한 곳에 격리해 둔다.
DEFAULT_CLASS_MAP = {
    "linear": {"block_type": "LINEAR", "defect_type": None},
    "linear_damaged": {"block_type": "LINEAR", "defect_type": "DAMAGED"},
    "dot": {"block_type": "DOT", "defect_type": None},
    "dot_damaged": {"block_type": "DOT", "defect_type": "DAMAGED"},
    "normal": {"block_type": "UNKNOWN", "defect_type": None},
    "damaged": {"block_type": "UNKNOWN", "defect_type": "DAMAGED"},
}

DEFAULTS = {
    "model_path": "models/best.pt",
    "confidence_threshold": 0.35,
    #: 추론 주기. main.py 의 detect 스레드가 이 값으로 돈다 — 메인 루프 tick 과 별개다.
    "interval_sec": 0.2,
    #: 같은 관측이 이만큼 연속으로 잡혀야 확정이다 (tracking).
    "confirm_frames": 3,
    #: 확정한 뒤 같은 종류를 다시 세지 않는 시간 (cooldown).
    "cooldown_sec": 30.0,
}


class Detector:
    """프레임 하나에서 확정된 관측 1건을 뽑는다. 확정 전이면 None.

    모델을 주입할 수 있어서 하드웨어·가중치 없이 매핑과 중복 제거를 시험할 수 있다.
    """

    def __init__(self, config: dict = None, *, model=None, class_map=None,
                 monotonic=time.monotonic) -> None:
        settings = dict(DEFAULTS)
        settings.update({k: v for k, v in (config or {}).items() if k in DEFAULTS})
        self._settings = settings
        self._model = model
        self._class_map = dict(class_map or DEFAULT_CLASS_MAP)
        self._monotonic = monotonic
        self._lock = threading.Lock()
        self._started = model is not None
        self._streak_key = None
        self._streak = 0
        self._confirmed_at = {}
        self._unmapped = set()

    # -- 수명 ------------------------------------------------------------

    def start(self) -> "Detector":
        """가중치를 올린다. 실패하면 모델 없이 뜨고 infer() 는 계속 None 이다."""
        self._started = True
        if self._model is not None:
            return self
        path = self._settings["model_path"]
        try:
            from ultralytics import YOLO

            self._model = YOLO(path)
        except Exception as exc:
            log.warning("detect: cannot load the model at %s (%s)", path, exc)
            self._model = None
        return self

    # -- 추론 ------------------------------------------------------------

    def infer(self, frame):
        """확정된 관측 1건 또는 None.

        돌려주는 dict 는 events.report_incident() 에 그대로 넘어간다 (§8.3).
        `normal` 이 True 면 결함이 아니라 정상 관측이고, 그건 event 가 아니라
        mission.result.normal_detection_count 로만 센다 (§11.3).
        """
        if self._model is None or frame is None:
            return None
        try:
            observation = self._best_observation(frame)
        except Exception:
            log.exception("detect: inference failed")
            return None
        if observation is None:
            with self._lock:
                self._streak_key, self._streak = None, 0
            return None
        if not self._confirm(observation):
            return None
        return observation

    def _best_observation(self, frame):
        """가장 확신하는 상자 하나를 계약 값으로 바꾼다. 임계값 미만이면 None."""
        threshold = self._settings["confidence_threshold"]
        results = self._model(frame, conf=threshold, verbose=False)
        if not results:
            return None
        boxes = getattr(results[0], "boxes", None)
        if boxes is None or not len(boxes):
            return None
        names = getattr(self._model, "names", {}) or {}

        best = None
        for box in boxes:
            confidence = float(box.conf[0])
            if confidence < threshold:
                continue
            if best is None or confidence > best[0]:
                best = (confidence, int(box.cls[0]), [float(v) for v in box.xyxy[0]])
        if best is None:
            return None
        confidence, class_id, xyxy = best
        class_name = names.get(class_id, str(class_id))
        mapped = self._mapped(class_name)
        height, width = frame.shape[0], frame.shape[1]
        return {
            "normal": mapped["defect_type"] is None,
            "block_type": mapped["block_type"],
            "defect_type": mapped["defect_type"],
            "confidence": min(1.0, max(0.0, confidence)),
            "bbox_norm": _normalized(xyxy, width, height),
        }

    def _mapped(self, class_name: str) -> dict:
        mapped = self._class_map.get(class_name)
        if mapped is not None:
            return mapped
        if class_name not in self._unmapped:
            self._unmapped.add(class_name)
            # 모르는 클래스에 결함 딱지를 붙이지 않는다 — 없는 사건을 만드는 쪽이 더 나쁘다.
            log.warning(
                "detect: %r is not in the class map; reporting it as a normal UNKNOWN block",
                class_name,
            )
        return {"block_type": "UNKNOWN", "defect_type": None}

    def _confirm(self, observation) -> bool:
        """tracking·cooldown 을 통과한 관측만 True. 프레임 수를 세는 게 아니다 (§11.3)."""
        key = (observation["block_type"], observation["defect_type"])
        now = self._monotonic()
        with self._lock:
            if key == self._streak_key:
                self._streak += 1
            else:
                self._streak_key, self._streak = key, 1
            if self._streak < self._settings["confirm_frames"]:
                return False
            last = self._confirmed_at.get(key)
            if last is not None and now - last < self._settings["cooldown_sec"]:
                return False
            self._confirmed_at[key] = now
            self._streak = 0
            return True

    def health(self) -> str:
        if not self._started:
            return "UNKNOWN"
        return "OK" if self._model is not None else "FAULT"


def _normalized(xyxy, width: int, height: int) -> list:
    """픽셀 [x1,y1,x2,y2] → 0.0~1.0 정규화. min <= max 를 보장한다 (계약 §8.3)."""
    if not width or not height:
        return [0.0, 0.0, 0.0, 0.0]
    x_min, y_min, x_max, y_max = xyxy
    values = [x_min / width, y_min / height, x_max / width, y_max / height]
    values = [min(1.0, max(0.0, value)) for value in values]
    return [
        min(values[0], values[2]),
        min(values[1], values[3]),
        max(values[0], values[2]),
        max(values[1], values[3]),
    ]


# --------------------------------------------------------------------------
# 모듈 싱글턴 — main.py 가 configure() 로 설정하고 start() 로 가중치를 올린다.
# --------------------------------------------------------------------------

_config = {}
_class_map = dict(DEFAULT_CLASS_MAP)
_detector = None


def configure(config: dict = None) -> None:
    """설정과 클래스 매핑만 받아 둔다. 가중치는 아직 올리지 않는다."""
    global _config, _class_map, MODEL_NAME, MODEL_VERSION
    section = dict(config or {})
    _config = section
    _class_map = dict(section.get("classes") or DEFAULT_CLASS_MAP)
    MODEL_NAME = section.get("model_name") or MODEL_NAME
    MODEL_VERSION = section.get("model_version") or MODEL_VERSION


def start() -> None:
    global _detector
    if _detector is None:
        _detector = Detector(_config, class_map=_class_map)
    _detector.start()


def interval_sec() -> float:
    """추론 주기. 설정을 읽는 곳은 여기 하나다 — main.py 가 상수를 들고 있지 않는다."""
    try:
        return float(_config.get("interval_sec", DEFAULTS["interval_sec"]))
    except (TypeError, ValueError):
        log.warning("detect: interval_sec is not a number; using the default")
        return DEFAULTS["interval_sec"]


def infer(frame=None):
    """확정된 관측 1건 또는 None. 가중치가 없으면 항상 None 이다."""
    if _detector is None:
        return None
    return _detector.infer(frame)
