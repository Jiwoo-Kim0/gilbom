"""카메라 캡처 — cv2.VideoCapture 얇은 래퍼 (실추론 전용).

cv2 import는 생성 시점으로 미룬다: 개발 PC에는 opencv가 없고(테스트는 mock 경로만
쓴다), 이 모듈이 import 됐다는 것만으로 의존성이 강제되면 안 되기 때문이다.
장치 문자열이 숫자면 인덱스로, 아니면 경로(/dev/video0)로 연다.

열기 실패는 RuntimeError로 즉시 죽인다 — 조립부(main.build_real_perception)의 방침이
'반쯤 눈먼 채 주행을 시작하는 것보다 낫다'이므로 fail-soft 진입점을 두지 않는다.
"""


class Camera:
    """단일 카메라. read()는 BGR 프레임(ndarray) 또는 실패 시 None.

    read()가 None을 돌려주면 상위(SegmentationProvider)도 None 프레임을 내고,
    FSM 공통 가드가 '인지 두절'로 정지시킨다 — 카메라 장애가 곧 안전 정지다.
    """

    def __init__(self, device: str):
        import cv2  # 실추론 경로에서만 로드 (개발 PC 미설치 — 보드에서 동작)

        self._cv2 = cv2
        source = int(device) if str(device).isdigit() else device
        # V4L2 백엔드 명시: /dev/v4l/by-id/... 같은 심볼릭 링크 경로는 백엔드
        # 자동 감지가 이미지 시퀀스로 오해해 열기 자체가 실패한다 (OpenCV 5 실측).
        self._capture = cv2.VideoCapture(source, cv2.CAP_V4L2)
        # MJPG 강제: 주행·검사 카메라가 USB 2.0 허브 하나를 공유한다 — 비압축
        # (YUYV)으로 두 대를 동시에 돌리면 대역폭이 모자라 프레임이 죽는다
        # (docs/HARDWARE.md, 이전 프로젝트 실측 교훈). 미지원 장치는 무시된다.
        self._capture.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
        if not self._capture.isOpened():
            raise RuntimeError(f"카메라를 열 수 없습니다: {device}")
        # V4L2 기본 내부 버퍼(수 프레임)를 줄인다: 소비(추론)가 캡처보다 느리면
        # read()가 '가장 오래된' 프레임을 돌려줘 상시 ~100ms+ 과거를 보게 된다.
        # 미지원 백엔드는 set()이 False를 돌려줄 뿐이라 무해하다.
        self._capture.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    def read(self):
        """최신 프레임 1장 (BGR ndarray). 실패 시 None."""
        ok, frame = self._capture.read()
        return frame if ok else None

    def close(self) -> None:
        self._capture.release()
