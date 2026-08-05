"""터치 센서 — 주행·탐지 시작 트리거 (명세: '터치 시 주행 탐지 시작').

40핀 헤더 GPIO 1개를 폴링해 **누름의 상승 엣지**를 래치한다. 스레드를 두지 않는다:
시작 버튼은 20Hz 폴링으로 충분하고, main 루프가 직접 읽으면 '하드웨어 명령은
apply_command 한 곳'이라는 single writer 계약과 나란히 '입력 읽기는 루프가 직접'
이라는 기존 관례(imu.read·camera.read)에 그대로 얹힌다.

핀 번호를 모르면(config `pin: null`) **비활성**이다 — 그 경우 시작 트리거는 종전대로
조작자 입력(Xbox A)뿐이다. 보드에서 배선한 핀을 config에 적으면 활성화된다.
Jetson.GPIO는 이미 requirements에 있고 import는 생성자 안으로 미룬다(개발 PC 보호).
"""

import logging
import sys
from typing import Optional

logger = logging.getLogger("robot.touch")


def _select_board_mode(gpio) -> None:
    """핀 번호 체계를 BOARD(물리 핀)로 맞춘다.

    Blinka(`board`/`busio` — 모터·서보의 PCA9685)가 먼저 열리면 Jetson.GPIO 모드가 이미
    SYSFS로 잡혀 있어 `setmode(BOARD)`가 ValueError("A different mode has already been
    set!")를 낸다. main은 `build_devices`(모터·서보)를 터치 센서보다 먼저 조립하므로
    **--live 경로는 항상 그 순서다** — 그대로 두면 dry-run에서는 터치가 살아 있고
    실주행에서만 시작 트리거가 조용히 죽는다(초기화 실패 → None). 모드를 되돌리는 공개
    API가 없어 전역 하나를 지우고 다시 잡는다 —
    tools/data_recorder/motor_driver.ensure_gpio_mode와 같은 처리다.
    """
    if gpio.getmode() == gpio.BOARD:
        return
    if getattr(gpio, "__name__", None) == "Jetson.GPIO":   # 주입된 대역은 건드리지 않는다
        module = sys.modules.get("Jetson.GPIO.gpio")
        if module is not None:
            module._gpio_mode = None
    gpio.setmode(gpio.BOARD)


class TouchSensor:
    """GPIO 터치 센서. `pressed()`가 상승 엣지 1회를 소비해 True를 돌려준다.

    active_high: 눌렀을 때 핀이 HIGH인가(풀다운 배선) LOW인가(풀업 배선).
        모듈형 터치 센서(TTP223 등)는 보통 눌렀을 때 HIGH다 — 그것이 기본값이다.
    """

    def __init__(self, pin: int, active_high: bool = True,
                 gpio=None):
        if gpio is None:
            import Jetson.GPIO as gpio     # 보드 전용 — 개발 PC에서는 주입으로만 쓴다

        self._gpio = gpio
        self._pin = int(pin)
        self._active_high = bool(active_high)
        self._was_pressed = False
        # BOARD 모드 = 물리 핀 번호. config에 적는 값과 헤더 인쇄가 일치해야
        # 배선 실수를 눈으로 잡을 수 있다.
        _select_board_mode(gpio)
        # 보드의 Jetson.GPIO는 pull_up_down을 무시한다(경고를 낸다) — 배선 의도의 기록으로
        # 남기고, 실제 풀 저항은 센서 모듈(TTP223 등)이 능동 구동하는 것에 의존한다.
        pull = gpio.PUD_DOWN if self._active_high else gpio.PUD_UP
        gpio.setup(self._pin, gpio.IN, pull_up_down=pull)

    def _is_down(self) -> bool:
        level = bool(self._gpio.input(self._pin))
        return level if self._active_high else not level

    def pressed(self) -> bool:
        """상승 엣지 1회 소비 — 누르고 있는 동안 계속 True가 되지는 않는다.

        엣지로 읽는 이유: 이 신호의 소비처가 `machine.request_start()`이고, 누르고
        있는 내내 시작 요청이 반복되면 경계 소실 폴백(IDLE — 수동 재시작만이 출구)의
        안전 설계를 침범한다. 손을 얹어 둔 채로는 IDLE에서 자동 재출발한다는 뜻이 된다.
        """
        try:
            down = self._is_down()
        except Exception:
            logger.warning("[터치] 핀 %d 읽기 실패 — 이번 틱 무시", self._pin,
                           exc_info=True)
            return False
        edge = down and not self._was_pressed
        self._was_pressed = down
        return edge

    def close(self) -> None:
        """핀 정리. 다른 GPIO 사용자를 건드리지 않도록 이 핀만 해제한다."""
        try:
            self._gpio.cleanup(self._pin)
        except Exception:
            logger.debug("[터치] 핀 정리 실패 (무해)", exc_info=True)


def build_touch_sensor(config: dict) -> Optional[TouchSensor]:
    """config hardware.touch → 센서 또는 None (핀 미지정·초기화 실패).

    실패해도 주행을 막지 않는다: 시작 트리거는 조작자 입력(Xbox A)과 이 센서 둘 다이고,
    둘 중 하나만 있어도 미션은 시작된다. 센서를 요구해서 주행 자체를 막지는 않는다.
    """
    touch = config["hardware"].get("touch") or {}
    pin = touch.get("pin")
    if pin is None:
        logger.info("터치 센서 핀 미지정 — 시작 트리거는 조작자 입력(Xbox A)뿐입니다")
        return None
    try:
        sensor = TouchSensor(pin=int(pin),
                             active_high=bool(touch.get("active_high", True)))
    except Exception:
        logger.warning("터치 센서 초기화 실패(핀 %s) — 조작자 입력으로 시작하십시오", pin,
                       exc_info=True)
        return None
    logger.info("터치 센서 연결: 핀 %s (누르면 주행·탐지 시작)", pin)
    return sensor
