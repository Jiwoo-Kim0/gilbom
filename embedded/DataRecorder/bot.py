import os
import time
import struct
import logging
import threading

import recorder_config as rc

# Resolved from DrivingModule (main.py inserts it into sys.path)
import config as drive_config
from motor_driver import get_motor_driver

_JS_EVENT = struct.Struct("IhBB")   # time_ms, value, type, number
_EV_BUTTON = 0x01
_EV_AXIS = 0x02
_EV_INIT = 0x80


class BotController(threading.Thread):
    """
    Reads the Xbox controller and drives the motors.

    Mapping: left stick X = steering, RT = forward, LT = reverse,
    B = emergency stop (release trigger to re-arm), A = record toggle.

    Fail-safe: the drive loop only applies throttle while joystick events can
    actually be read - if the device drops, motors stop and the reader retries.
    The steering servo is clamped by motor_driver.set_angle to the calibrated
    lock range; past the lock the linkage jams, so never bypass that clamp.
    """

    def __init__(self, on_record_toggle=None):
        super().__init__(daemon=True, name="bot")
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self.on_record_toggle = on_record_toggle

        # DrivingModule clamps set_speed to MAX_SPEED=85 (autonomous cruise
        # margin). Manual outdoor driving needs the full range, so lift the
        # clamp for THIS process only - the file is not touched.
        drive_config.MAX_SPEED = 100

        self.motor = get_motor_driver()
        self.connected = False
        self.estop = False
        self.throttle_cap = rc.THROTTLE_MAX

        # latched controller state, written by reader / read by drive loop
        self._steer_raw = 0
        self._stick_raw = 0
        self._lt_raw = -32767
        self._rt_raw = -32767

        # what the drive loop last applied (for status / control log)
        self.applied_angle = getattr(drive_config, "STEERING_CENTER", 100)
        self.applied_speed = 0
        self.applied_direction = "STOP"

        self._center = getattr(drive_config, "STEERING_CENTER", 100)
        self._span = min(self._center - getattr(drive_config, "STEERING_MAX_LEFT", 40),
                         getattr(drive_config, "STEERING_MAX_RIGHT", 160) - self._center)

        self._reader = threading.Thread(target=self._read_loop, daemon=True, name="js-reader")

    # ---- joystick reader -------------------------------------------------

    def _read_loop(self):
        while not self._stop.is_set():
            try:
                fd = os.open(rc.JS_DEVICE, os.O_RDONLY)
            except OSError:
                if self.connected:
                    logging.warning("[BOT] controller disconnected")
                self.connected = False
                self._neutral()
                self._stop.wait(2.0)
                continue

            logging.info(f"[BOT] controller connected: {rc.JS_DEVICE}")
            self.connected = True
            try:
                while not self._stop.is_set():
                    data = os.read(fd, _JS_EVENT.size)
                    if len(data) != _JS_EVENT.size:
                        break
                    _, value, ev_type, number = _JS_EVENT.unpack(data)
                    self._handle_event(ev_type & ~_EV_INIT, number, value,
                                       init=bool(ev_type & _EV_INIT))
            except OSError:
                pass
            finally:
                os.close(fd)
                self.connected = False
                self._neutral()

    def _handle_event(self, ev_type, number, value, init=False):
        if ev_type == _EV_AXIS:
            if number == rc.JS_AXIS_STEER:
                self._steer_raw = value
            elif number == rc.JS_AXIS_THROTTLE:
                self._stick_raw = value
            elif number == rc.JS_AXIS_LT:
                self._lt_raw = value
            elif number == rc.JS_AXIS_RT:
                self._rt_raw = value
        elif ev_type == _EV_BUTTON and not init and value == 1:
            if number == rc.JS_BTN_ESTOP:
                self.estop = True
                logging.info("[BOT] EMERGENCY STOP (B)")
            elif number == rc.JS_BTN_RECORD and self.on_record_toggle:
                self.on_record_toggle()
            elif number in (rc.JS_BTN_CAP_UP, rc.JS_BTN_CAP_DOWN):
                step = rc.THROTTLE_CAP_STEP if number == rc.JS_BTN_CAP_UP else -rc.THROTTLE_CAP_STEP
                self.throttle_cap = max(rc.THROTTLE_CAP_MIN,
                                        min(rc.THROTTLE_CAP_MAX, self.throttle_cap + step))
                logging.info(f"[BOT] throttle cap -> {self.throttle_cap}%")

    def _neutral(self):
        self._steer_raw = 0
        self._stick_raw = 0
        self._lt_raw = -32767
        self._rt_raw = -32767
        self.motor.stop()

    # ---- drive loop ------------------------------------------------------

    @staticmethod
    def _trigger01(raw):
        """-32767..32767 -> 0..1 with deadzone."""
        v = (raw + 32767) / 65534.0
        return v if v * 32767 > rc.JS_DEADZONE / 2 else 0.0

    def run(self):
        self._reader.start()
        interval = 1.0 / rc.DRIVE_LOOP_HZ
        while not self._stop.is_set():
            self._tick()
            time.sleep(interval)
        self.motor.stop()
        self.motor.cleanup()

    def _tick(self):
        # steering
        x = self._steer_raw
        if abs(x) < rc.JS_DEADZONE:
            x = 0
        angle = self._center + (x / 32767.0) * self._span
        if int(angle) != self.applied_angle:
            self.motor.set_angle(int(angle))
            self.applied_angle = self.motor.current_angle

        # throttle: triggers (RT/LT) or left stick Y, whichever is larger.
        # Joystick Y is negative when pushed up, so up = forward.
        sy = self._stick_raw
        if abs(sy) < rc.JS_DEADZONE:
            sy = 0
        fwd = max(self._trigger01(self._rt_raw), max(0.0, -sy / 32767.0))
        rev = max(self._trigger01(self._lt_raw), max(0.0, sy / 32767.0))

        if not self.connected:
            fwd = rev = 0.0

        if self.estop:
            if fwd == 0.0 and rev == 0.0:
                self.estop = False      # all throttle inputs released -> re-arm
            fwd = rev = 0.0

        if fwd > 0.0 and fwd >= rev:
            speed = int(rc.THROTTLE_MIN + fwd * (self.throttle_cap - rc.THROTTLE_MIN))
            self.motor.forward(speed)
            self.applied_speed = speed
            direction = "FORWARD"
        elif rev > 0.0:
            speed = int(rc.THROTTLE_MIN + rev * (rc.REVERSE_MAX - rc.THROTTLE_MIN))
            self.motor.backward(speed)
            self.applied_speed = speed
            direction = "BACKWARD"
        else:
            if self.applied_direction != "STOP":
                self.motor.stop()
            self.applied_speed = 0
            direction = "STOP"

        if direction != self.applied_direction:
            logging.info(f"[BOT] {self.applied_direction} -> {direction} "
                         f"(speed={self.applied_speed}, angle={self.applied_angle})")
        self.applied_direction = direction

    def stop(self):
        self._stop.set()

    def status(self):
        return {
            "connected": self.connected,
            "estop": self.estop,
            "angle": self.applied_angle,
            "angle_center": self._center,
            "angle_span": self._span,
            "speed": self.applied_speed,
            "direction": self.applied_direction,
            "throttle_cap": self.throttle_cap,
        }
