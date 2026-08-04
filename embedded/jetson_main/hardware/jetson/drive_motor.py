"""구동 모터 실물 구현 — 모터 HAT(PCA9685, 기본 0x40)의 TB6612FNG B채널.

배선·주소·채널은 Phase 0에서 검증됨(docs/HARDWARE.md). 모든 수치는 config.yaml에서
주입된다. adafruit 라이브러리는 생성 시점에만 import 하므로, 이 모듈을 import
하는 것 자체는 PC(하드웨어 없음)에서도 안전하다.
"""

import logging
import time

from hardware.interfaces import DriveMotor
from hardware.jetson.pca9685_wake import ensure_awake, is_asleep

logger = logging.getLogger(__name__)

# PCA9685 프로토콜 상수 (튜닝값 아님): 12비트 PWM의 16비트 레지스터 스케일
_DUTY_FULL = 0xFFFF
_PCT_FULL = 100.0
_WAKE_CHECK_PERIOD_SEC = 1.0    # 주행 중 SLEEP 재래치 감시 주기 — 0731에 주행
                                # 도중 재래치가 실측됐다 (레지스터 1회 읽기라 저렴)


class JetsonDriveMotor(DriveMotor):
    """TB6612FNG B채널 구동: PWMB=속도, BIN1/BIN2=방향.

    전진 IN1=H/IN2=L, 후진 IN1=L/IN2=H, 무동력 정지 모두 L,
    숏브레이크 IN1=IN2=H + PWM 최대 (기존 코드베이스에서 검증된 시퀀스).

    생성자의 stop() 호출은 1회성 안전 초기화(장치를 확정된 정지 상태로) —
    주행 중 명령은 main 루프의 apply_command 한 곳에서만 온다(single writer).
    """

    def __init__(self, address: int, pwm_frequency_hz: int,
                 pwm_channel: int, in1_channel: int, in2_channel: int):
        import board
        import busio
        from adafruit_pca9685 import PCA9685

        self._i2c = busio.I2C(board.SCL, board.SDA)
        self._address = address
        self._pca = PCA9685(self._i2c, address=address)
        self._pca.frequency = pwm_frequency_hz
        # 클론 칩은 frequency 세터의 wake 1회가 안 먹을 수 있다 — 깰 때까지 재기록
        # (SLEEP 래치 시 조향만 되고 전후진이 죽는다: pca9685_wake.py 참고)
        ensure_awake(self._i2c, address, "모터 HAT")
        self._last_wake_check = time.monotonic()
        self._pwm_channel = pwm_channel
        self._in1_channel = in1_channel
        self._in2_channel = in2_channel
        self.stop()

    def _check_awake(self) -> None:
        """주행 중 SLEEP 재래치 감시 — 래치가 확인되면 즉시 재기동을 시도한다."""
        now = time.monotonic()
        if now - self._last_wake_check < _WAKE_CHECK_PERIOD_SEC:
            return
        self._last_wake_check = now
        try:
            if is_asleep(self._i2c, self._address):
                logger.warning("[모터] PCA9685 SLEEP 재래치 감지 — 재기동 시도 "
                               "(모터 배터리 전압 강하 의심)")
                ensure_awake(self._i2c, self._address, "모터 HAT", timeout_sec=0.2)
        except OSError as error:
            logger.warning("[모터] SLEEP 감시 읽기 실패: %s", error)

    def _write(self, duty_pct: float, in1_high: bool, in2_high: bool) -> None:
        self._check_awake()
        duty = int(max(0.0, min(_PCT_FULL, duty_pct)) / _PCT_FULL * _DUTY_FULL)
        self._pca.channels[self._pwm_channel].duty_cycle = duty
        self._pca.channels[self._in1_channel].duty_cycle = _DUTY_FULL if in1_high else 0
        self._pca.channels[self._in2_channel].duty_cycle = _DUTY_FULL if in2_high else 0

    def set_throttle_pct(self, throttle_pct: float) -> None:
        value = float(throttle_pct)
        if value > 0:
            self._write(value, True, False)     # 전진
        elif value < 0:
            self._write(-value, False, True)    # 후진
        else:
            self.stop()

    def stop(self) -> None:
        self._write(0.0, False, False)          # 무동력 (코스트)

    def brake(self) -> None:
        self._write(_PCT_FULL, True, True)      # 숏브레이크 (권선 단락, 밀림 저항)
