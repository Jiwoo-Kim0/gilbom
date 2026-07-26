"""배터리 — 잔량 % 읽기 + 상태 판정 (18650×4).  [F-007]"""

# TODO Battery 클래스
#   - read() -> battery dict (telemetry.battery 에 그대로 들어감)
#     valid, percent, state, source(MEASURED | ESTIMATED)
#     측정 회로 없으면 가동시간 기반 추정(ESTIMATED)
#   - 상태 판정 (계약 §9, 히스테리시스):
#     NORMAL ≥20% / LOW <20% / CRITICAL <10%(TBD) / 복귀 RECOVERED ≥25%
#   - 상태가 '전이'된 순간을 감지해서 알림 → events/battery 발행은 main→comms
