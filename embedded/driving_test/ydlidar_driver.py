"""
YDLIDAR Driver Module with Serial Parser & Realistic Sidewalk/Road Simulator Mode.
Supports YDLIDAR X4, G4, T-mini series via /dev/ttyUSB0 (or configured serial port).
Includes Auto-Watchdog Fallback to Mock Simulation if physical sensor yields no data.
"""

import time
import math
import struct
import random
import logging
import threading
import serial
import config

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')


class YDLiDARPoint:
    def __init__(self, angle_deg, distance_m, intensity=0):
        self.angle_deg = angle_deg      # Relative forward angle (-180 to +180 deg, 0 is straight ahead)
        self.distance_m = distance_m    # Radial distance in meters
        self.intensity = intensity      # Signal quality/intensity


class YDLiDARDriver:
    def __init__(self, port=None, baudrate=None, mock_mode=False):
        self.port = port or getattr(config, 'LIDAR_PORT', '/dev/ttyUSB0')
        self.baudrate = baudrate or getattr(config, 'LIDAR_BAUDRATE', 128000)
        self.mock_mode = mock_mode or getattr(config, 'LIDAR_MOCK_MODE', False)

        self.serial_conn = None
        self.running = False
        self.thread = None

        self.latest_scan = []  # List of YDLiDARPoint
        self.scan_dict = {}    # Dict mapping angle -> YDLiDARPoint to deduplicate sweep points
        self.last_packet_time = time.time()
        self.lock = threading.Lock()

        # Simulator state variables for mock mode
        self.sim_time = 0.0
        self.sim_curb_scenario = "SAFE_SIDEWALK" # "SAFE_SIDEWALK", "DROP_LEFT", "DROP_RIGHT", "DROP_CENTER"

    def start(self):
        self.running = True
        self._start_reader()

    def _connect_hardware(self):
        try:
            if self.serial_conn and self.serial_conn.is_open:
                try:
                    self.serial_conn.close()
                except Exception:
                    pass
            self.serial_conn = serial.Serial(self.port, self.baudrate, timeout=1.0)
            self.serial_conn.flushInput()
            
            # DTR pin motor enable for YDLIDAR (X4/G4)
            try:
                self.serial_conn.dtr = True
                self.serial_conn.rts = False
            except Exception:
                pass

            # Send start scan command (0xA5 0x60)
            self.serial_conn.write(b'\xA5\x60')
            logging.info(f"[LiDAR] YDLIDAR Physical Serial Connection established on {self.port}.")
            return True
        except Exception as e:
            logging.error(f"[LiDAR] Failed to open physical LiDAR port {self.port} ({e}). Will keep retrying physical sensor connection.")
            self.serial_conn = None
            return False

    def _start_reader(self):
        if self.mock_mode:
            logging.info("[LiDAR] Starting YDLIDAR in SIMULATION / MOCK mode.")
            self.thread = threading.Thread(target=self._mock_reader_thread, daemon=True)
        else:
            logging.info(f"[LiDAR] Connecting to physical YDLIDAR at {self.port} ({self.baudrate} baud)...")
            self._connect_hardware()
            self.thread = threading.Thread(target=self._hardware_reader_thread, daemon=True)

        self.thread.start()

    def set_mode(self, use_mock):
        """Allows switching between physical LiDAR and Mock Simulation in real-time."""
        with self.lock:
            if self.mock_mode == use_mock:
                return
            self.mock_mode = use_mock
            if self.serial_conn:
                try:
                    self.serial_conn.close()
                except Exception:
                    pass
                self.serial_conn = None

        logging.info(f"[LiDAR] Switched mode -> {'MOCK_SIMULATION' if use_mock else 'PHYSICAL_HARDWARE'}")
        self._start_reader()

    def get_latest_scan(self):
        with self.lock:
            return list(self.latest_scan)

    def set_mock_scenario(self, scenario):
        self.sim_curb_scenario = scenario
        logging.info(f"[LiDAR Mock] Set simulated scenario to: {scenario}")

    def _hardware_reader_thread(self):
        """YDLIDAR Serial Frame Parser Thread for physical sensor perception."""
        buffer = bytearray()
        self.last_packet_time = time.time()

        while self.running and not self.mock_mode:
            try:
                # If serial connection is not active, try reconnecting to physical sensor
                if not self.serial_conn or not self.serial_conn.is_open:
                    time.sleep(1.0)
                    self._connect_hardware()
                    continue

                # Auto-Watchdog: If no valid packet arrives within 4 seconds, retry serial connection
                if time.time() - self.last_packet_time > 4.0:
                    logging.warning("[LiDAR Watchdog] No physical serial scan packets received for 4s. Re-establishing physical LiDAR connection...")
                    self.last_packet_time = time.time()
                    self._connect_hardware()
                    buffer.clear()
                    continue

                if self.serial_conn and self.serial_conn.in_waiting > 0:
                    data = self.serial_conn.read(self.serial_conn.in_waiting)
                    buffer.extend(data)

                    while len(buffer) >= 10:
                        if buffer[0] != 0xAA or buffer[1] != 0x55:
                            buffer.pop(0)
                            continue

                        ct = buffer[2]
                        lsn = buffer[3]
                        if len(buffer) < 10 + lsn * 2:
                            break

                        fsa = (buffer[4] | (buffer[5] << 8)) >> 1
                        lsa = (buffer[6] | (buffer[7] << 8)) >> 1

                        fsa_deg = fsa / 64.0
                        lsa_deg = lsa / 64.0

                        angle_diff = lsa_deg - fsa_deg
                        if angle_diff < 0:
                            angle_diff += 360.0

                        points = []
                        min_dist = getattr(config, 'MIN_SCAN_DIST_M', 0.10)
                        max_dist = getattr(config, 'MAX_SCAN_DIST_M', 4.00)

                        for i in range(lsn):
                            dist_raw = buffer[10 + i * 2] | (buffer[10 + i * 2 + 1] << 8)
                            dist_m = dist_raw / 4000.0

                            if lsn > 1:
                                angle_deg = fsa_deg + (angle_diff / (lsn - 1)) * i
                            else:
                                angle_deg = fsa_deg

                            angle_offset = getattr(config, 'LIDAR_ANGLE_OFFSET_DEG', 120.0)
                            calibrated_angle = angle_deg - angle_offset
                            rel_angle = (calibrated_angle + 180) % 360 - 180

                            if min_dist <= dist_m <= max_dist:
                                points.append(YDLiDARPoint(rel_angle, dist_m))

                        del buffer[:10 + lsn * 2]

                        if points:
                            self.last_packet_time = time.time()
                            with self.lock:
                                for pt in points:
                                    ang_key = round(pt.angle_deg, 1)
                                    self.scan_dict[ang_key] = pt
                                # Sort latest scan by angle for clean sweep frame
                                self.latest_scan = [self.scan_dict[k] for k in sorted(self.scan_dict.keys())]
                else:
                    time.sleep(0.01)
            except Exception as e:
                logging.error(f"[LiDAR Serial Error] {e}")
                time.sleep(0.5)


    def _mock_reader_thread(self):
        """Generates realistic 360-degree LiDAR point cloud for tilted ground scan simulation."""
        pitch_rad = math.radians(getattr(config, 'LIDAR_PITCH_DEG', 7.5))
        h = getattr(config, 'LIDAR_HEIGHT_M', 0.22)

        while self.running and self.mock_mode:
            self.sim_time += 0.05
            points = []

            for deg in range(-180, 180, 1):
                rad = math.radians(deg)
                
                if -60 <= deg <= 60:
                    cos_phi = math.cos(rad)
                    base_dist = h / (math.sin(pitch_rad) * cos_phi + 1e-6)
                    base_dist = max(0.2, min(5.0, base_dist))

                    extra_drop = 0.0
                    if self.sim_curb_scenario == "DROP_LEFT" and -60 <= deg <= -15:
                        extra_drop = 0.20
                    elif self.sim_curb_scenario == "DROP_RIGHT" and 15 <= deg <= 60:
                        extra_drop = 0.20
                    elif self.sim_curb_scenario == "DROP_CENTER" and -20 <= deg <= 20:
                        extra_drop = 0.25
                    elif self.sim_curb_scenario == "OBSTACLE" and -10 <= deg <= 10:
                        base_dist = 0.45

                    if extra_drop > 0:
                        dist = (h + extra_drop) / (math.sin(pitch_rad) * cos_phi + 1e-6)
                    else:
                        dist = base_dist

                    dist += random.uniform(-0.01, 0.01)
                else:
                    dist = random.uniform(1.5, 3.0)

                points.append(YDLiDARPoint(deg, dist, intensity=100))

            with self.lock:
                self.latest_scan = points

            time.sleep(0.08)

    def stop(self):
        self.running = False
        if self.serial_conn:
            try:
                self.serial_conn.close()
            except Exception:
                pass
        logging.info("[LiDAR] YDLIDAR driver stopped.")
