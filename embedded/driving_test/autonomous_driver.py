"""
Autonomous Navigation Controller (Sensor Fusion Engine).
Fuses Horizontal 2D LiDAR Obstacle Avoidance with Camera YOLO Sidewalk Segmentation & Braille Block Capture.
"""

import time
import logging
import threading
import config
from ydlidar_driver import YDLiDARDriver
from horizontal_obstacle_detector import HorizontalObstacleDetector
from camera_vision import CameraVisionProcessor
from motor_driver import get_motor_driver

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')


class AutonomousDriver:
    def __init__(self, motor_mode=None):
        self.motor_driver = get_motor_driver(motor_mode)
        self.lidar_driver = YDLiDARDriver()
        self.obstacle_detector = HorizontalObstacleDetector()
        self.vision_processor = CameraVisionProcessor()

        self.is_auto = False  # Toggle Autonomous Mode
        self.running = False
        self.thread = None
        self.lock = threading.Lock()

        # Telemetry & Status Cache
        self.last_lidar_result = {}
        self.last_vision_result = {}
        self.last_control_action = "MANUAL_IDLE"

    def start(self):
        self.running = True
        self.lidar_driver.start()
        self.vision_processor.start()

        self.thread = threading.Thread(target=self._control_loop, daemon=True)
        self.thread.start()
        logging.info("[AutoDriver] Autonomous Sensor Fusion Control Engine Started.")

    def set_auto_mode(self, enabled):
        with self.lock:
            self.is_auto = enabled
            if not enabled:
                self.motor_driver.stop()
                self.last_control_action = "MANUAL_MODE"
            else:
                self.last_control_action = "AUTO_ENGAGED"
        logging.info(f"[AutoDriver] Mode switched to: {'AUTONOMOUS' if enabled else 'MANUAL'}")

    def update_hardware_mode(self, new_mode):
        with self.lock:
            self.motor_driver.stop()
            self.motor_driver.cleanup()
            self.motor_driver = get_motor_driver(new_mode)
        logging.info(f"[AutoDriver] Hardware driver changed to: {new_mode}")

    def set_mock_scenario(self, scenario):
        self.lidar_driver.set_mock_scenario(scenario)

    def _control_loop(self):
        center_angle = getattr(config, 'STEERING_CENTER', 100)

        while self.running:
            try:
                cruise_speed = getattr(config, 'SAFE_CRUISE_SPEED', 75)
                cornering_speed = getattr(config, 'CORNERING_SPEED', 75)

                # 1. Fetch Horizontal LiDAR Scan & Run Obstacle Avoidance Algorithm
                scan = self.lidar_driver.get_latest_scan()
                lidar_result = self.obstacle_detector.analyze_scan(scan)

                # 2. Fetch Camera Vision Sidewalk Steering & Braille Detections
                vision_result = self.vision_processor.get_vision_steering()

                with self.lock:
                    self.last_lidar_result = lidar_result
                    self.last_vision_result = vision_result

                # 3. Autonomous Control & Steering Sensor Fusion
                if self.is_auto:
                    lidar_status = lidar_result['overall_status']
                    avoid_offset = lidar_result['suggested_avoid_offset']
                    vision_steer = vision_result['suggested_steer_angle']
                    braille_blocks = vision_result['detected_braille_blocks']

                    # Priority 1: LiDAR Emergency Front Obstacle Brake
                    if lidar_result['is_emergency_stop']:
                        self.motor_driver.stop()
                        time.sleep(0.1)
                        backup_steer = center_angle + avoid_offset
                        self.motor_driver.set_angle(backup_steer)
                        self.motor_driver.backward(35)  # Back up slightly
                        time.sleep(0.3)
                        self.last_control_action = f"EMERGENCY BRAKE -> LiDAR Front Obstacle! ({lidar_result['front_min_dist']}m)"

                    # Priority 2: LiDAR Obstacle Avoidance Steering Compensation
                    elif lidar_status in ["OBSTACLE_FRONT", "OBSTACLE_LEFT", "OBSTACLE_RIGHT"]:
                        fused_steer = vision_steer + avoid_offset
                        fused_steer = max(config.STEERING_MAX_LEFT, min(config.STEERING_MAX_RIGHT, fused_steer))
                        self.motor_driver.set_angle(fused_steer)
                        self.motor_driver.forward(cornering_speed)
                        self.last_control_action = f"OBSTACLE AVOID -> Steering {int(fused_steer)}° ({lidar_status})"

                    # Priority 3: Vision Sidewalk Path Following
                    elif vision_result['sidewalk_detected']:
                        self.motor_driver.set_angle(vision_steer)
                        self.motor_driver.forward(cruise_speed)
                        
                        braille_info = f" [BRAILLE DETECTED!]" if len(braille_blocks) > 0 else ""
                        self.last_control_action = f"SIDEWALK CRUISE -> Steering {int(vision_steer)}°{braille_info}"

                    else:
                        # Fallback Safe Forward
                        self.motor_driver.set_angle(center_angle)
                        self.motor_driver.forward(cruise_speed)
                        self.last_control_action = "CRUISE (NO MASK DETECTED)"

                time.sleep(0.05)  # 20 Hz Control Loop
            except Exception as e:
                logging.error(f"[AutoDriver Loop Error] {e}")
                time.sleep(0.1)

    def get_status(self):
        with self.lock:
            return {
                'is_auto': self.is_auto,
                'hardware_mode': self.motor_driver.__class__.__name__,
                'speed': self.motor_driver.current_speed,
                'direction': self.motor_driver.current_direction,
                'angle': self.motor_driver.current_angle,
                'last_action': self.last_control_action,
                'lidar': self.last_lidar_result,
                'vision': self.last_vision_result,
                'captures': self.vision_processor.get_capture_logs()
            }

    def stop(self):
        self.running = False
        self.set_auto_mode(False)
        self.lidar_driver.stop()
        self.vision_processor.stop()
        self.motor_driver.stop()
        self.motor_driver.cleanup()
        logging.info("[AutoDriver] Sensor Fusion Navigation system safely stopped.")
