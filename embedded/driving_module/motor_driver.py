import time
import logging
import config

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

def ensure_gpio_mode(target_mode):
    """Helper to safely set Jetson.GPIO mode even if Adafruit Blinka set SYSFS mode."""
    try:
        import Jetson.GPIO as GPIO
        import Jetson.GPIO.gpio as gpio_module
        cur_mode = GPIO.getmode()
        if cur_mode != target_mode:
            gpio_module._gpio_mode = None
            GPIO.setmode(target_mode)
    except Exception as e:
        logging.warning(f"[GPIO] ensure_gpio_mode warning: {e}")


class BaseMotorDriver:
    def __init__(self):
        self.current_direction = "STOP"
        self.current_speed = getattr(config, 'DEFAULT_SPEED', 35)
        self.current_angle = getattr(config, 'STEERING_CENTER', 100)

    def forward(self, speed=None):
        raise NotImplementedError

    def backward(self, speed=None):
        raise NotImplementedError

    def left(self, angle=None, speed=None):
        return self.steer_left(getattr(config, 'STEERING_STEP', 15))

    def right(self, angle=None, speed=None):
        return self.steer_right(getattr(config, 'STEERING_STEP', 15))

    def steer_left(self, step=15):
        min_angle = getattr(config, 'STEERING_MAX_LEFT', 40)
        new_angle = max(min_angle, self.current_angle - step)
        return self.set_angle(new_angle)

    def steer_right(self, step=15):
        max_angle = getattr(config, 'STEERING_MAX_RIGHT', 160)
        new_angle = min(max_angle, self.current_angle + step)
        return self.set_angle(new_angle)

    def set_angle(self, angle):
        min_angle = getattr(config, 'STEERING_MAX_LEFT', 40)
        max_angle = getattr(config, 'STEERING_MAX_RIGHT', 160)
        self.current_angle = max(min_angle, min(max_angle, int(angle)))

    def center(self):
        self.set_angle(getattr(config, 'STEERING_CENTER', 100))

    def stop(self):
        raise NotImplementedError

    def set_speed(self, speed):
        max_sp = getattr(config, 'MAX_SPEED', 100)
        min_sp = getattr(config, 'MIN_SPEED', 0)
        self.current_speed = max(min_sp, min(max_sp, int(speed)))

    def cleanup(self):
        pass


class MockMotorDriver(BaseMotorDriver):
    """Simulated Motor Driver for software testing without hardware."""
    def __init__(self):
        super().__init__()
        logging.info("[MOCK] Initialized Mock Motor Driver")

    def forward(self, speed=None):
        if speed is not None: self.set_speed(speed)
        self.current_direction = "FORWARD"

    def backward(self, speed=None):
        if speed is not None: self.set_speed(speed)
        self.current_direction = "BACKWARD"

    def set_angle(self, angle):
        super().set_angle(angle)

    def steer_left(self, step=15):
        super().steer_left(step)
        self.current_direction = "LEFT"

    def steer_right(self, step=15):
        super().steer_right(step)
        self.current_direction = "RIGHT"

    def center(self):
        super().center()
        self.current_direction = "CENTER"

    def stop(self):
        self.current_direction = "STOP"


class GPIOMotorDriver(BaseMotorDriver):
    """Jetson GPIO Direct Motor Driver Implementation."""
    def __init__(self):
        super().__init__()
        import Jetson.GPIO as GPIO
        self.GPIO = GPIO
        
        mode = GPIO.BOARD if getattr(config, 'GPIO_PIN_MODE', 'BOARD') == 'BOARD' else GPIO.BCM
        ensure_gpio_mode(mode)
        self.GPIO.setwarnings(False)

        self.in1 = getattr(config, 'MOTOR_IN1', 29)
        self.in2 = getattr(config, 'MOTOR_IN2', 31)
        self.in3 = getattr(config, 'MOTOR_IN3', 35)
        self.in4 = getattr(config, 'MOTOR_IN4', 37)
        self.ena = getattr(config, 'MOTOR_ENA', 33)
        self.enb = getattr(config, 'MOTOR_ENB', 32)

        pins = [self.in1, self.in2, self.in3, self.in4, self.ena, self.enb]
        for p in pins:
            try:
                self.GPIO.setup(p, self.GPIO.OUT, initial=self.GPIO.LOW)
            except Exception as e:
                logging.warning(f"[GPIO] Pin setup warning for {p}: {e}")

        try:
            freq = getattr(config, 'PWM_FREQ', 1000)
            self.pwm_a = self.GPIO.PWM(self.ena, freq)
            self.pwm_b = self.GPIO.PWM(self.enb, freq)
            self.pwm_a.start(self.current_speed)
            self.pwm_b.start(self.current_speed)
            self.use_pwm = True
        except Exception:
            self.use_pwm = False
            try:
                self.GPIO.output(self.ena, self.GPIO.HIGH)
                self.GPIO.output(self.enb, self.GPIO.HIGH)
            except Exception:
                pass

        logging.info(f"[GPIO] Initialized GPIO Motor Driver")

    def _update_enable_pins(self, speed_a, speed_b):
        if getattr(self, 'use_pwm', False):
            try:
                if hasattr(self, 'pwm_a') and self.pwm_a:
                    self.pwm_a.ChangeDutyCycle(speed_a)
                if hasattr(self, 'pwm_b') and self.pwm_b:
                    self.pwm_b.ChangeDutyCycle(speed_b)
            except Exception:
                pass
        else:
            try:
                self.GPIO.output(self.ena, self.GPIO.HIGH if speed_a > 0 else self.GPIO.LOW)
                self.GPIO.output(self.enb, self.GPIO.HIGH if speed_b > 0 else self.GPIO.LOW)
            except Exception:
                pass

    def forward(self, speed=None):
        if speed is not None: self.set_speed(speed)
        try:
            self.GPIO.output(self.in1, self.GPIO.HIGH)
            self.GPIO.output(self.in2, self.GPIO.LOW)
            self.GPIO.output(self.in3, self.GPIO.HIGH)
            self.GPIO.output(self.in4, self.GPIO.LOW)
        except Exception as e:
            logging.error(f"[GPIO] forward error: {e}")

        self._update_enable_pins(self.current_speed, self.current_speed)
        self.current_direction = "FORWARD"

    def backward(self, speed=None):
        if speed is not None: self.set_speed(speed)
        try:
            self.GPIO.output(self.in1, self.GPIO.LOW)
            self.GPIO.output(self.in2, self.GPIO.HIGH)
            self.GPIO.output(self.in3, self.GPIO.LOW)
            self.GPIO.output(self.in4, self.GPIO.HIGH)
        except Exception as e:
            logging.error(f"[GPIO] backward error: {e}")

        self._update_enable_pins(self.current_speed, self.current_speed)
        self.current_direction = "BACKWARD"

    def steer_left(self, step=15):
        super().steer_left(step)
        self.current_direction = "LEFT"

    def steer_right(self, step=15):
        super().steer_right(step)
        self.current_direction = "RIGHT"

    def stop(self):
        try:
            self.GPIO.output(self.in1, self.GPIO.LOW)
            self.GPIO.output(self.in2, self.GPIO.LOW)
            self.GPIO.output(self.in3, self.GPIO.LOW)
            self.GPIO.output(self.in4, self.GPIO.LOW)
            self._update_enable_pins(0, 0)
        except Exception:
            pass
        self.current_direction = "STOP"

    def cleanup(self):
        self.stop()
        try:
            self.GPIO.cleanup()
        except Exception:
            pass


class PCA9685MotorDriver(BaseMotorDriver):
    """Dual I2C Driver: Servo Board at 0x60 (Ch0) & DC Motor HAT at 0x40 (Ch5 PWM, Ch4 IN1, Ch3 IN2)."""
    def __init__(self):
        super().__init__()
        import busio
        import board
        from adafruit_pca9685 import PCA9685
        from adafruit_servokit import ServoKit

        self.i2c_bus = busio.I2C(board.SCL, board.SDA)
        self.pca = None
        self.kit = None

        # 1. Connect Servo Steering Board at 0x60
        servo_addrs = [getattr(config, 'SERVO_ADDRESS', 0x60), 0x60, 0x40]
        for addr in servo_addrs:
            try:
                self.kit = ServoKit(channels=16, i2c=self.i2c_bus, address=addr)
                logging.info(f"[PCA9685] Steering Servo Board connected at {hex(addr)}")
                break
            except Exception as e:
                logging.warning(f"[PCA9685] ServoKit try {hex(addr)} error: {e}")

        # 2. Connect DC Motor HAT at 0x40
        pca_addrs = [getattr(config, 'PCA9685_ADDRESS', 0x40), 0x40, 0x60]
        for addr in pca_addrs:
            try:
                pca_obj = PCA9685(self.i2c_bus, address=addr)
                pca_obj.frequency = 60
                self.pca = pca_obj
                logging.info(f"[PCA9685] DC Motor Controller connected at {hex(addr)}")
                break
            except Exception as e:
                logging.warning(f"[PCA9685] Motor PCA try {hex(addr)} error: {e}")

        self.steering_channel = getattr(config, 'PCA9685_STEERING_CHANNEL', 0)
        self.pwm_ch = getattr(config, 'PCA9685_MOTOR_PWM_CH', 5)
        self.in1_ch = getattr(config, 'PCA9685_MOTOR_IN1_CH', 4)
        self.in2_ch = getattr(config, 'PCA9685_MOTOR_IN2_CH', 3)

        self.set_angle(getattr(config, 'STEERING_CENTER', 100))

    def set_angle(self, angle):
        super().set_angle(angle)
        if self.kit:
            try:
                self.kit.servo[self.steering_channel].angle = self.current_angle
            except Exception as e:
                logging.warning(f"[PCA9685] Steering set_angle error: {e}")

    def _set_motor(self, pwm_val, in1_high, in2_high):
        if self.pca:
            try:
                duty = int((max(0, min(100, pwm_val)) / 100.0) * 65535)
                self.pca.channels[self.pwm_ch].duty_cycle = duty
                self.pca.channels[self.in1_ch].duty_cycle = 65535 if in1_high else 0
                self.pca.channels[self.in2_ch].duty_cycle = 65535 if in2_high else 0
            except Exception as e:
                logging.warning(f"[PCA9685] Motor output error: {e}")

    def forward(self, speed=None):
        if speed is not None: self.set_speed(speed)
        # Forward: IN1=HIGH(65535), IN2=LOW(0)
        self._set_motor(self.current_speed, True, False)
        self.current_direction = "FORWARD"

    def backward(self, speed=None):
        if speed is not None: self.set_speed(speed)
        # Backward: IN1=LOW(0), IN2=HIGH(65535)
        self._set_motor(self.current_speed, False, True)
        self.current_direction = "BACKWARD"

    def stop(self):
        self._set_motor(0, False, False)
        self.current_direction = "STOP"


def get_motor_driver(mode=None):
    if mode is None:
        mode = getattr(config, 'HARDWARE_MODE', 'PCA9685')
    mode = mode.upper()

    if mode == 'PCA9685':
        try:
            return PCA9685MotorDriver()
        except Exception as e:
            logging.warning(f"PCA9685 Initialization failed ({e}). Falling back to MOCK mode.")
            return MockMotorDriver()
    elif mode == 'GPIO':
        try:
            return GPIOMotorDriver()
        except Exception as e:
            logging.warning(f"GPIO Initialization failed ({e}). Falling back to MOCK mode.")
            return MockMotorDriver()
    else:
        return MockMotorDriver()
