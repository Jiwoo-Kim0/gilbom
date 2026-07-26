"""조향 서보 제어 — MG996R + PCA9685 (I2C bus 7, addr 0x60, ch0).  [F-001, F-002]"""

# TODO Steering 클래스
#   - __init__(cfg): I2C bus 7, 0x60 초기화, 서보용 PWM 주파수(50Hz) 설정
#   - center(): Steer Center 100 으로 정렬 — 부팅 시 필수 (F-001)
#   - set(value): 60~140 범위로 클램프 후 ch0 에 PWM 출력
#   - 전원 주의: PCA9685 V+ 는 별도 5V 급전 (서보 스톨 전류 400~800mA)
