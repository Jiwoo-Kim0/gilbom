"""카메라 캡처 — USB/CSI.  [F-004]"""

# TODO Camera 클래스
#   - __init__(cfg): VideoCapture 오픈 (OpenCV)
#   - read() -> frame: 추론용 프레임 반환
#   - save_evidence(frame) -> (path, meta): 증거 이미지 저장
#     규격: 장변 1280px 리사이즈, JPEG q80, 최종 500KB 이하 (계약 §12.1)
#     meta: captured_at(UTC), image_width, image_height


def health() -> str:
    """state.health.camera — Camera Open·최근 Frame·연속 읽기 실패로 판정 (계약 §5.3).

    하드웨어가 붙기 전까지 UNKNOWN 이다. 계약상 UNKNOWN 은 '검사 전' 이라는 뜻이라
    지금 상태를 정확히 나타낸다 — 가짜 OK 를 돌려주면 안 된다.
    """
    return "UNKNOWN"
