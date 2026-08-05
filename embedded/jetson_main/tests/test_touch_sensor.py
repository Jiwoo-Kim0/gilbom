"""터치 센서 — 명세의 '터치 시 주행 탐지 시작' 트리거.

Jetson.GPIO는 보드 전용이라 개발 PC에서는 가짜 GPIO 모듈을 주입해 검증한다
(`TouchSensor(gpio=...)`). 고정하는 계약은 둘이다:
  · **상승 엣지 1회**만 소비한다 — 누르고 있는 내내 시작 요청이 반복되면 경계 소실
    폴백(IDLE — 수동 재시작만이 출구)의 안전 설계가 무력해진다.
  · 읽기 실패·초기화 실패가 주행을 막지 않는다 — 시작 트리거는 조작자 입력(Xbox A)과
    이 센서 둘 다이고, 하나만 있어도 미션은 시작된다.
"""

import sys

import pytest

from hardware.jetson.touch import TouchSensor, build_touch_sensor


class FakeGpio:
    """Jetson.GPIO 대역 — 레벨을 테스트가 직접 조작한다."""

    BOARD = "BOARD"
    IN = "IN"
    PUD_UP = "PUD_UP"
    PUD_DOWN = "PUD_DOWN"

    def __init__(self, level: bool = False, fail_read: bool = False):
        self.level = level
        self.fail_read = fail_read
        self.mode = None
        self.setup_calls = []
        self.cleaned = []

    def setmode(self, mode):
        self.mode = mode

    def getmode(self):
        return self.mode

    def setup(self, pin, direction, pull_up_down=None):
        self.setup_calls.append((pin, direction, pull_up_down))

    def input(self, pin):
        if self.fail_read:
            raise OSError("gpio read failed")
        return self.level

    def cleanup(self, pin):
        self.cleaned.append(pin)


class TestEdgeSemantics:
    def sensor(self, gpio, **kwargs) -> TouchSensor:
        return TouchSensor(pin=18, gpio=gpio, **kwargs)

    def test_press_reports_once(self):
        gpio = FakeGpio(level=False)
        touch = self.sensor(gpio)
        assert not touch.pressed()
        gpio.level = True
        assert touch.pressed()          # 상승 엣지
        assert not touch.pressed()      # 계속 누르고 있어도 한 번뿐이다
        assert not touch.pressed()

    def test_release_rearms(self):
        gpio = FakeGpio(level=False)
        touch = self.sensor(gpio)
        gpio.level = True
        assert touch.pressed()
        gpio.level = False
        assert not touch.pressed()
        gpio.level = True
        assert touch.pressed()          # 다시 누르면 다시 한 번

    def test_already_held_at_startup_still_needs_a_release(self):
        # 기동 시 이미 눌린 상태(배선 오류·물체가 얹힘)를 시작 요청으로 읽지 않는다.
        # 첫 폴링이 엣지로 잡히지만, 그 뒤로는 놓았다 다시 눌러야 한다
        gpio = FakeGpio(level=True)
        touch = self.sensor(gpio)
        touch.pressed()
        for _ in range(10):
            assert not touch.pressed()

    def test_active_low_wiring(self):
        # 접점 스위치를 GND로 당기는 배선 — 눌렀을 때 LOW다
        gpio = FakeGpio(level=True)
        touch = self.sensor(gpio, active_high=False)
        assert not touch.pressed()      # HIGH = 안 누름
        gpio.level = False
        assert touch.pressed()

    def test_pull_direction_matches_wiring(self):
        # 풀 저항 방향이 배선과 반대면 핀이 떠서 상시 눌림으로 읽힌다
        high = FakeGpio()
        self.sensor(high)
        assert high.setup_calls[0][2] == FakeGpio.PUD_DOWN
        low = FakeGpio()
        self.sensor(low, active_high=False)
        assert low.setup_calls[0][2] == FakeGpio.PUD_UP

    def test_board_mode_matches_config_pin_numbering(self):
        # config에 적는 값이 헤더 인쇄와 같아야 배선 실수를 눈으로 잡는다
        gpio = FakeGpio()
        self.sensor(gpio)
        assert gpio.mode == FakeGpio.BOARD
        assert gpio.setup_calls[0][0] == 18

    def test_foreign_mode_is_reclaimed(self):
        # Blinka(board/busio)가 먼저 열리면 모드가 SYSFS로 잡혀 setmode(BOARD)가 ValueError다.
        # main은 모터·서보를 터치보다 먼저 조립하므로 --live에서는 늘 이 순서고, 잡지 않으면
        # 실주행에서만 터치 시작 트리거가 조용히 죽는다.
        gpio = FakeGpio()
        gpio.setmode("SYSFS")
        self.sensor(gpio)
        assert gpio.mode == FakeGpio.BOARD


class TestFailureIsHarmless:
    def test_read_failure_is_not_a_press(self):
        gpio = FakeGpio(fail_read=True)
        touch = TouchSensor(pin=18, gpio=gpio)
        assert not touch.pressed()

    def test_close_only_releases_its_own_pin(self):
        gpio = FakeGpio()
        touch = TouchSensor(pin=18, gpio=gpio)
        touch.close()
        assert gpio.cleaned == [18]     # 전체 cleanup()이면 다른 GPIO 사용자를 끊는다


class TestBuildFromConfig:
    def test_configured_pin_matches_wiring(self, config):
        # 0804 배선 확정: VCC pin1(3.3V) / SIG pin16 / GND pin14.
        # 구동 I2C(3·5)·IMU(27·28·17·25)와 겹치면 그쪽이 죽는다
        touch = config["hardware"]["touch"]
        assert touch["pin"] == 16
        assert touch["active_high"] is True
        assert touch["pin"] not in (3, 5, 17, 25, 27, 28)

    def test_pin_null_disables_without_error(self):
        # 핀을 비우면 비활성 — 시작 트리거는 Xbox A만 남는다 (센서 고장 시의 우회로)
        assert build_touch_sensor(
            {"hardware": {"touch": {"pin": None, "active_high": True}}}) is None

    def test_missing_section_disables_without_error(self):
        assert build_touch_sensor({"hardware": {}}) is None

    def test_import_failure_disables_without_error(self, monkeypatch):
        # 개발 PC에는 Jetson.GPIO가 없다 — 그래도 예외가 올라오면 주행이 죽는다.
        # 모듈을 가려서 확인한다: 보드에는 Jetson.GPIO가 있고 preflight가 보드에서도 이
        # 테스트를 돌리므로, 가리지 않으면 실패하는 데다 실패 전에 실제로 핀을 점유한다.
        monkeypatch.setitem(sys.modules, "Jetson.GPIO", None)
        assert build_touch_sensor(
            {"hardware": {"touch": {"pin": 18, "active_high": True}}}) is None
