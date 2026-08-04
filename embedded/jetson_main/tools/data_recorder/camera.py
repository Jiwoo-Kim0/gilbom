import os
import time
import logging
import threading

import cv2

import recorder_config as rc


class CameraWorker(threading.Thread):
    """
    Owns one V4L2 camera. Continuously captures frames, keeps the latest JPEG
    for the preview stream, and - while a writer is attached - appends every
    captured frame to a video file.

    Both cameras share one USB 2.0 hub, so capture is forced to MJPG
    (see DrivingModule/config.py NAV_CAMERA_INDEX comment).
    """

    def __init__(self, name: str, index: int, width: int, height: int, fps: int):
        super().__init__(daemon=True, name=f"cam-{name}")
        self.cam_name = name
        self.index = index
        self.width = width
        self.height = height
        self.fps = fps

        self._cap = None
        self._stop = threading.Event()
        self._lock = threading.Lock()

        self.latest_jpeg = None
        self.last_frame_ts = 0.0
        self.capture_count = 0
        # EMA of the ACTUAL capture rate. Cameras deliver ~half the requested
        # fps here (USB hub is shared), so recording at the nominal rate would
        # play back fast - the writer uses this measurement instead.
        self.measured_fps = float(fps)

        self._writer = None
        self._writer_path = None
        self.recorded_frames = 0

    # ---- lifecycle -------------------------------------------------------

    def _open(self):
        cap = cv2.VideoCapture(self.index, cv2.CAP_V4L2)
        cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, self.width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, self.height)
        cap.set(cv2.CAP_PROP_FPS, self.fps)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        if not cap.isOpened():
            cap.release()
            return None
        return cap

    def run(self):
        while not self._stop.is_set():
            if self._cap is None:
                self._cap = self._open()
                if self._cap is None:
                    logging.warning(f"[{self.cam_name}] /dev/video{self.index} open failed, retrying in 2s")
                    self._stop.wait(2.0)
                    continue
                logging.info(f"[{self.cam_name}] opened /dev/video{self.index} "
                             f"{self.width}x{self.height}@{self.fps} MJPG")

            ok, frame = self._cap.read()
            if not ok:
                logging.warning(f"[{self.cam_name}] read failed, reopening")
                self._cap.release()
                self._cap = None
                continue

            self.capture_count += 1
            now = time.monotonic()
            if self.last_frame_ts > 0:
                dt = now - self.last_frame_ts
                if 0.001 < dt < 1.0:
                    self.measured_fps = 0.95 * self.measured_fps + 0.05 * (1.0 / dt)
            self.last_frame_ts = now

            ok, jpeg = cv2.imencode(".jpg", frame,
                                    [cv2.IMWRITE_JPEG_QUALITY, rc.PREVIEW_JPEG_QUALITY])
            if ok:
                self.latest_jpeg = jpeg.tobytes()

            with self._lock:
                if self._writer is not None:
                    self._writer.write(frame)
                    self.recorded_frames += 1

        with self._lock:
            self._close_writer_locked()
        if self._cap is not None:
            self._cap.release()

    def stop(self):
        self._stop.set()

    @property
    def alive_frame(self) -> bool:
        return (time.monotonic() - self.last_frame_ts) < 2.0

    # ---- recording -------------------------------------------------------

    def start_recording(self, path: str) -> bool:
        """Attach a video writer; every frame from now on is appended to `path`."""
        fps = max(1.0, round(self.measured_fps, 1))
        with self._lock:
            self._close_writer_locked()
            writer = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"),
                                     fps, (self.width, self.height))
            if not writer.isOpened():
                # mp4v unavailable in this OpenCV build -> fall back to MJPG/avi
                path = os.path.splitext(path)[0] + ".avi"
                writer = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"MJPG"),
                                         fps, (self.width, self.height))
            if not writer.isOpened():
                logging.error(f"[{self.cam_name}] VideoWriter open failed: {path}")
                return False
            self._writer = writer
            self._writer_path = path
            self.recorded_frames = 0
            logging.info(f"[{self.cam_name}] recording -> {path} @ {fps}fps (measured)")
            return True

    def stop_recording(self):
        with self._lock:
            frames, path = self.recorded_frames, self._writer_path
            self._close_writer_locked()
        return {"path": path, "frames": frames}

    def _close_writer_locked(self):
        if self._writer is not None:
            self._writer.release()
            logging.info(f"[{self.cam_name}] closed {self._writer_path} "
                         f"({self.recorded_frames} frames)")
            self._writer = None

    # ---- preview ---------------------------------------------------------

    def mjpeg_generator(self):
        interval = 1.0 / rc.PREVIEW_MAX_FPS
        while True:
            jpeg = self.latest_jpeg
            if jpeg is not None:
                yield (b"--frame\r\nContent-Type: image/jpeg\r\n"
                       b"Content-Length: " + str(len(jpeg)).encode() + b"\r\n\r\n"
                       + jpeg + b"\r\n")
            time.sleep(interval)
