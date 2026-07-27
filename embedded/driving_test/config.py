"""
AutoDrive Configuration File
Defines Motor Hardware, YDLIDAR settings, Horizontal LiDAR Obstacle Avoidance, 
YOLO Sidewalk Segmentation, and Braille Block Detection parameters.
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
STEERING_MAX_RIGHT = 160
STEERING_STEP = 15

# Default Speeds (%)
DEFAULT_SPEED = 75
MAX_SPEED = 100
MIN_SPEED = 0
SAFE_CRUISE_SPEED = 75
CORNERING_SPEED = 75

# ==========================================
# 2. Horizontal YDLIDAR Obstacle Avoidance Settings
# ==========================================
LIDAR_PORT = '/dev/ttyUSB0'
LIDAR_BAUDRATE = 128000  # Default for YDLIDAR X4/G4/T-mini
LIDAR_MOCK_MODE = False
LIDAR_ANGLE_OFFSET_DEG = 200.0  # Sensor alignment offset (0° = front)

# Horizontal Distance Safety Thresholds (Meters)
OBSTACLE_STOP_DIST_M = 0.40   # Immediate stop threshold for front obstacles
OBSTACLE_WARN_DIST_M = 0.75   # Slowdown & steering avoidance threshold

# Field of View Sectors (Degrees relative to front 0°)
FOV_FRONT_MIN = -30.0
FOV_FRONT_MAX = 30.0
FOV_LEFT_MIN = -75.0
FOV_LEFT_MAX = -15.0
FOV_RIGHT_MIN = 15.0
FOV_RIGHT_MAX = 75.0

MIN_SCAN_DIST_M = 0.10
MAX_SCAN_DIST_M = 4.00

# ==========================================
# 3. Vision System (Camera, YOLO Seg & Braille Detector)
# ==========================================
CAMERA_INDEX = 0
CAMERA_WIDTH = 640
CAMERA_HEIGHT = 480
CAMERA_FPS = 30

# Braille Block Detection Model (YOLO Object Detection)
BRAILLE_MODEL_PATH = "/home/ubuntu/SSAFY/models/best.pt"
BRAILLE_CONF_THRES = 0.35
BRAILLE_CAPTURE_DIR = "/home/ubuntu/SSAFY/captures"
BRAILLE_CAPTURE_COOLDOWN_SEC = 2.5  # Seconds between automated frame saves

# Sidewalk Segmentation Model (YOLOv11 Segmentation)
SIDEWALK_SEG_MODEL_PATH = "yolo11n-seg.pt"
SIDEWALK_CONF_THRES = 0.25

# Autonomous Fusion Weights
STEER_GAIN = 1.5  # Sensitivity gain for vision sidewalk center offset
