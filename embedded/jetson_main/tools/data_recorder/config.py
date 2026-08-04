"""
Hardware & camera configuration.

Copied from the deleted DrivingModule/config.py (2026-07-30 reset) so
DataRecorder is self-contained - motor_driver.py imports this. NOTE: this copy
turned out to be an OLDER snapshot than /home/ubuntu/DrivingCore (restored from
DrivingCore_backup_20260730.zip), which holds the authoritative field-tuned
calibration. The steering lock range below was re-synced from that backup on
2026-07-30; motor_driver.py was replaced with the backup version outright.
"""

import os

# ==========================================
# 1. Hardware Motor Control Settings
# ==========================================
# HARDWARE_MODE: 'PCA9685', 'GPIO', 'HYBRID', or 'MOCK'
HARDWARE_MODE = 'PCA9685'

# PCA9685 Addresses and Channels
PCA9685_ADDRESS = 0x40
SERVO_ADDRESS = 0x60
PCA9685_STEERING_CHANNEL = 0
PCA9685_MOTOR_PWM_CH = 5
PCA9685_MOTOR_IN1_CH = 4
PCA9685_MOTOR_IN2_CH = 3

# GPIO Pin Configuration (Jetson.GPIO)
GPIO_PIN_MODE = 'BOARD'
MOTOR_ENA = 33
MOTOR_IN1 = 29
MOTOR_IN2 = 31
MOTOR_ENB = 32
MOTOR_IN3 = 35
MOTOR_IN4 = 37
PWM_FREQ = 1000

# Steering Angle Calibration (Degrees)
STEERING_CENTER = 100
STEERING_MAX_LEFT = 40
# MEASURED 2026-07-28 (restored from DrivingCore backup): usable range is 40..130 -
# beyond 130 to the right the linkage binds and the drivetrain stalls. The range is
# asymmetric around center 100 (60° of left lock vs 30° of right lock).
STEERING_MAX_RIGHT = 130
STEERING_STEP = 5

# Default Speeds (%)
DEFAULT_SPEED = 50
MAX_SPEED = 85
MIN_SPEED = 0
SAFE_CRUISE_SPEED = 48
CORNERING_SPEED = 50         # Throttle for the bypass maneuver. Raised from 35 after the speed
                             # calibration: the maneuver legs are arc-length based, so clearance is
                             # unchanged (0.31 m) while a bypass finishes ~3.8 s sooner. Not raised
                             # further - above ~60 the legs get short enough (<2.5 s) that drivetrain
                             # spool-up becomes a large fraction of each leg and eats the margin.

# ==========================================
# 2. Camera Geometry & Vision Navigation
# ==========================================
CAMERA_HEIGHT_CM = 20.0     # Camera height from ground (20cm)
CAMERA_PITCH_DEG = 15.0      # Downward pitch angle (15 degrees)

# Two USB cameras with separate roles. Both share ONE USB 2.0 hub, so both captures
# force MJPG - two uncompressed YUYV streams exceed the hub bandwidth and the second
# camera fails to open.
NAV_CAMERA_INDEX = 0         # 보도블럭 인식 + 주행 판단 (Web Camera, /dev/video0)
FRONT_CAMERA_INDEX = 2       # 전방 주행 확인용 모니터링 (Brio 100, /dev/video2).
                             # Capture + stream only: runs no inference and must never
                             # feed the navigator or its watchdog.
CAMERA_INDEX = NAV_CAMERA_INDEX
CAMERA_WIDTH = 640
CAMERA_HEIGHT = 480
CAMERA_FPS = 30

FRONT_CAMERA_WIDTH = 640
FRONT_CAMERA_HEIGHT = 480
FRONT_CAMERA_FPS = 15        # Monitoring only - kept low to leave USB bandwidth and
                             # CPU for the navigation pipeline
FRONT_CAMERA_JPEG_QUALITY = 70

# Navigation Safety Corridors (Pixels in 640x480 frame)
SAFE_CORRIDOR_LEFT_X = 200
SAFE_CORRIDOR_RIGHT_X = 440
OBSTACLE_DANGER_Y = 280      # Bottom region is close to bot

# YOLO Segmentation Settings
YOLO_MODEL_PATH = "/home/ubuntu/SSAFY/yolo11n-seg.pt"
CONF_THRESHOLD = 0.25
IOU_THRESHOLD = 0.45
MASK_ALPHA = 0.40

# Obstacle Classes to Avoid
OBSTACLE_CLASS_IDS = [0, 1, 2, 3, 5, 7, 15, 16, 56, 57, 60]  # person, bicycle, car, motorcycle, bus, truck, cat, dog, chair, couch, table
ROAD_VEHICLE_CLASS_IDS = [1, 2, 3, 5, 7]                    # Road vehicles indicating road region

# ==========================================
# 3. Obstacle Behavior State Machine
#    CRUISE -> STOP_HOLD -> (clear? CRUISE) / (persist? AVOID -> RECOVER -> CRUISE)
# ==========================================
# Detection gating (how an obstacle qualifies as "blocking the path")
OBSTACLE_TRIGGER_DIST_M = 1.20    # Only obstacles within this ground distance block the path
OBSTACLE_TRIGGER_Y = 300          # ...or whose box bottom is below this pixel row (very close)
OBSTACLE_CONFIRM_FRAMES = 2       # Consecutive frames required before braking (debounce false positives)
OBSTACLE_CLEAR_GRACE_SEC = 0.6    # Obstacle must be absent this long before it counts as "gone"

# Stop-and-observe phase
OBSTACLE_STOP_HOLD_SEC = 3.0      # Hold still this long; if obstacle stays put -> avoid, if it leaves -> go straight
STOP_HOLD_MAX_SEC = 6.0           # Hard cap on holding (re-anchoring can extend the hold) -> force avoid
ANCHOR_MATCH_TOL_X = 90           # Same-obstacle match tolerance, horizontal pixels
ANCHOR_MATCH_TOL_Y = 80           # Same-obstacle match tolerance, vertical pixels

# --- Speed calibration: MEASURE THIS ---
# Ground speed in m/s per 1% throttle. Every leg of the bypass maneuver is specified as a
# distance and converted to a duration with this number, so if it is wrong the bot turns
# back onto the obstacle's line too early. Measure it once: drive forward at a known
# throttle % on a flat floor, time 2 m, then set = (2 / seconds) / throttle_pct.
BOT_SPEED_MPS_PER_PCT = 0.0032    # MEASURED 2026-07-28: 34 cm in 3.008 s at 35% throttle
                                  # => 0.113 m/s at CORNERING_SPEED, 0.32 m/s at full throttle.
                                  # Re-measure if the chassis, tyres, gearing or battery pack change.

# Avoidance maneuver phase (distances, converted to durations via BOT_SPEED_MPS_PER_PCT)
AVOID_STEER_AMOUNT = 55           # Steering offset from center during the evasive turn (degrees)
AVOID_TURN_DIST_M = 0.40          # Arc length of the evasive turn away from the obstacle.
                                  # The mirrored recovery turn reuses this, so the yaw cancels out.
AVOID_PASS_DIST_M = 0.60          # Straight travel alongside the obstacle before turning back.
                                  # Must exceed the obstacle depth + wheelbase to clear it.
AVOID_BLIND_CLEAR_SEC = 1.0       # Extra straight running required after a *close* obstacle drops out
                                  # of sight. The camera has a near blind spot, so "not detected"
                                  # at short range does NOT mean the path is clear.
AVOID_PASS_EXTRA_MAX_SEC = 2.0    # Cap on that extra straight leg, so it can never run forever
AVOID_MAX_ATTEMPTS = 3            # Evasive turns tried (alternating sides) before parking in BLOCKED
POST_AVOID_COOLDOWN_SEC = 1.0     # Ignore new stop triggers right after recovery (obstacle is beside us)

AVOID_ABORT_GRACE_SEC = 0.6       # An evasive turn gets this long before a brake can abort it
                                  # (the obstacle is still dead ahead when the turn starts)

# Reverse escape (used when an avoidance attempt ends up blocked anyway)
REVERSE_DIST_M = 0.25
REVERSE_SPEED = 35

# What counts as "close" for the blind-spot rule above. Anything inside the trigger band
# counts: the near blind spot starts well before the bumper.
CLOSE_OBSTACLE_DIST_M = 1.20
CLOSE_OBSTACLE_Y = 300
RECOVER_MAX_ABORTS = 3            # Times the turn-back may be postponed by the obstacle
                                  # reappearing ahead, before it is forced through

# Emergency brake (overrides every state)
CRITICAL_STOP_DIST_M = 0.50        # Widened from 0.40: at 0.40 a mis-calibrated bypass could
                                   # graze an obstacle that was visible for only one frame
CRITICAL_STOP_Y = 450
CRITICAL_BUMPER_HALF_W = 70        # Half-width (px, from frame center) of the physical front bumper.
                                   # Only obstacles overlapping this band can trigger the emergency brake;
                                   # anything further out to the side is handled by the normal stop logic.

# ==========================================
# 4. Fail-safe: Watchdog & Evidence Gate
# ==========================================
# The motor driver holds its last PWM until something changes it, so "commands stopped
# arriving" is NOT the same as "stop". A dead operator link therefore means the bot keeps
# driving forever unless a watchdog actively brakes it. The watchdog runs on its own thread,
# independent of the vision loop, because a stalled camera thread would otherwise leave the
# state machine uncalled and the last throttle latched.
WATCHDOG_ENABLED = True
WATCHDOG_LINK_TIMEOUT_SEC = 2.5    # No operator heartbeat for this long -> brake.
                                   # 2.5s at SAFE_CRUISE_SPEED is ~38cm of travel.
                                   # Phone hotspots sleep their WiFi briefly, so do not go
                                   # much below this or nuisance stops become common.
WATCHDOG_FRAME_TIMEOUT_SEC = 1.0   # Vision pipeline stalled (camera died, inference hung) -> brake.
                                   # Driving is only allowed while perception is demonstrably alive.
WATCHDOG_TICK_HZ = 10

# --- Drivable-surface evidence gate ---
# Sidewalk driving must invert the default from "no obstacle detected -> go" to
# "no positive evidence of drivable ground -> stop". Curbs, sidewalk ends and drop-offs
# show up as the ABSENCE of detections, never as a detected object.
#
# NOTE: there is no drivable-surface perception module yet, so with this flag False the gate
# is inert - it is scaffolding for the free-space module and reports evidence_source=NONE.
# It MUST be turned on for outdoor driving. The watchdog above protects today; this does not.
REQUIRE_DRIVABLE_EVIDENCE = False
DRIVABLE_MIN_CONFIDENCE = 0.5      # Evidence provider confidence below this counts as no evidence
DRIVABLE_MIN_LOOKAHEAD_M = 0.6     # Clear drivable ground required ahead before moving off

# Blind-running limits, active whenever there is no drivable-surface evidence
NO_EVIDENCE_LEASH_SEC = 30.0       # Continuous blind forward travel allowed before parking and
                                   # demanding a re-arm (~4.5m at cruise). 0 disables.
NO_EVIDENCE_SPEED_CAP = 35         # Cruise throttle cap while running blind. Deliberately applied
                                   # to cruising ONLY - capping the bypass maneuver would shorten
                                   # its legs below the distance they were computed for.
