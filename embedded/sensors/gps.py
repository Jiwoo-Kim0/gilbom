"""GPS — NEO-M8N, UART NMEA 9600 baud.  [F-001, F-006]"""

# TODO Gps 클래스
#   - __init__(cfg): pyserial 로 포트 오픈
#   - read() -> position dict (telemetry.position 에 그대로 들어감)
#     valid, lat, lon, accuracy_m(hdop 기반), heading_deg(course, 없으면 null)
#   - fix 미확보 → valid=False, 좌표는 null (더미 좌표는 개발 초기에만)
