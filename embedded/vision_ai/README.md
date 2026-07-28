# Tactile Paving Block Detection & Classification System (YOLOv11)
점자블럭 정상/불량 감지 및 판별 YOLOv11 모델 개발 프로젝트

---

## 📌 1. 프로젝트 개요 (Overview)

본 프로젝트는 **Jetson Orin Nano 엣지 디바이스** 환경에서 실시간으로 **점자블럭을 감지(Detection)** 하고, 해당 점자블럭이 **정상(Good)** 인지 **불량(Bad)** 인지 **판별(Classification)** 하는 YOLOv11 기반 파이프라인입니다.

`agent.md` 요구사항에 맞춰, 세부 폴리곤 라벨링(`불량전체`, `불량부분` 등) 데이터셋에서 **정상 점자블럭(Class 0: `good`)** 과 **불량 점자블럭(Class 1: `bad`)** 의 바운딩 박스를 추출하여 YOLOv11 학습에 최적화된 형태로 전처리하고 파이프라인을 구축하였습니다.

---

## 📁 2. 코드 및 디렉토리 구조 (Repository Structure)

```
yolo/
├── agent.md                        # 요구사항 정의 문서
├── dataset/                        # 원본 데이터셋 (images/good, bad, labels/good, bad)
├── dataset_preprocessor.py         # 원본 JSON 라벨 -> YOLO 포맷 변환 및 전처리 모듈
├── train_yolo.py                   # YOLOv11 모델 학습, 검증 및 엑스포트 스크립트
├── infer_demo.py                   # 추론 결과 시각화 및 Bounding Box 그리기도구
├── main.py                         # 전체 파이프라인 실행 메인 엔트리포인트
└── README.md                       # 프로젝트 종합 안내 문서
```

---

## 🎯 3. 데이터셋 구조 및 전처리 방식 (Dataset Preprocessing)

원본 JSON 데이터셋 구조:
- **Good (정상)**: 11,560개 이미지 & 라벨 (`is_defect` = `정상`)
- **Bad (불량)**: 5,838개 이미지 & 라벨 (`is_defect` = `불량전체` + `불량부분`)

전처리 모듈 (`dataset_preprocessor.py`) 동작 방식:
1. `good` 데이터셋: `정상` 다각형 영역의 [x_min, y_min, x_max, y_max]를 계산 후 YOLO 정규화 좌표로 변환 (`Class 0: good`)
2. `bad` 데이터셋: `불량전체` 다각형 영역의 [x_min, y_min, x_max, y_max]를 계산 후 YOLO 정규화 좌표로 변환 (`Class 1: bad`)
3. Train/Val 비율(80:20) 계층적 분할(Stratified Split) 적용 후 `dataset_yolo/` 디렉토리에 저장 및 `data.yaml` 생성

---

## 🚀 4. 실행 가이드 (How to Run)

### 4.1 파이프라인 일괄 실행 (`main.py`)
```bash
# 전체 데이터셋 전처리 및 학습 (Jetson Orin Nano용 기본 yolo11n 모델)
python main.py --epochs 30 --batch 16 --model yolo11n.pt

# 빠른 디버깅/테스트 모드 (클래스당 500개 샘플 사용)
python main.py --max-samples 500 --epochs 5
```

### 4.2 단계별 단독 실행

#### ① 데이터 전처리 (`dataset_preprocessor.py`)
```bash
python dataset_preprocessor.py --raw-dir ./dataset --out-dir ./dataset_yolo --val-ratio 0.2
```

#### ② 모델 학습 및 검증 (`train_yolo.py`)
```bash
python train_yolo.py --data ./dataset_yolo/data.yaml --model yolo11n.pt --epochs 30 --batch 16 --export onnx
```

#### ③ 추론 및 결과 시각화 (`infer_demo.py`)
```bash
# 단일 이미지 추론
python infer_demo.py --model runs/detect/tactile_block_yolo11/weights/best.pt --input dataset_yolo/images/val/sample.jpg

# 폴더 전체 추론
python infer_demo.py --model runs/detect/tactile_block_yolo11/weights/best.pt --input dataset_yolo/images/val --out-dir results/predictions
```

---

## ⚡ 5. Jetson Orin Nano 엣지 디바이스 배포 (Edge Deployment)

Jetson Orin Nano 단말에서 최고 속도와 최저 지연시간(Real-time FP16 / INT8)을 확보하기 위해 TensorRT Engine 포맷으로 엑스포트하여 사용합니다.

```bash
# Jetson Orin Nano 보드 환경에서 실행
yolo export model="runs/detect/tactile_block_yolo11/weights/best.pt" format=engine half=True imgsz=640
```

### Python 추론 스크립트 예시 (Jetson Orin Nano)
```python
from ultralytics import YOLO

# TensorRT 엔지 모델 로드
model = YOLO("runs/detect/tactile_block_yolo11/weights/best.engine")

# 카메라 스트림 또는 이미지 추론
results = model(source=0, stream=True, conf=0.25)
for r in results:
    for box in r.boxes:
        cls_id = int(box.cls[0])
        label = "정상(Good)" if cls_id == 0 else "불량(Bad)"
        print(f"Detected: {label}, Conf: {box.conf[0]:.2f}")
```
