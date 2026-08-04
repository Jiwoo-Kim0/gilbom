"""조향 서보 실물 구현 — PCA9685 서보 보드(기본 0x60) ch0의 MG996R.

소프트 리밋 클램프는 부모(SteeringServo.set_offset_deg 템플릿 메서드)가 강제하므로
여기에는 '클램프 완료된 절대각을 서보에 쓰는' 일만 남는다. 주소·채널·중심·리밋은
config.yaml에서 주입. adafruit 라이브러리는 생성 시점에만 import 한다.

주의(docs/HARDWARE.md): 절대 130°(중심+30°)는 링키지 걸림 경계 — 소프트 리밋이 그
경계 자체이므로, 지속 조향은 +25° 이하 권장. 관례: 좌 = 중심보다 작은 각.

생성자의 center() 호출은 1회성 안전 초기화(조향 중립 확정) — 주행 중 명령은
main 루프의 apply_command 한 곳에서만 온다(single writer).
"""

from hardware.interfaces import SteeringServo


class JetsonSteeringServo(SteeringServo):
    """ServoKit 기반 조향 — 오프셋(도)을 절대각으로 변환해 지정 채널에 쓴다."""

    def __init__(self, address: int, channel: int, center_deg: float,
                 left_offset_limit_deg: float, right_offset_limit_deg: float):
        super().__init__(center_deg, left_offset_limit_deg, right_offset_limit_deg)
        import board
        import busio
        from adafruit_servokit import ServoKit

        from hardware.jetson.pca9685_wake import ensure_awake

        i2c = busio.I2C(board.SCL, board.SDA)
        kit = ServoKit(channels=16, i2c=i2c, address=address)
        # 클론 칩 SLEEP 래치 대비 — wake 1회 쓰기를 신뢰하지 않는다 (pca9685_wake.py)
        ensure_awake(i2c, address, "서보 보드")
        self._servo = kit.servo[channel]
        self.center()

    def _apply_offset_deg(self, clamped_offset_deg: float) -> None:
        self._servo.angle = self.absolute_angle_deg(clamped_offset_deg)
