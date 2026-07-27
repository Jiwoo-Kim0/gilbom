"""
Curb Detector Module for Tilted 2D LiDAR (5~10 degrees downward).
Calculates ground surface distance baselines, height deviations (z-elevation in cm),
roadway step-down drops (급격한 단차), obstacles/curbs, and avoidance steering commands.
Includes temporal exponential moving average (EMA) smoothing for vehicle pitch stability.
"""

import math
import logging
import config

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')


class CurbDetector:
    def __init__(self, height_m=None, pitch_deg=None, drop_threshold_m=None, wall_threshold_m=None):
        self.height_m = height_m or getattr(config, 'LIDAR_HEIGHT_M', 0.22)
        self.pitch_deg = pitch_deg or getattr(config, 'LIDAR_PITCH_DEG', 7.5)
        self.drop_threshold_m = drop_threshold_m or getattr(config, 'DROP_THRESHOLD_M', 0.15)
        self.wall_threshold_m = wall_threshold_m or getattr(config, 'WALL_THRESHOLD_M', 0.12)

        # Sector boundaries (Degrees)
        self.left_range = (getattr(config, 'SECTOR_LEFT_MIN', -60.0), getattr(config, 'SECTOR_LEFT_MAX', -15.0))
        self.center_range = (getattr(config, 'SECTOR_CENTER_MIN', -15.0), getattr(config, 'SECTOR_CENTER_MAX', 15.0))
        self.right_range = (getattr(config, 'SECTOR_RIGHT_MIN', 15.0), getattr(config, 'SECTOR_RIGHT_MAX', 60.0))

        # Ground Calibration Offset (m)
        self.ground_calibration_offset = 0.0

        # Temporal EMA Cache for Z-deltas per angle (-60 to +60 deg)
        self.smoothed_z_map = {}
        self.ema_alpha = 0.25  # Smooth alpha (0.25 = 25% new scan, 75% historical average)

    def update_parameters(self, height_m=None, pitch_deg=None, drop_threshold_m=None, wall_threshold_m=None):
        if height_m is not None: self.height_m = float(height_m)
        if pitch_deg is not None: self.pitch_deg = float(pitch_deg)
        if drop_threshold_m is not None: self.drop_threshold_m = float(drop_threshold_m)
        if wall_threshold_m is not None: self.wall_threshold_m = float(wall_threshold_m)

    def calculate_expected_flat_distance(self, angle_deg):
        pitch_rad = math.radians(max(1.0, min(45.0, self.pitch_deg)))
        azimuth_rad = math.radians(angle_deg)
        cos_azimuth = math.cos(azimuth_rad)

        if cos_azimuth <= 0.01:
            return 999.0

        expected_d = (self.height_m + self.ground_calibration_offset) / (math.sin(pitch_rad) * cos_azimuth)
        return max(0.1, min(10.0, expected_d))

    def analyze_scan(self, scan_points):
        if not scan_points:
            return self._empty_result()

        left_pts, center_pts, right_pts = [], [], []
        processed_points = []
        elevation_profile = []

        pitch_rad = math.radians(max(1.0, min(45.0, self.pitch_deg)))
        sin_pitch = math.sin(pitch_rad)

        min_dist = getattr(config, 'MIN_SCAN_DIST_M', 0.10)
        max_dist = getattr(config, 'MAX_SCAN_DIST_M', 4.00)

        # 1. Filter valid distance points in FOV (-60 to 60 deg)
        fov_points = []
        for pt in scan_points:
            if -60.0 <= pt.angle_deg <= 60.0 and min_dist <= pt.distance_m <= max_dist:
                fov_points.append(pt)

        if not fov_points:
            return self._empty_result()

        # Sort FOV points by angle for Spatial Filtering
        fov_points.sort(key=lambda p: p.angle_deg)

        # 2. Compute Raw Z-deltas
        raw_deltas = []
        for pt in fov_points:
            expected_dist = self.calculate_expected_flat_distance(pt.angle_deg)
            dist_diff = pt.distance_m - expected_dist
            raw_z = dist_diff * sin_pitch * math.cos(math.radians(pt.angle_deg))
            raw_deltas.append(raw_z)

        # 3. Apply 5-Point Spatial Median Filter (Removes isolated spike noise across neighbor angles)
        n = len(raw_deltas)
        spatial_filtered_deltas = []
        window_radius = 2  # 5-point window (-2 to +2)
        for i in range(n):
            start_i = max(0, i - window_radius)
            end_i = min(n, i + window_radius + 1)
            neighbor_window = sorted(raw_deltas[start_i:end_i])
            median_val = neighbor_window[len(neighbor_window) // 2]
            spatial_filtered_deltas.append(median_val)

        # 4. Temporal EMA & Classification
        for pt, raw_z, filt_z in zip(fov_points, raw_deltas, spatial_filtered_deltas):
            angle = pt.angle_deg
            dist = pt.distance_m
            expected_dist = self.calculate_expected_flat_distance(angle)
            ang_key = int(round(angle))

            prev_z = self.smoothed_z_map.get(ang_key, filt_z)
            
            # Spike Rejection: If sudden deviation > 0.35m from historical trend, suppress spike weight
            if abs(filt_z - prev_z) > 0.35:
                effective_alpha = self.ema_alpha * 0.2
            else:
                effective_alpha = self.ema_alpha

            smoothed_z = effective_alpha * filt_z + (1.0 - effective_alpha) * prev_z
            self.smoothed_z_map[ang_key] = smoothed_z

            # Convert to cm for visual elevation profile
            z_cm = round(smoothed_z * 100.0, 1)

            # Classification based on smoothed z_delta
            if smoothed_z > self.drop_threshold_m:
                point_type = "ROAD_DROP"  # 도로 하향 단차 (인도 -> 도로 떨어짐)
            elif smoothed_z < -self.wall_threshold_m:
                point_type = "OBSTACLE"   # 연석 벽 / 장애물
            else:
                point_type = "SIDEWALK"   # 정상 인도 지면

            pt_info = {
                'angle': angle,
                'dist': dist,
                'expected_dist': expected_dist,
                'z_delta': smoothed_z,
                'z_cm': z_cm,
                'type': point_type
            }
            processed_points.append(pt_info)
            elevation_profile.append({'angle': int(round(angle)), 'z_cm': z_cm, 'type': point_type})

            if self.left_range[0] <= angle < self.left_range[1]:
                left_pts.append(pt_info)
            elif self.center_range[0] <= angle <= self.center_range[1]:
                center_pts.append(pt_info)
            elif self.right_range[0] < angle <= self.right_range[1]:
                right_pts.append(pt_info)

        # Sort elevation profile by angle
        elevation_profile.sort(key=lambda x: x['angle'])

        left_sector = self._evaluate_sector("LEFT", left_pts)
        center_sector = self._evaluate_sector("CENTER", center_pts)
        right_sector = self._evaluate_sector("RIGHT", right_pts)

        overall_status = "SAFE_SIDEWALK"
        recommended_action = "DRIVE_FORWARD"
        suggested_steer_offset = 0.0

        if center_sector['status'] == "ROADWAY_DROP":
            overall_status = "ROAD_EDGE_CENTER"
            recommended_action = "STOP_AND_AVOID"
            if left_sector['drop_ratio'] < right_sector['drop_ratio']:
                suggested_steer_offset = -35.0
            else:
                suggested_steer_offset = 35.0

        elif left_sector['status'] == "ROADWAY_DROP":
            overall_status = "ROAD_EDGE_LEFT"
            recommended_action = "STEER_RIGHT"
            suggested_steer_offset = min(40.0, 20.0 + left_sector['drop_ratio'] * 30.0)

        elif right_sector['status'] == "ROADWAY_DROP":
            overall_status = "ROAD_EDGE_RIGHT"
            recommended_action = "STEER_LEFT"
            suggested_steer_offset = -min(40.0, 20.0 + right_sector['drop_ratio'] * 30.0)

        elif center_sector['status'] == "OBSTACLE" or left_sector['status'] == "OBSTACLE" or right_sector['status'] == "OBSTACLE":
            overall_status = "OBSTACLE_AHEAD"
            recommended_action = "SLOW_OR_STOP"
            if left_sector['status'] == "OBSTACLE" and right_sector['status'] != "OBSTACLE":
                suggested_steer_offset = 25.0
            elif right_sector['status'] == "OBSTACLE" and left_sector['status'] != "OBSTACLE":
                suggested_steer_offset = -25.0

        return {
            'overall_status': overall_status,
            'recommended_action': recommended_action,
            'suggested_steer_offset': suggested_steer_offset,
            'pitch_deg': self.pitch_deg,
            'height_m': self.height_m,
            'drop_threshold_m': self.drop_threshold_m,
            'elevation_profile': elevation_profile,
            'sectors': {
                'left': left_sector,
                'center': center_sector,
                'right': right_sector
            },
            'processed_points': processed_points
        }

    def _evaluate_sector(self, name, sector_pts):
        if not sector_pts:
            return {
                'name': name,
                'status': 'UNKNOWN',
                'drop_ratio': 0.0,
                'obstacle_ratio': 0.0,
                'sidewalk_ratio': 0.0,
                'count': 0
            }

        total = len(sector_pts)
        drop_count = sum(1 for p in sector_pts if p['type'] == 'ROAD_DROP')
        obstacle_count = sum(1 for p in sector_pts if p['type'] == 'OBSTACLE')
        sidewalk_count = sum(1 for p in sector_pts if p['type'] == 'SIDEWALK')

        drop_ratio = drop_count / float(total)
        obstacle_ratio = obstacle_count / float(total)
        sidewalk_ratio = sidewalk_count / float(total)

        if drop_ratio > 0.25:
            status = "ROADWAY_DROP"
        elif obstacle_ratio > 0.25:
            status = "OBSTACLE"
        else:
            status = "SIDEWALK_SAFE"

        return {
            'name': name,
            'status': status,
            'drop_ratio': round(drop_ratio, 2),
            'obstacle_ratio': round(obstacle_ratio, 2),
            'sidewalk_ratio': round(sidewalk_ratio, 2),
            'count': total
        }

    def _empty_result(self):
        return {
            'overall_status': 'NO_DATA',
            'recommended_action': 'STOP',
            'suggested_steer_offset': 0.0,
            'pitch_deg': self.pitch_deg,
            'height_m': self.height_m,
            'drop_threshold_m': self.drop_threshold_m,
            'elevation_profile': [],
            'sectors': {
                'left': {'status': 'NO_DATA', 'drop_ratio': 0},
                'center': {'status': 'NO_DATA', 'drop_ratio': 0},
                'right': {'status': 'NO_DATA', 'drop_ratio': 0}
            },
            'processed_points': []
        }
