"""Xbox 컨트롤러 리더 — 수동 조작 입력 (웹 테스트 도구 전용).

tools/data_recorder/bot.py의 js 이벤트 파싱·매핑을 옮겨왔다. 결정적 차이:
여기서는 모터를 직접 만지지 않는다 — 축·버튼 상태를 래치하고 DriveCommand
계산만 제공하며, 하드웨어 쓰기는 주행 루프(single writer)가 한다.

페일세이프(녹화 도구와 동일 정책):
  - 장치가 끊기면 입력을 중립으로 되돌린다 → 주행 루프는 정지 명령을 받는다.
  - B(비상 정지)는 래치된다: 모든 스로틀 입력을 놓아야 재장전된다.
"""

import logging
import os
import struct
import threading
from typing import Callable, Optional

from driving.states import DriveCommand

logger = logging.getLogger("robot.webtest")

_JS_EVENT = struct.Struct("IhBB")   # time_ms, value, type, number
_EV_BUTTON = 0x01
_EV_AXIS = 0x02
_EV_INIT = 0x80
_AXIS_FULL = 32767.0


class XboxControllerReader(threading.Thread):
    """js 장치 이벤트를 읽어 최신 입력 상태를 래치한다 (읽기 전용 스레드).

    on_manual_toggle: A 버튼이 눌릴 때마다 호출 — 수동/자동 전환은 세션이 결정.
    """

    def __init__(self, joystick_config: dict,
                 on_manual_toggle: Optional[Callable[[], None]] = None):
        super().__init__(daemon=True, name="xbox-reader")
        self._device = str(joystick_config["device"])
        self._axis_steer = int(joystick_config["axis_steer"])
        self._axis_throttle = int(joystick_config["axis_throttle"])
        self._axis_reverse = int(joystick_config["axis_reverse_trigger"])
        self._axis_forward = int(joystick_config["axis_forward_trigger"])
        self._button_toggle = int(joystick_config["button_manual_toggle"])
        self._button_estop = int(joystick_config["button_estop"])
        self._button_cap_down = int(joystick_config["button_cap_down"])
        self._button_cap_up = int(joystick_config["button_cap_up"])
        self._deadzone = int(joystick_config["deadzone"])
        self._throttle_min = float(joystick_config["throttle_min_pct"])
        self._cap_step = float(joystick_config["throttle_cap_step_pct"])
        self._cap_min = float(joystick_config["throttle_cap_min_pct"])
        self._cap_max = float(joystick_config["throttle_cap_max_pct"])
        self._on_manual_toggle = on_manual_toggle
        self._stop_requested = threading.Event()

        self.connected = False
        self.estop_latched = False
        self.throttle_cap = float(joystick_config["throttle_cap_initial_pct"])

        # 래치된 축 원시값 — 리더 스레드가 쓰고 주행 루프가 읽는다
        self._steer_raw = 0
        self._stick_raw = 0
        self._reverse_raw = -32767
        self._forward_raw = -32767

    # ---- 리더 스레드 -------------------------------------------------------

    def run(self):
        while not self._stop_requested.is_set():
            try:
                descriptor = os.open(self._device, os.O_RDONLY)
            except OSError:
                if self.connected:
                    logger.warning("[XBOX] 컨트롤러 연결 끊김")
                self.connected = False
                self._neutral()
                self._stop_requested.wait(2.0)
                continue

            logger.info("[XBOX] 컨트롤러 연결: %s", self._device)
            self.connected = True
            try:
                while not self._stop_requested.is_set():
                    data = os.read(descriptor, _JS_EVENT.size)
                    if len(data) != _JS_EVENT.size:
                        break
                    _, value, event_type, number = _JS_EVENT.unpack(data)
                    self._handle_event(event_type & ~_EV_INIT, number, value,
                                       is_init=bool(event_type & _EV_INIT))
            except OSError:
                pass
            finally:
                os.close(descriptor)
                self.connected = False
                self._neutral()

    def stop(self):
        self._stop_requested.set()

    def _handle_event(self, event_type: int, number: int, value: int,
                      is_init: bool) -> None:
        if event_type == _EV_AXIS:
            if number == self._axis_steer:
                self._steer_raw = value
            elif number == self._axis_throttle:
                self._stick_raw = value
            elif number == self._axis_reverse:
                self._reverse_raw = value
            elif number == self._axis_forward:
                self._forward_raw = value
        elif event_type == _EV_BUTTON and not is_init and value == 1:
            if number == self._button_estop:
                self.estop_latched = True
                logger.info("[XBOX] 수동 비상 정지 (B) — 스로틀을 다 놓으면 재장전")
            elif number == self._button_toggle and self._on_manual_toggle:
                self._on_manual_toggle()
            elif number in (self._button_cap_up, self._button_cap_down):
                step = self._cap_step if number == self._button_cap_up else -self._cap_step
                self.throttle_cap = max(self._cap_min,
                                        min(self._cap_max, self.throttle_cap + step))
                logger.info("[XBOX] 최대 스로틀 → %.0f%%", self.throttle_cap)

    def _neutral(self):
        self._steer_raw = 0
        self._stick_raw = 0
        self._reverse_raw = -32767
        self._forward_raw = -32767

    # ---- 주행 루프가 부르는 명령 계산 (순수 — 장치 접근 없음) -----------------

    def _trigger_level(self, raw: int) -> float:
        """트리거 원시값(-32767..32767) → 0..1, 데드존 적용."""
        level = (raw + _AXIS_FULL) / (2.0 * _AXIS_FULL)
        return level if level * _AXIS_FULL > self._deadzone / 2.0 else 0.0

    def drive_command(self, steer_left_limit_deg: float,
                      steer_right_limit_deg: float) -> DriveCommand:
        """래치된 입력 → DriveCommand. 끊김·비상 정지 시 정지 명령.

        조향: 왼스틱 X를 좌우 비대칭 리밋에 각각 비례시킨다 (좌 45°/우 30°).
        스로틀: RT/왼스틱↑ 전진, LT/왼스틱↓ 후진 — 큰 쪽 우선, min..cap 스케일.
        """
        steer_raw = 0 if abs(self._steer_raw) < self._deadzone else self._steer_raw
        steer_ratio = steer_raw / _AXIS_FULL
        limit = steer_left_limit_deg if steer_ratio < 0 else steer_right_limit_deg
        steering_offset_deg = steer_ratio * limit

        stick = 0 if abs(self._stick_raw) < self._deadzone else self._stick_raw
        forward = max(self._trigger_level(self._forward_raw),
                      max(0.0, -stick / _AXIS_FULL))   # 스틱 위 = 음수 = 전진
        reverse = max(self._trigger_level(self._reverse_raw),
                      max(0.0, stick / _AXIS_FULL))

        if not self.connected:
            forward = reverse = 0.0

        if self.estop_latched:
            if forward == 0.0 and reverse == 0.0:
                self.estop_latched = False   # 전 스로틀 해제 → 재장전
            return DriveCommand(throttle_pct=0.0, steering_offset_deg=0.0, brake=True)

        if forward > 0.0 and forward >= reverse:
            throttle = self._throttle_min + forward * (self.throttle_cap - self._throttle_min)
        elif reverse > 0.0:
            throttle = -(self._throttle_min + reverse * (self.throttle_cap - self._throttle_min))
        else:
            throttle = 0.0
        return DriveCommand(throttle_pct=throttle,
                            steering_offset_deg=steering_offset_deg)

    def status(self) -> dict:
        return {
            "connected": self.connected,
            "estop": self.estop_latched,
            "throttle_cap": self.throttle_cap,
        }
