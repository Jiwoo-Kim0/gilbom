"""GPS — NEO-M8N, UART NMEA 9600 baud.  [F-001, F-006]"""

# TODO Gps 클래스
#   - __init__(cfg): pyserial 로 포트 오픈
#   - read() -> position dict (telemetry.position 에 그대로 들어감)
#     valid, lat, lon, accuracy_m(hdop 기반), heading_deg(course, 없으면 null)
#   - fix 미확보 → valid=False, 좌표는 null (더미 좌표는 개발 초기에만)


def read_position() -> dict:
    """telemetry.position 의 원재료 (계약 §6.4).

    하드웨어가 붙기 전이라 Fix 가 없다. 직전 좌표도 더미 좌표도 만들지 않는다 —
    GPS 가 끊긴 것과 그 자리에 서 있는 것은 지도에서 완전히 다른 뜻이다.
    heading_deg 는 RMC Course Over Ground 가 유효할 때만 채운다.
    """
    return {"valid": False, "lat": None, "lon": None, "heading_deg": None}


def health() -> str:
    """state.health.gps — Serial 연결·최근 NMEA·GPS Fix 로 판정 (계약 §5.3).

    하드웨어가 붙기 전까지 UNKNOWN 이다. 계약상 UNKNOWN 은 '검사 전' 이라는 뜻이라
    지금 상태를 정확히 나타낸다 — 가짜 OK 를 돌려주면 안 된다.
    """
    return "UNKNOWN"
