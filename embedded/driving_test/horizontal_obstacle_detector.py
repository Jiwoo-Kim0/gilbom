"""
Horizontal LiDAR Obstacle Avoidance Detector.
Analyzes 2D planar LiDAR point cloud to detect front, left, and right obstacles
and computes safety status and avoidance steering offsets.
"""

import math
import logging
import config

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')


class HorizontalObstacleDetector:
    def __init__(self):
        self.stop_dist = getattr(config, 'OBSTACLE_STOP_DIST_M', 0.40)
        self.warn_dist = getattr(config, 'OBSTACLE_WARN_DIST_M', 0.75)

    def analyze_scan(self, scan_points):
        """
        Input: list of YDLiDARPoint objects (angle_deg: -180 to 180, distance_m: meters)
        Returns: Dict with status, min distances per sector, and suggested avoidance offset.
        """
        if not scan_points:
            return {
                'overall_status': 'NO_DATA',
                'front_min_dist': 99.0,
                'left_min_dist': 99.0,
                'right_min_dist': 99.0,
                'suggested_avoid_offset': 0.0,
                'is_emergency_stop': False
            }

        front_pts = []
        left_pts = []
        right_pts = []

        front_min_angle = getattr(config, 'FOV_FRONT_MIN', -30.0)
        front_max_angle = getattr(config, 'FOV_FRONT_MAX', 30.0)
        left_min_angle = getattr(config, 'FOV_LEFT_MIN', -75.0)
        left_max_angle = getattr(config, 'FOV_LEFT_MAX', -15.0)
        right_min_angle = getattr(config, 'FOV_RIGHT_MIN', 15.0)
        right_max_angle = getattr(config, 'FOV_RIGHT_MAX', 75.0)

        for pt in scan_points:
            d = pt.distance_m
            ang = pt.angle_deg

            # Filter valid ranges
            if d < getattr(config, 'MIN_SCAN_DIST_M', 0.10) or d > getattr(config, 'MAX_SCAN_DIST_M', 4.00):
                continue

            if front_min_angle <= ang <= front_max_angle:
                front_pts.append(d)
            if left_min_angle <= ang <= left_max_angle:
                left_pts.append(d)
            if right_min_angle <= ang <= right_max_angle:
                right_pts.append(d)

        front_min = min(front_pts) if front_pts else 99.0
        left_min = min(left_pts) if left_pts else 99.0
        right_min = min(right_pts) if right_pts else 99.0

        suggested_avoid_offset = 0.0
        is_emergency_stop = False
        overall_status = "CLEAR_PATH"

        if front_min <= self.stop_dist:
            overall_status = "EMERGENCY_STOP_FRONT"
            is_emergency_stop = True
            # Determine which side is more open to steer away during backup
            suggested_avoid_offset = -35.0 if left_min > right_min else 35.0

        elif front_min <= self.warn_dist:
            overall_status = "OBSTACLE_FRONT"
            # Steer towards clearer side
            if left_min > right_min:
                suggested_avoid_offset = -25.0  # Steer Left
            else:
                suggested_avoid_offset = 25.0   # Steer Right

        elif left_min <= (self.warn_dist * 0.7):
            overall_status = "OBSTACLE_LEFT"
            suggested_avoid_offset = 20.0  # Nudge Right to clear left obstacle

        elif right_min <= (self.warn_dist * 0.7):
            overall_status = "OBSTACLE_RIGHT"
            suggested_avoid_offset = -20.0 # Nudge Left to clear right obstacle

        return {
            'overall_status': overall_status,
            'front_min_dist': round(front_min, 3),
            'left_min_dist': round(left_min, 3),
            'right_min_dist': round(right_min, 3),
            'suggested_avoid_offset': suggested_avoid_offset,
            'is_emergency_stop': is_emergency_stop
        }
