"""INA226 battery voltage reader over software (bit-bang) I2C.

The onboard INA3221 (bus 1) and motor HAT (bus 7) both occupy 0x40 and the
INA226 module has no A0/A1 pads, so the INA226 gets its own private bus made
from two spare header pins:

    Jetson 40-pin        INA226 module
    -------------        -------------
    pin 1  (3.3V)  --->  VCC   (MUST be 3.3V, not 5V: onboard pull-ups
    pin 30 (GND)   --->  GND    would lift SDA/SCL above the 3.3V GPIO limit)
    pin 29 (GPIO)  --->  SDA
    pin 31 (GPIO)  --->  SCL
    battery pack + --->  IN+   (voltage only; shunt current not used)

The module's onboard 10k pull-ups to VCC provide the open-drain highs, so no
extra resistors are needed. Lines are driven low by switching the pin to
output-low and released by switching back to input.

Run directly for a live readout:  python3 battery.py
"""

import time

import Jetson.GPIO as GPIO

PIN_SDA = 29  # BOARD numbering
PIN_SCL = 31

INA226_ADDR = 0x40
REG_BUS_VOLTAGE = 0x02          # LSB = 1.25 mV
REG_MANUFACTURER_ID = 0xFE      # reads 0x5449 ("TI")

# Per-cell LiPo open-circuit voltage -> charge percent
_CELL_CURVE = [
    (4.20, 100), (4.15, 95), (4.11, 90), (4.08, 85), (4.02, 80),
    (3.98, 75), (3.95, 70), (3.91, 65), (3.87, 60), (3.85, 55),
    (3.84, 50), (3.82, 45), (3.80, 40), (3.79, 35), (3.77, 30),
    (3.75, 25), (3.73, 20), (3.71, 15), (3.69, 10), (3.61, 5),
    (3.27, 0),
]


class I2CNackError(IOError):
    pass


class SoftI2C:
    """Minimal open-drain bit-bang I2C master (master-only, no clock stretch)."""

    def __init__(self, sda=PIN_SDA, scl=PIN_SCL, delay=5e-6):
        self._delay = delay
        self._sda = sda
        self._scl = scl
        GPIO.setwarnings(False)
        GPIO.setmode(GPIO.BOARD)
        GPIO.setup(sda, GPIO.IN)  # released = pulled high by module
        GPIO.setup(scl, GPIO.IN)

    def close(self):
        GPIO.cleanup([self._sda, self._scl])

    # -- line primitives -------------------------------------------------
    def _release(self, pin):
        GPIO.setup(pin, GPIO.IN)
        time.sleep(self._delay)

    def _drive_low(self, pin):
        GPIO.setup(pin, GPIO.OUT, initial=GPIO.LOW)
        time.sleep(self._delay)

    def _read(self, pin):
        return GPIO.input(pin)

    # -- bus conditions ---------------------------------------------------
    def _start(self):
        # SDA falls while SCL is high (works as both start and repeated start)
        self._release(self._sda)
        self._release(self._scl)
        self._drive_low(self._sda)
        self._drive_low(self._scl)

    def _stop(self):
        self._drive_low(self._sda)
        self._release(self._scl)
        self._release(self._sda)

    def _write_byte(self, byte):
        for i in range(7, -1, -1):
            if (byte >> i) & 1:
                self._release(self._sda)
            else:
                self._drive_low(self._sda)
            self._release(self._scl)
            self._drive_low(self._scl)
        # ACK bit: slave pulls SDA low
        self._release(self._sda)
        self._release(self._scl)
        ack = self._read(self._sda) == GPIO.LOW
        self._drive_low(self._scl)
        return ack

    def _read_byte(self, ack):
        self._release(self._sda)
        byte = 0
        for _ in range(8):
            self._release(self._scl)
            byte = (byte << 1) | (1 if self._read(self._sda) else 0)
            self._drive_low(self._scl)
        if ack:
            self._drive_low(self._sda)
        else:
            self._release(self._sda)
        self._release(self._scl)
        self._drive_low(self._scl)
        self._release(self._sda)
        return byte

    # -- transactions -----------------------------------------------------
    def read_word(self, addr, reg):
        """Read a 16-bit big-endian register (INA226 style)."""
        self._start()
        try:
            if not self._write_byte(addr << 1):
                raise I2CNackError(f"no ACK from 0x{addr:02x} (write)")
            if not self._write_byte(reg):
                raise I2CNackError(f"no ACK for register 0x{reg:02x}")
            self._start()  # repeated start
            if not self._write_byte((addr << 1) | 1):
                raise I2CNackError(f"no ACK from 0x{addr:02x} (read)")
            msb = self._read_byte(ack=True)
            lsb = self._read_byte(ack=False)
        finally:
            self._stop()
        return (msb << 8) | lsb

    def bus_ok(self):
        """True when both released lines read high (pull-ups present).

        Floating pins read low, which makes every address appear to ACK, so
        callers must check this before trusting probe()/scan().
        """
        self._release(self._sda)
        self._release(self._scl)
        return self._read(self._sda) == GPIO.HIGH and self._read(self._scl) == GPIO.HIGH

    def probe(self, addr):
        self._start()
        ack = self._write_byte(addr << 1)
        self._stop()
        return ack

    def scan(self):
        return [a for a in range(0x08, 0x78) if self.probe(a)]


class INA226Battery:
    def __init__(self, bus=None, cells=None):
        self.bus = bus or SoftI2C()
        mfg = self.bus.read_word(INA226_ADDR, REG_MANUFACTURER_ID)
        if mfg != 0x5449:
            raise IOError(f"INA226 not found (manufacturer id 0x{mfg:04x})")
        self.cells = cells or self._detect_cells()

    def _detect_cells(self):
        v = self.voltage()
        cells = max(2, min(4, round(v / 3.85)))
        return cells

    def voltage(self):
        raw = self.bus.read_word(INA226_ADDR, REG_BUS_VOLTAGE)
        return raw * 1.25e-3

    def percent(self):
        per_cell = self.voltage() / self.cells
        if per_cell >= _CELL_CURVE[0][0]:
            return 100
        if per_cell <= _CELL_CURVE[-1][0]:
            return 0
        for (v_hi, p_hi), (v_lo, p_lo) in zip(_CELL_CURVE, _CELL_CURVE[1:]):
            if per_cell >= v_lo:
                frac = (per_cell - v_lo) / (v_hi - v_lo)
                return round(p_lo + frac * (p_hi - p_lo))
        return 0


if __name__ == "__main__":
    bus = SoftI2C()
    if not bus.bus_ok():
        print("SDA/SCL not pulled high — INA226 not wired or VCC unpowered "
              "(SDA=pin29, SCL=pin31, VCC=3.3V pin1, GND=pin30)")
        bus.close()
        raise SystemExit(1)
    found = bus.scan()
    print(f"scan: {[hex(a) for a in found] or 'no devices'}")
    if INA226_ADDR not in found:
        print("INA226 (0x40) not responding — check wiring (SDA=29, SCL=31, VCC=3.3V)")
        bus.close()
        raise SystemExit(1)
    batt = INA226Battery(bus)
    print(f"detected {batt.cells}S pack")
    try:
        while True:
            v = batt.voltage()
            print(f"{v:6.3f} V  ({v / batt.cells:.3f} V/cell)  {batt.percent():3d}%")
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        bus.close()
