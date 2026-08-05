"""Live monitor for the INA226 soft-I2C lines (pins 29/31).

Run this, then wiggle/reseat each jumper one at a time and watch the
readout change.  Both lines must show HIGH before battery.py can work.

    python3 wire_check.py          # Ctrl+C to quit
"""

import sys
import time

import Jetson.GPIO as GPIO

PIN_SDA = 29
PIN_SCL = 31

GPIO.setwarnings(False)
GPIO.setmode(GPIO.BOARD)
GPIO.setup([PIN_SDA, PIN_SCL], GPIO.IN)

try:
    while True:
        sda = GPIO.input(PIN_SDA)
        scl = GPIO.input(PIN_SCL)
        state = (
            f"SDA(pin29)={'HIGH' if sda else 'LOW '}  "
            f"SCL(pin31)={'HIGH' if scl else 'LOW '}  "
            f"{'*** OK ***' if sda and scl else 'not pulled up'}"
        )
        sys.stdout.write("\r" + state + "   ")
        sys.stdout.flush()
        time.sleep(0.1)
except KeyboardInterrupt:
    print()
finally:
    GPIO.cleanup([PIN_SDA, PIN_SCL])
