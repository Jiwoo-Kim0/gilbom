"""PCA9685 클론 SLEEP 래치 대응 — 깨어날 때까지 MODE1 wake를 재기록한다.

모터 HAT의 PCA9685 클론(버스7 0x40)은 MODE1.SLEEP(비트4)이 래치된 채 멈출 수
있다: 모든 I2C 쓰기가 ACK되고 프리스케일도 박히지만 SLEEP이 1로 읽히며 PWM
출력 전체가 죽는다 — 조향(별도 보드 0x60)은 되는데 전후진만 무반응인 증상
(0730 야외 실측, 0731에는 주행 도중 재래치도 관측 — 모터 배터리 전압 강하 의심).

wake 쓰기 1회는 신뢰할 수 없다(같은 값이 먹을 때도 안 먹을 때도 있다) —
SLEEP이 실제로 0으로 읽힐 때까지 재기록해야 한다. RESTART(비트7)는 여기서
절대 쓰지 않는다: SLEEP 해제와 RESTART 설정을 한 번에 쓰는 것은 데이터시트
금지 사항이다. DrivingCore motor_driver.py의 _ensure_awake와 동일 정책.
"""

import logging
import time

logger = logging.getLogger(__name__)

_MODE1_REG = 0x00
_MODE1_SLEEP = 0x10
_MODE1_WAKE_MASK = 0x6F      # SLEEP·RESTART만 지우고 AI/SUBx/ALLCALL은 보존


def _read_mode1(i2c_bus, address: int) -> int:
    buf = bytearray(1)
    while not i2c_bus.try_lock():
        pass
    try:
        i2c_bus.writeto_then_readfrom(address, bytes([_MODE1_REG]), buf)
    finally:
        i2c_bus.unlock()
    return buf[0]


def _write_mode1(i2c_bus, address: int, value: int) -> None:
    while not i2c_bus.try_lock():
        pass
    try:
        i2c_bus.writeto(address, bytes([_MODE1_REG, value]))
    finally:
        i2c_bus.unlock()


def is_asleep(i2c_bus, address: int) -> bool:
    return bool(_read_mode1(i2c_bus, address) & _MODE1_SLEEP)


def ensure_awake(i2c_bus, address: int, name: str, timeout_sec: float = 1.5) -> bool:
    """SLEEP(비트4)이 0으로 읽힐 때까지 wake 값을 재기록한다. 깨어나면 True."""
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        try:
            mode1 = _read_mode1(i2c_bus, address)
        except OSError as error:
            logger.warning("[PCA9685] %s MODE1 읽기 실패: %s", name, error)
            time.sleep(0.05)
            continue
        if not (mode1 & _MODE1_SLEEP):
            return True
        try:
            _write_mode1(i2c_bus, address, mode1 & _MODE1_WAKE_MASK)
        except OSError as error:
            logger.warning("[PCA9685] %s MODE1 쓰기 실패: %s", name, error)
        time.sleep(0.02)
    logger.error(
        "[PCA9685] %s(%#04x) SLEEP 래치에서 못 깨어남 — 명령이 ACK돼도 PWM 출력 "
        "전체가 죽어 있다. HAT 전원(모터 배터리+로직 3.3V)을 껐다 켜고 재시작할 것.",
        name, address)
    return False
