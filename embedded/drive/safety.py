"""장애물 감지 — YDLidar X2 (2D, USB serial). 판정만 하고 정지 실행은 main → motor.  [F-003]"""

# TODO Safety 클래스
#   - __init__(cfg): LiDAR 시리얼 연결, 스캔 시작
#   - check() -> (obstacle: bool, distance_m: float)
#     전방 부채꼴에서 최소 거리 추출, stop_distance_m 미만이면 obstacle=True
#   - 해제 판정: 장애물 사라짐 → SAFETY_CLEARED 조건 제공 (auto_resume 판단은 main)
#   - distance_m 은 events/safety 의 measurement.distance_m 으로 그대로 사용됨
#   - LiDAR 통신 두절 시 LIDAR_FAULT 를 알릴 수 있어야 함 (health 반영)
