"""카메라 캡처 — USB/CSI.  [F-004]"""

# TODO Camera 클래스
#   - __init__(cfg): VideoCapture 오픈 (OpenCV)
#   - read() -> frame: 추론용 프레임 반환
#   - save_evidence(frame) -> (path, meta): 증거 이미지 저장
#     규격: 장변 1280px 리사이즈, JPEG q80, 최종 500KB 이하 (계약 §12.1)
#     meta: captured_at(UTC), image_width, image_height
