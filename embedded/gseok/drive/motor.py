"""구동 모터 제어 — PCA9685(I2C) + Jetson.GPIO 폴백.  [F-002]

드라이버는 driving_test/motor_driver.py 에서 가져왔다(원본은 읽기 전용).
⚠️ 이 프로세스가 I2C 버스를 소유한다. driving_test 앱과 동시에 띄우지 말 것.
원본 get_motor_driver() 는 초기화가 실패하면 조용히 Mock 으로 떨어진다. 그대로 두면
하드웨어가 없는데 health.motor=OK 를 보고하게 되므로, 여기서는 실제로 무엇이 붙었는지
드러내고 Mock 이면 FAULT 로 판정한다 (계약 §5.3).
"""

from __future__ import annotations

import logging
import threading

log = logging.getLogger(__name__)

DEFAULTS = {
    "hardware_mode": "PCA9685",
    "pca9685_address": 0x40,
    "servo_address": 0x60,
    "steering_channel": 0,
    "motor_pwm_channel": 5,
    "motor_in1_channel": 4,
    "motor_in2_channel": 3,
    #: 조향 캘리브레이션 — driving_test 실측값. 임의로 바꾸지 말 것.
    "steering_center": 100,
    "steering_max_left": 40,
    "steering_max_right": 160,
    "default_speed": 75,
}


class Pca9685Driver:
    """PCA9685 채널로 DC 모터와 조향 서보를 움직인다. 초기화 실패는 예외로 알린다."""

    def __init__(self, settings: dict) -> None:
        import board
        import busio
        from adafruit_pca9685 import PCA9685
        from adafruit_servokit import ServoKit

        self._settings = settings
        bus = busio.I2C(board.SCL, board.SDA)
        self._pca = None
        for address in (settings["pca9685_address"], 0x40, 0x60):
            try:
                candidate = PCA9685(bus, address=address)
                candidate.frequency = 60
                self._pca = candidate
                log.info("motor: PCA9685 at %s", hex(address))
                break
            except Exception:
                continue
        if self._pca is None:
            raise RuntimeError("no PCA9685 on the I2C bus")
        self._kit = None
        for address in (settings["servo_address"], 0x60, 0x40):
            try:
                self._kit = ServoKit(channels=16, i2c=bus, address=address)
                break
            except Exception:
                continue
        self._throttle(0.0)

    def _throttle(self, throttle: float) -> None:
        settings = self._settings
        pulse = max(0, min(0xFFFF, int(0xFFFF * abs(throttle))))
        channels = self._pca.channels
        channels[settings["motor_pwm_channel"]].duty_cycle = pulse if throttle else 0
        forward = 0xFFFF if throttle > 0 else 0
        backward = 0xFFFF if throttle < 0 else 0
        channels[settings["motor_in1_channel"]].duty_cycle = forward
        channels[settings["motor_in2_channel"]].duty_cycle = backward

    def drive(self, speed_percent: int) -> None:
        self._throttle(max(-100, min(100, speed_percent)) / 100.0)

    def halt(self) -> None:
        self._throttle(0.0)

    def steer(self, angle: int) -> None:
        if self._kit is not None:
            self._kit.servo[self._settings["steering_channel"]].angle = angle


class MotorController:
    """주행 명령 상태를 들고 있는다. 실제 회전은 재지 않는다 — 엔코더가 없다 (§6.4)."""

    def __init__(self, config: dict = None, *, driver=None) -> None:
        settings = dict(DEFAULTS)
        settings.update({k: v for k, v in (config or {}).items() if k in DEFAULTS})
        self._settings = settings
        self._driver = driver
        self._lock = threading.Lock()
        self._direction = "STOP"
        self._started = driver is not None

    # -- 수명 ------------------------------------------------------------

    def start(self) -> "MotorController":
        """드라이버를 연다. 실패하면 드라이버 없이 뜨고 health 가 FAULT 를 말한다."""
        self._started = True
        if self._driver is not None:
            return self
        mode = str(self._settings["hardware_mode"]).upper()
        if mode != "PCA9685":
            log.warning("motor: hardware_mode=%s is not wired here", mode)
            return self
        try:
            self._driver = Pca9685Driver(self._settings)
        except Exception as exc:
            # 원본처럼 Mock 으로 떨어지지 않는다 — 없으면 없다고 보고한다.
            log.warning("motor: PCA9685 init failed (%s)", exc)
            self._driver = None
        return self

    def stop(self) -> None:
        """어떤 상태에서도 즉시 정지. 실패해도 예외를 올리지 않는다.

        계약 §16-10 — 실제 안전 정지는 로봇 내부에서 먼저 수행하고 MQTT 는 그 결과를
        보고한다. 그 '먼저' 를 만족시키는 유일한 지점이다.
        """
        with self._lock:
            self._direction = "STOP"
        if self._driver is None:
            return
        try:
            self._driver.halt()
        except Exception:
            log.exception("motor: stop failed")

    # -- 주행 ------------------------------------------------------------

    def forward(self, speed_percent: int = None) -> None:
        speed = self._settings["default_speed"] if speed_percent is None else speed_percent
        with self._lock:
            self._direction = "FORWARD"
        if self._driver is not None:
            self._driver.drive(speed)

    def steer(self, angle: int) -> None:
        settings = self._settings
        angle = max(settings["steering_max_left"], min(settings["steering_max_right"], int(angle)))
        if self._driver is not None:
            self._driver.steer(angle)

    def center(self) -> None:
        self.steer(self._settings["steering_center"])

    # -- 보고 ------------------------------------------------------------

    def is_driving(self) -> bool:
        with self._lock:
            return self._direction != "STOP"

    def health(self) -> str:
        if not self._started:
            return "UNKNOWN"
        # 드라이버가 없으면 I2C 가 응답하지 않은 것이다 (계약 §5.3).
        return "OK" if self._driver is not None else "FAULT"


# --------------------------------------------------------------------------
# 모듈 싱글턴 — main.py 가 configure() 로 설정하고 start() 로 장치를 연다.
# --------------------------------------------------------------------------

_config = {}
_controller = None


def configure(config: dict = None) -> None:
    """설정만 받아 둔다. I2C 는 건드리지 않는다."""
    global _config
    _config = dict(config or {})


def start() -> None:
    global _controller
    if _controller is None:
        _controller = MotorController(_config)
    _controller.start()


def controller():
    """조립된 컨트롤러. 주행 코드가 붙으면 여기서 가져다 쓴다."""
    return _controller


def stop() -> None:
    """즉시 정지. 기동 전이면 아무 일도 하지 않는다 — 예외를 올리지 않는다."""
    if _controller is not None:
        _controller.stop()


def is_driving() -> bool:
    """주행 PWM 명령을 내보내는 중인지 (계약 §6.4).

    telemetry.motion.moving 의 재료다. 실제 바퀴 회전 측정이 아니라 제어 명령
    기준이다 — 엔코더가 없어서 회전을 확인할 방법 자체가 없다.
    """
    if _controller is None:
        return False
    return _controller.is_driving()


def health() -> str:
    """state.health.motor — I2C Driver 응답·Fault Feedback 으로 판정 (계약 §5.3).

    기동 전에는 UNKNOWN 이다. 계약상 UNKNOWN 은 '검사 전' 이라는 뜻이라
    지금 상태를 정확히 나타낸다 — 가짜 OK 를 돌려주면 안 된다.
    """
    if _controller is None:
        return "UNKNOWN"
    return _controller.health()
