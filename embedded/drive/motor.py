"""구동 모터 제어 — DC GA25-370 + Motor Driver HAT (I2C 0x40, TB6612FNG, B채널).  [F-002]"""

# TODO Motor 클래스
#   - __init__(cfg): I2C 0x40 초기화, B채널 사용
#   - forward(speed): PWM 듀티로 속도 제어 (저속 순찰용)
#   - stop(): 어떤 상태에서도 즉시 정지 — EMERGENCY_STOP / 장애물 정지 경로
#     (예외 없이 항상 성공해야 하는 유일한 메서드)
