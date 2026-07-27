"""장애물 감지 — YDLidar X2 (2D, USB serial). 판정만 하고 정지 실행은 main → motor.  [F-003]"""

# TODO Safety 클래스
#   - __init__(cfg): LiDAR 시리얼 연결, 스캔 시작
#   - check() -> (obstacle: bool, distance_m: float)
#     전방 부채꼴에서 최소 거리 추출, stop_distance_m 미만이면 obstacle=True
#   - 해제 판정: 장애물 사라짐 → SAFETY_CLEARED 조건 제공 (auto_resume 판단은 main)
#   - distance_m 은 events/safety 의 measurement.distance_m 으로 그대로 사용됨
#   - LiDAR 통신 두절 시 LIDAR_FAULT 를 알릴 수 있어야 함 (health 반영)


def health() -> str:
    """state.health.lidar — Serial·SDK 연결·최근 Scan·연속 Parse 실패로 판정 (계약 §5.3).

    하드웨어가 붙기 전까지 UNKNOWN 이다. 계약상 UNKNOWN 은 '검사 전' 이라는 뜻이라
    지금 상태를 정확히 나타낸다 — 가짜 OK 를 돌려주면 안 된다.
    """
    return "UNKNOWN"
