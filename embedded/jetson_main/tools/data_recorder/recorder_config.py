"""
DataRecorder settings.

Hardware / camera geometry settings are NOT duplicated here - they are imported
from DrivingModule/config.py (main.py puts DrivingModule on sys.path first, and
this package must never contain its own config.py or motor_driver's
`import config` would resolve to the wrong file).
"""

import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# Web dashboard (segTest owns 8000)
HOST = "0.0.0.0"
PORT = 8100

# Where recording sessions are written: recordings/<YYYYmmdd_HHMMSS>/
RECORDINGS_DIR = os.path.join(BASE_DIR, "recordings")

# Preview stream
PREVIEW_JPEG_QUALITY = 70
PREVIEW_MAX_FPS = 15          # per client; keeps WiFi usable while driving

# Control log (steering/throttle alongside the videos, for imitation learning)
CONTROL_LOG_HZ = 20

# GPS (u-blox 7 on USB; by-id path survives replug/renumbering)
GPS_PORT = "/dev/serial/by-id/usb-u-blox_AG_-_www.u-blox.com_u-blox_7_-_GPS_GNSS_Receiver-if00"
GPS_BAUD = 9600

# Naver Cloud Maps. Only the client ID is sent to the browser (dynamic map);
# the secret is for server-side REST calls (geocoding) and must never leave here.
NAVER_MAP_CLIENT_ID = "k484gnacx3"
NAVER_MAP_CLIENT_SECRET = "YfLQ0sqargsxIfgMLFaBuPme1KuKn7lxxwZUkNkY"

# Joystick
JS_DEVICE = "/dev/input/js0"
JS_AXIS_STEER = 0             # left stick X
JS_AXIS_THROTTLE = 1          # left stick Y (up = forward, down = reverse)
JS_AXIS_LT = 2                # rest -32767, pressed +32767
JS_AXIS_RT = 5
JS_BTN_RECORD = 0             # A - toggle recording
JS_BTN_ESTOP = 1              # B - emergency stop
JS_BTN_CAP_DOWN = 2           # X - max throttle -10%
JS_BTN_CAP_UP = 3             # Y - max throttle +10%
JS_DEADZONE = 4000            # stick/trigger noise floor (of 32767)
DRIVE_LOOP_HZ = 20

# Throttle: stick/trigger 0..1 is scaled into THROTTLE_MIN..<current cap>.
# THROTTLE_MIN is the smallest command actually sent - below it the
# drivetrain just whines. The cap starts at THROTTLE_MAX and is adjusted
# live with the X/Y buttons in CAP_STEP increments (outdoor runs need 100%).
THROTTLE_MIN = 12
THROTTLE_MAX = 70             # initial cap on startup
THROTTLE_CAP_STEP = 10
THROTTLE_CAP_MIN = 20
THROTTLE_CAP_MAX = 100
# Reverse uses the same live throttle cap as forward (X/Y buttons adjust both).
