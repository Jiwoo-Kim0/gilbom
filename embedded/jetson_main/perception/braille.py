"""점자블록 파손 판정 — band_argmax 규칙의 순수 이식 + 검사기 어댑터.

판정 로직은 tools/braille_detect/jetson_infer.py의 decide()/_band_argmax()/_iou()를
ultralytics Boxes 의존만 벗겨 그대로 옮긴 것이다. 원본의 클래스 존재 검사
(normal/defect_full이 모델에 없으면 조기 반환)만 뺐다 — 여기서는 클래스 구성이
config로 고정된 3클래스 모델 하나뿐이고, 빠져도 판정 결과는 같다(그 경우
group_bands가 비어 '띠 검출 없음'으로 흘러 동일하게 False). 알고리즘을 바꾸지 말 것:
normal과 defect_full은 같은 띠에 붙는 배타적 라벨이라 모델이 둘을 동시에 내며,
"defect_full 박스가 있는가"로 세면 실사 불량률이 66.7%로 과대집계된다
(묶음 argmax는 14.3%, 육안 실측 실제값 15.3% — models/README.md).

계층 구분은 segmentation.py와 동일:
  - 순수 계층: Detection 튜플 목록 → BrailleVerdict. 개발 PC에서 테스트.
  - 어댑터(BrailleInspector): 이미지 → YOLO 추론 → Detection 변환 → 순수 판정.
    ultralytics가 필요하므로 보드에서만 생성한다.

임계값(iou_same_band, band_conf, defect_conf, min_defects)은 전부
config.yaml perception.braille에서 온다 — 기본값·운영점 근거는 models/README.md.
"""

import time
from dataclasses import dataclass
from typing import Callable, List, Sequence, Tuple

# Detection: (신뢰도, 클래스명, (x1, y1, x2, y2)) — 원본의 boxes 행 1개에 대응
Detection = Tuple[float, str, Tuple[float, float, float, float]]

CLASS_NORMAL = "normal"
CLASS_DEFECT_FULL = "defect_full"
CLASS_DEFECT_PARTIAL = "defect_partial"


@dataclass(frozen=True)
class BrailleParams:
    """판정 튜닝값 묶음 — config.yaml perception.braille에서만 온다."""

    iou_same_band: float    # 같은 띠로 묶는 IoU (원본 IOU_SAME_BAND)
    band_conf: float        # 띠 판정 운영점 — 0=순수 argmax가 F1 최대, 근거 없이 올리지 말 것
    defect_conf: float      # defect_partial 승격 전용 임계 (띠 판정에는 무영향)
    min_defects: int        # 승격 최소 개수 — 실제 하한은 max(2, 이 값) (원본 그대로)

    @classmethod
    def from_config(cls, braille: dict) -> "BrailleParams":
        # 이 구현은 band_argmax 전용이다 — config의 decision_rule을 조용히 무시하면
        # '설정을 바꿨는데 동작이 같은' 사고가 나므로 기동 시 즉시 실패시킨다.
        rule = str(braille.get("decision_rule", "band_argmax"))
        if rule != "band_argmax":
            raise ValueError(
                f"지원하지 않는 decision_rule '{rule}' — 이 구현은 band_argmax 전용"
                " (다른 규칙은 tools/braille_detect/jetson_infer.py 참고)")
        return cls(
            iou_same_band=float(braille["iou_same_band"]),
            band_conf=float(braille["band_conf"]),
            defect_conf=float(braille["defect_conf"]),
            min_defects=int(braille["min_defects"]),
        )


@dataclass(frozen=True)
class BrailleVerdict:
    """검사 1회의 판정 결과 — 3단계 통신(사진+위치 보고)의 페이로드가 된다."""

    timestamp: float
    is_defect: bool
    reason: str             # 사람이 읽는 판정 사유 (원본 문구 유지)
    band_count: int         # IoU로 묶은 띠(경쟁 예측 그룹) 수
    defect_band_count: int  # defect_full이 이긴 띠 수
    partial_count: int      # defect_conf를 넘은 국소 파손 검출 수


# ======================================================================
# 순수 계층 — 원본 jetson_infer.py의 이식 (알고리즘 동일, 자료형만 평문)
# ======================================================================

def _iou(a: Tuple[float, float, float, float],
         b: Tuple[float, float, float, float]) -> float:
    """축 정렬 박스 IoU — 원본 _iou() 그대로."""
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    union = ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)
    return inter / (union + 1e-9)


def group_bands(detections: Sequence[Detection], iou_same_band: float
                ) -> List[List[Detection]]:
    """normal/defect_full 검출을 같은 띠끼리 묶는다 — 원본 _band_argmax() 그대로.

    신뢰도 내림차순으로 훑으며 그룹 대표(첫 원소)와 IoU가 임계 이상이면 합류,
    아니면 새 그룹. 정렬 덕에 각 그룹의 첫 원소가 곧 최고 신뢰도(argmax 승자)다.
    """
    bands = [d for d in detections if d[1] in (CLASS_NORMAL, CLASS_DEFECT_FULL)]
    bands.sort(key=lambda d: -d[0])
    groups: List[List[Detection]] = []
    for det in bands:
        for group in groups:
            if _iou(det[2], group[0][2]) >= iou_same_band:
                group.append(det)
                break
        else:
            groups.append([det])
    return groups


def decide_band_argmax(detections: Sequence[Detection], params: BrailleParams,
                       timestamp: float) -> BrailleVerdict:
    """band_argmax 판정 — 원본 decide()의 band_argmax 분기 그대로.

    1차: 띠별 argmax 승자가 defect_full(신뢰도 ≥ band_conf)인 띠가 있으면 불량.
    2차: 띠가 전부 정상이어도 defect_partial(신뢰도 ≥ defect_conf)이
         max(2, min_defects)곳 이상이면 불량으로 승격.
    """
    groups = group_bands(detections, params.iou_same_band)
    parts = [d[0] for d in detections
             if d[1] == CLASS_DEFECT_PARTIAL and d[0] >= params.defect_conf]

    if not groups:
        reason = "검출 없음" if not detections else "띠 검출 없음"
        return BrailleVerdict(timestamp=timestamp, is_defect=False, reason=reason,
                              band_count=0, defect_band_count=0,
                              partial_count=len(parts))

    bad = [g[0] for g in groups
           if g[0][1] == CLASS_DEFECT_FULL and g[0][0] >= params.band_conf]
    if bad:
        note = f" +파손 {len(parts)}곳" if parts else ""
        reason = (f"불량 띠 {len(bad)}/{len(groups)} "
                  f"(최대 {max(conf for conf, _, _ in bad):.2f}){note}")
        return BrailleVerdict(timestamp=timestamp, is_defect=True, reason=reason,
                              band_count=len(groups), defect_band_count=len(bad),
                              partial_count=len(parts))

    # 띠는 정상으로 봤지만 국소 파손이 여러 곳이면 승격시킨다 (원본 그대로)
    if len(parts) >= max(2, params.min_defects):
        reason = f"국소 파손 {len(parts)}곳 (최대 {max(parts):.2f})"
        return BrailleVerdict(timestamp=timestamp, is_defect=True, reason=reason,
                              band_count=len(groups), defect_band_count=0,
                              partial_count=len(parts))

    reason = (f"정상 띠 {len(groups)}개"
              + (f", 파손 {len(parts)}곳" if parts else ""))
    return BrailleVerdict(timestamp=timestamp, is_defect=False, reason=reason,
                          band_count=len(groups), defect_band_count=0,
                          partial_count=len(parts))


# ======================================================================
# 어댑터 — 이미지 1장 → 추론 1회 → 판정 1개 (보드 전용)
# ======================================================================

class BrailleInspector:
    """models/braille_defect.pt 검사기. INSPECT 상태에서 main 루프가 1회 부른다.

    모델은 생성자에서 1회만 로드한다. TensorRT 엔진 전환 등 보드 최적화는
    보드 작업에서 한다 (tools/braille_detect/README.md 참고) — 여기서는 .pt 직접 추론.
    """

    def __init__(self, model_path: str, imgsz, conf: float, iou: float,
                 params: BrailleParams,
                 clock: Callable[[], float] = time.monotonic):
        from ultralytics import YOLO  # 보드 전용 — 개발 PC에서는 생성 자체를 안 한다

        self._model = YOLO(model_path)
        self._imgsz = imgsz
        self._conf = conf
        self._iou = iou
        self._params = params
        self._clock = clock

    def inspect(self, image_bgr) -> BrailleVerdict:
        """정지 상태에서 캡처한 프레임 1장을 판정한다."""
        return self.inspect_annotated(image_bgr)[0]

    def inspect_annotated(self, image_bgr) -> Tuple[BrailleVerdict, "object"]:
        """판정 + 검출 박스가 그려진 프레임(BGR)을 함께 돌려준다.

        관제 화면(웹 테스트)과 3단계 통신의 증빙 사진이 소비한다 — 판정 알고리즘은
        inspect()와 완전히 동일하다 (같은 추론 1회를 공유).
        """
        result = self._model.predict(image_bgr, imgsz=self._imgsz, conf=self._conf,
                                     iou=self._iou, verbose=False)[0]
        detections = _detections_from_result(result)
        verdict = decide_band_argmax(detections, self._params, timestamp=self._clock())
        return verdict, result.plot()


def _detections_from_result(result) -> List[Detection]:
    """ultralytics 결과 → Detection 목록 (클래스는 id가 아니라 이름으로 —
    원본 decide()가 이름 조회였으므로 클래스 순서 하드코딩이 필요 없다)."""
    boxes = result.boxes
    if boxes is None or len(boxes) == 0:
        return []
    xyxy = boxes.xyxy.tolist() if hasattr(boxes.xyxy, "tolist") else boxes.xyxy
    detections: List[Detection] = []
    for conf, cls_id, box in zip(boxes.conf, boxes.cls, xyxy):
        detections.append((float(conf), result.names[int(cls_id)],
                           (float(box[0]), float(box[1]), float(box[2]), float(box[3]))))
    return detections
