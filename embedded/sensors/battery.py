"""배터리 — 잔량 % 읽기 + 상태 판정 (18650×4).  [F-007]"""

# TODO Battery 클래스
#   - read() -> battery dict (telemetry.battery 에 그대로 들어감)
#     valid, percent, state, source(MEASURED | ESTIMATED)
#     측정 회로 없으면 가동시간 기반 추정(ESTIMATED)
#   - 상태 판정 (계약 §9, 히스테리시스):
#     NORMAL ≥20% / LOW <20% / CRITICAL <10%(TBD) / 복귀 RECOVERED ≥25%
#   - 상태가 '전이'된 순간을 감지해서 알림 → events/battery 발행은 main→comms


def read() -> dict:
    """RobotState.set_battery() 에 그대로 넘어가는 읽기 결과.

    측정 회로가 붙기 전이라 아직 못 믿는다 — valid=False 면 잔량은 null 이고
    등급은 UNKNOWN 이다 (§5.5). 회로가 붙으면 percent 와 함께
    source=MEASURED(가동시간 추정이면 ESTIMATED)를 채운다.
    """
    return {"valid": False, "percent": None, "state": "UNKNOWN"}


def health() -> str:
    """state.health.battery — BMS·ADC 읽기 성공·최근 측정값으로 판정 (계약 §5.3).

    battery.state 와 다른 축이다: 측정 장치가 멀쩡하고 잔량만 낮으면
    battery.state=LOW / health.battery=OK 다 (계약 §5.5).
    하드웨어가 붙기 전까지 UNKNOWN — '검사 전' 이라는 뜻이라 지금 상태와 맞는다.
    """
    return "UNKNOWN"
