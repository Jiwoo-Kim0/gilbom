"""점자블록 결함 추론 — TensorRT + YOLO.  [F-004, F-005]"""

# TODO Detector 클래스
#   - __init__(cfg): models/ 의 .engine 로드
#   - infer 전처리 ⚠️ 임의로 정하지 말 것 — 학습 때 쓴 설정을 그대로 때려박을 것
#     (imgsz · 정규화 범위 · RGB/BGR · letterbox 패딩). 학습과 1픽셀이라도 다르면
#     모델은 돌아가는데 탐지만 조용히 틀림. 학습 담당에게 best.pt + export 명령
#     + 클래스 순서 받아서 확정.
#   - infer(frame) -> result | None
#     result 필드 (incident payload 에 그대로 들어감, 계약 §8):
#       block_type: LINEAR | DOT | UNKNOWN
#       defect_type: MVP 는 DAMAGED 만 (확장 enum 은 심화)
#       confidence: 0.0~1.0 (threshold 미만이면 None)
#       bbox_norm: [x_min, y_min, x_max, y_max] 0~1 정규화
#   - NORMAL 탐지는 이벤트 발행 안 함 — 카운트만 유지
#     (mission 종료 시 normal_detection_count 로 보고)
#   - 중복 억제: 같은 지점 연속 프레임 재발행 방지 (계약 §8 중복 정책 — 5m/30초 등 팀 확정)
