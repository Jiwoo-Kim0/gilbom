"""
Camera Vision Module.
Handles Camera Capture, Dual YOLO Inferences (Braille Block Detection & Sidewalk Segmentation),
Automated Braille Block Snapshot Capturing, and Steering Path Calculation.
"""

import os
import sys
import time
import ctypes
import datetime
import logging
import threading
import cv2
import numpy as np
import config

# Pre-load NVIDIA Jetson CUDA cusparseLt library if present
cusparselt_so = os.path.expanduser("~/.local/lib/python3.10/site-packages/nvidia/cusparselt/lib/libcusparseLt.so.0")
if os.path.exists(cusparselt_so):
    try:
        ctypes.CDLL(cusparselt_so)
    except Exception:
        pass

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')


class CameraVisionProcessor:
    def __init__(self):
        self.camera_index = getattr(config, 'CAMERA_INDEX', 0)
        self.cap = None
        self.running = False
        self.lock = threading.Lock()
        
        self.current_raw_frame = None
        self.current_annotated_frame = None
        self.thread = None

        # AI Models
        self.braille_model = None
        self.braille_classes = {}
        self.seg_model = None
        
        # Braille Block Auto Capture Control
        self.capture_dir = getattr(config, 'BRAILLE_CAPTURE_DIR', '/home/ubuntu/SSAFY/captures')
        os.makedirs(self.capture_dir, exist_ok=True)
        self.last_capture_time = 0.0
        self.cooldown_sec = getattr(config, 'BRAILLE_CAPTURE_COOLDOWN_SEC', 2.5)
        self.capture_logs = []  # History of captures

        # Sidewalk Steering Output Cache
        self.vision_steering = {
            'sidewalk_detected': False,
            'center_offset': 0.0,       # Normalized offset (-1.0 = far left, +1.0 = far right)
            'suggested_steer_angle': 100, # Center steering angle
            'detected_braille_blocks': []
        }

        self._load_models()

    def _load_models(self):
        try:
            from ultralytics import YOLO
            import torch
            device = 'cuda' if torch.cuda.is_available() else 'cpu'

            # 1. Load Braille Block Detection Model (best.pt)
            braille_path = getattr(config, 'BRAILLE_MODEL_PATH', '/home/ubuntu/SSAFY/models/best.pt')
            if os.path.exists(braille_path):
                logging.info(f"[Vision] Loading Braille YOLO model from {braille_path} on {device}...")
                self.braille_model = YOLO(braille_path)
                self.braille_model.to(device)
                self.braille_classes = self.braille_model.names if hasattr(self.braille_model, 'names') else {}
                logging.info(f"[Vision] Braille Model loaded successfully! Classes: {self.braille_classes}")
            else:
                logging.warning(f"[Vision] Braille model not found at {braille_path}")

            # 2. Load Sidewalk Segmentation Model (yolo11n-seg.pt)
            seg_path = getattr(config, 'SIDEWALK_SEG_MODEL_PATH', 'yolo11n-seg.pt')
            logging.info(f"[Vision] Loading Sidewalk Segmentation model ({seg_path}) on {device}...")
            self.seg_model = YOLO(seg_path)
            self.seg_model.to(device)
            logging.info("[Vision] Segmentation Model loaded successfully!")

            # Warmup
            dummy = np.zeros((480, 640, 3), dtype=np.uint8)
            if self.braille_model:
                self.braille_model(dummy, verbose=False)
            if self.seg_model:
                self.seg_model(dummy, verbose=False)
            logging.info("[Vision] Models warmed up on GPU.")

        except Exception as e:
            logging.error(f"[Vision Model Load Error] {e}")

    def start(self):
        self.running = True
        self.cap = cv2.VideoCapture(self.camera_index)
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, getattr(config, 'CAMERA_WIDTH', 640))
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, getattr(config, 'CAMERA_HEIGHT', 480))

        if not self.cap.isOpened():
            logging.error(f"[Vision] Failed to open camera device /dev/video{self.camera_index}")

        self.thread = threading.Thread(target=self._process_loop, daemon=True)
        self.thread.start()
        logging.info("[Vision] Camera capture & AI processing thread started.")

    def _process_loop(self):
        center_angle = getattr(config, 'STEERING_CENTER', 100)
        steer_gain = getattr(config, 'STEER_GAIN', 1.5)

        while self.running:
            try:
                if self.cap and self.cap.isOpened():
                    ret, frame = self.cap.read()
                    if not ret or frame is None:
                        time.sleep(0.01)
                        continue
                else:
                    # Provide fallback blank frame if no hardware camera connected
                    frame = np.zeros((480, 640, 3), dtype=np.uint8)
                    cv2.putText(frame, "NO CAMERA FEED", (180, 240), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 255), 2)
                    time.sleep(0.05)

                annotated = frame.copy()
                h, w, _ = frame.shape
                frame_center_x = w / 2.0

                # =========================================================
                # Step 1: Sidewalk Segmentation & Path Steering Calculation
                # =========================================================
                sidewalk_detected = False
                center_offset = 0.0
                target_steer_angle = center_angle

                if self.seg_model is not None:
                    seg_results = self.seg_model(frame, conf=0.25, verbose=False)
                    if seg_results and len(seg_results) > 0:
                        res = seg_results[0]
                        # Draw segmentation masks if available
                        if res.masks is not None:
                            masks = res.masks.data.cpu().numpy()
                            combined_mask = np.zeros((h, w), dtype=np.uint8)

                            for i, mask in enumerate(masks):
                                cls_id = int(res.boxes.cls[i].item())
                                # Standard COCO or sidewalk classes
                                resized_mask = cv2.resize(mask, (w, h))
                                combined_mask = np.maximum(combined_mask, (resized_mask > 0.5).astype(np.uint8))

                            # Create overlay
                            color_mask = np.zeros_like(frame)
                            color_mask[:, :, 1] = combined_mask * 180  # Green overlay for ground/walkable region
                            annotated = cv2.addWeighted(annotated, 1.0, color_mask, 0.4, 0)

                            # Calculate Centroid of lower half ROI
                            roi_mask = combined_mask[int(h * 0.4):, :]
                            M = cv2.moments(roi_mask)
                            if M["m00"] > 0:
                                cX = int(M["m10"] / M["m00"])
                                cY = int(M["m01"] / M["m00"]) + int(h * 0.4)
                                
                                sidewalk_detected = True
                                center_offset = (cX - frame_center_x) / (w / 2.0)  # -1.0 to 1.0
                                
                                # Steering adjustment: offset * gain * 30 degrees
                                steer_delta = center_offset * steer_gain * 35.0
                                target_steer_angle = center_angle + steer_delta
                                target_steer_angle = max(config.STEERING_MAX_LEFT, min(config.STEERING_MAX_RIGHT, target_steer_angle))

                                cv2.circle(annotated, (cX, cY), 8, (0, 255, 255), -1)
                                cv2.line(annotated, (int(frame_center_x), h), (cX, cY), (0, 255, 255), 2)
                        
                        else:
                            # Fallback segmenter heuristic: lower road region
                            gray = cv2.cvtColor(frame[int(h*0.5):, :], cv2.COLOR_BGR2GRAY)
                            blur = cv2.GaussianBlur(gray, (5, 5), 0)
                            edges = cv2.Canny(blur, 50, 150)
                            lines = cv2.HoughLinesP(edges, 1, np.pi/180, threshold=30, minLineLength=40, maxLineGap=20)
                            if lines is not None:
                                sidewalk_detected = True

                # =========================================================
                # Step 2: Braille Block Detection & Auto Image Capture
                # =========================================================
                detected_blocks = []
                if self.braille_model is not None:
                    braille_results = self.braille_model(frame, conf=getattr(config, 'BRAILLE_CONF_THRES', 0.35), verbose=False)
                    if braille_results and len(braille_results) > 0:
                        b_res = braille_results[0]
                        if b_res.boxes is not None and len(b_res.boxes) > 0:
                            now = time.time()
                            should_capture = (now - self.last_capture_time) >= self.cooldown_sec

                            for box in b_res.boxes:
                                cls_id = int(box.cls[0].item())
                                cls_name = self.braille_classes.get(cls_id, f"block_{cls_id}")
                                conf = float(box.conf[0].item())
                                x1, y1, x2, y2 = map(int, box.xyxy[0].tolist())

                                detected_blocks.append({
                                    'class_id': cls_id,
                                    'class_name': cls_name,
                                    'confidence': round(conf, 3),
                                    'bbox': [x1, y1, x2, y2]
                                })

                                # Draw bounding box on annotated frame
                                box_color = (0, 215, 255) if 'normal' in cls_name else (0, 0, 255)
                                cv2.rectangle(annotated, (x1, y1), (x2, y2), box_color, 3)
                                label_text = f"TACTILE: {cls_name} ({conf*100:.1f}%)"
                                cv2.putText(annotated, label_text, (x1, max(y1 - 10, 20)),
                                            cv2.FONT_HERSHEY_SIMPLEX, 0.6, box_color, 2)

                            # Perform automated snapshot capture if cooldown passed
                            if should_capture and len(detected_blocks) > 0:
                                self.last_capture_time = now
                                timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
                                top_class = detected_blocks[0]['class_name']
                                filename = f"braille_{timestamp_str}_{top_class}.jpg"
                                filepath = os.path.join(self.capture_dir, filename)

                                # Save snapshot image with metadata overlay
                                capture_img = annotated.copy()
                                timestamp_label = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                                cv2.putText(capture_img, f"Captured: {timestamp_label}", (20, 30),
                                            cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)
                                cv2.imwrite(filepath, capture_img)

                                capture_info = {
                                    'filename': filename,
                                    'filepath': filepath,
                                    'timestamp': timestamp_label,
                                    'blocks_found': len(detected_blocks),
                                    'top_class': top_class
                                }
                                self.capture_logs.insert(0, capture_info)
                                if len(self.capture_logs) > 50:
                                    self.capture_logs.pop()
                                logging.info(f"[Vision] 📸 AUTOMATIC CAPTURE SAVED: {filepath}")

                # Draw Overlay Info Bar
                cv2.putText(annotated, f"Steer: {int(target_steer_angle)} deg | Braille: {len(detected_blocks)}", 
                            (15, h - 20), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)

                with self.lock:
                    self.current_raw_frame = frame
                    self.current_annotated_frame = annotated
                    self.vision_steering = {
                        'sidewalk_detected': sidewalk_detected,
                        'center_offset': round(center_offset, 3),
                        'suggested_steer_angle': int(target_steer_angle),
                        'detected_braille_blocks': detected_blocks
                    }

                time.sleep(0.03)  # ~30 FPS loop

            except Exception as e:
                logging.error(f"[Vision Process Loop Error] {e}")
                time.sleep(0.1)

    def get_latest_annotated_frame(self):
        with self.lock:
            if self.current_annotated_frame is not None:
                return self.current_annotated_frame.copy()
            return None

    def get_vision_steering(self):
        with self.lock:
            return dict(self.vision_steering)

    def get_capture_logs(self):
        with self.lock:
            return list(self.capture_logs)

    def stop(self):
        self.running = False
        if self.cap is not None:
            self.cap.release()
        logging.info("[Vision] Camera Vision Processor stopped.")
