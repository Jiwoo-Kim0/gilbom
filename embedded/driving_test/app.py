#!/usr/bin/env python3
"""
AutoDrive Autonomous Driving Web Dashboard & Control Server
Integrates Horizontal LiDAR Avoidance, Camera YOLO Sidewalk Segmentation, 
and Automated Braille Block Detection & Snapshot Captures.
Serves Web Interface at http://0.0.0.0:5000
"""

import os
import atexit
import logging
import cv2
from flask import Flask, render_template, jsonify, request, Response, send_from_directory
import config
from autonomous_driver import AutonomousDriver

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)

# Initialize Sensor-Fusion Autonomous Driver System
auto_sys = AutonomousDriver(getattr(config, 'HARDWARE_MODE', 'PCA9685'))
auto_sys.start()


@app.errorhandler(Exception)
def handle_exception(e):
    if "404" in str(e):
        return jsonify({'success': False, 'message': 'Not Found'}), 404
    logging.error(f"Unhandled Exception: {e}", exc_info=True)
    return jsonify({
        'success': False,
        'message': str(e)
    }), 500


@app.route('/')
def index():
    return render_template('index.html')


@app.route('/favicon.ico')
def favicon():
    return ('', 204)


@app.route('/video_feed')
def video_feed():
    """MJPEG Direct Video Stream Endpoint for Live Camera Feed with YOLO Overlays."""
    def generate_mjpeg_frames():
        while True:
            frame = auto_sys.vision_processor.get_latest_annotated_frame()
            if frame is None:
                import time
                time.sleep(0.04)
                continue

            ret, buffer = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 75])
            if not ret:
                continue

            frame_bytes = buffer.tobytes()
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')
            import time
            time.sleep(0.03)

    return Response(generate_mjpeg_frames(), mimetype="multipart/x-mixed-replace; boundary=frame")


@app.route('/captures/<filename>')
def serve_capture_file(filename):
    """Serves captured Braille block images to the web dashboard."""
    capture_dir = getattr(config, 'BRAILLE_CAPTURE_DIR', '/home/ubuntu/SSAFY/captures')
    return send_from_directory(capture_dir, filename)


@app.route('/api/captures', methods=['GET'])
def get_captures():
    """Returns list of automated Braille block snapshots."""
    logs = auto_sys.vision_processor.get_capture_logs()
    return jsonify({
        'success': True,
        'captures': logs
    })


@app.route('/api/telemetry', methods=['GET'])
def get_telemetry():
    """Returns live telemetry, motor state, LiDAR point cloud, Vision steering & Braille detections."""
    status = auto_sys.get_status()
    scan_pts = auto_sys.lidar_driver.get_latest_scan()

    formatted_points = [
        {
            'angle': round(p.angle_deg, 1),
            'dist': round(p.distance_m, 3)
        }
        for p in scan_pts
    ]

    return jsonify({
        'success': True,
        'is_auto': status['is_auto'],
        'hardware_mode': status['hardware_mode'],
        'lidar_mock_mode': auto_sys.lidar_driver.mock_mode,
        'direction': status['direction'],
        'speed': status['speed'],
        'angle': status['angle'],
        'last_action': status['last_action'],
        'lidar': status['lidar'],
        'vision': status['vision'],
        'captures': status['captures'],
        'scan_points': formatted_points
    })


@app.route('/api/control', methods=['POST'])
def control():
    """Manual motor & steering control endpoint."""
    try:
        data = request.get_json() or {}
        action = data.get('action', '').upper()
        speed = data.get('speed')
        angle = data.get('angle')
        step = data.get('step', 15)

        driver = auto_sys.motor_driver

        if speed is not None:
            try:
                driver.set_speed(int(speed))
            except ValueError:
                pass

        if angle is not None and action not in ['LEFT', 'RIGHT']:
            try:
                driver.set_angle(int(angle))
            except ValueError:
                pass

        if action == 'FORWARD':
            driver.forward()
        elif action == 'BACKWARD':
            driver.backward()
        elif action == 'LEFT':
            driver.steer_left(int(step))
        elif action == 'RIGHT':
            driver.steer_right(int(step))
        elif action == 'CENTER':
            driver.center()
        elif action == 'SET_ANGLE':
            if angle is not None:
                driver.set_angle(int(angle))
        elif action == 'STOP':
            driver.stop()
        else:
            return jsonify({'success': False, 'message': f'Invalid action: {action}'}), 400

        return jsonify({
            'success': True,
            'action': action,
            'direction': driver.current_direction,
            'speed': driver.current_speed,
            'angle': driver.current_angle
        })
    except Exception as e:
        logging.error(f"Error in control endpoint: {e}")
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/auto', methods=['POST'])
def toggle_auto():
    """Toggles Autonomous Sidewalk Driving Mode."""
    try:
        data = request.get_json() or {}
        enabled = bool(data.get('enabled', False))
        auto_sys.set_auto_mode(enabled)
        return jsonify({'success': True, 'is_auto': auto_sys.is_auto})
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/lidar_source', methods=['POST'])
def toggle_lidar_source():
    """Toggles between Physical LiDAR (/dev/ttyUSB0) and Mock Simulator."""
    try:
        data = request.get_json() or {}
        use_mock = bool(data.get('mock_mode', False))
        auto_sys.lidar_driver.set_mode(use_mock)
        return jsonify({'success': True, 'mock_mode': auto_sys.lidar_driver.mock_mode})
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/mode', methods=['POST'])
def change_hardware_mode():
    """Switches hardware motor driver mode (PCA9685, GPIO, HYBRID, MOCK)."""
    try:
        data = request.get_json() or {}
        new_mode = data.get('mode', '').upper()
        if new_mode not in ['GPIO', 'PCA9685', 'HYBRID', 'MOCK']:
            return jsonify({'success': False, 'message': 'Invalid mode'}), 400

        auto_sys.update_hardware_mode(new_mode)
        return jsonify({'success': True, 'mode': new_mode})
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


def cleanup_on_exit():
    if auto_sys:
        auto_sys.stop()

atexit.register(cleanup_on_exit)

if __name__ == '__main__':
    print("\n" + "="*65)
    print(" 🚗 AutoDrive Sidewalk Robot System (LiDAR Avoidance + Vision Seg)")
    print(" 📸 Braille Block Capture Model: /home/ubuntu/SSAFY/models/best.pt")
    print(" 🌐 Access Web Dashboard: http://localhost:5000 or http://<Orin-IP>:5000")
    print("="*65 + "\n")
    app.run(host='0.0.0.0', port=5000, debug=False)
