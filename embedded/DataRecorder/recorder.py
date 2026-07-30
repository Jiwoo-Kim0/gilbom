import os
import csv
import time
import logging
import threading
from datetime import datetime

import recorder_config as rc


class RecorderManager:
    """
    Starts/stops a synchronized recording session across all cameras and logs
    the applied steering/throttle to control_log.csv at CONTROL_LOG_HZ, so each
    session is usable as an imitation-learning dataset (video + labels).
    """

    def __init__(self, cameras: dict, bot):
        self.cameras = cameras          # name -> CameraWorker
        self.bot = bot
        self._lock = threading.Lock()
        self._log_thread = None
        self._log_stop = None

        self.active = False
        self.session_dir = None
        self.session_name = None
        self.started_at = None
        self.last_session = None

    def start(self):
        with self._lock:
            if self.active:
                return {"ok": False, "error": "already recording"}

            name = datetime.now().strftime("%Y%m%d_%H%M%S")
            session_dir = os.path.join(rc.RECORDINGS_DIR, name)
            os.makedirs(session_dir, exist_ok=True)

            started = []
            for cam_name, cam in self.cameras.items():
                path = os.path.join(session_dir, f"{cam_name}.mp4")
                if cam.start_recording(path):
                    started.append(cam_name)

            if not started:
                return {"ok": False, "error": "no camera could start recording"}

            self.active = True
            self.session_dir = session_dir
            self.session_name = name
            self.started_at = time.monotonic()

            self._log_stop = threading.Event()
            self._log_thread = threading.Thread(
                target=self._control_log_loop,
                args=(os.path.join(session_dir, "control_log.csv"), self._log_stop),
                daemon=True, name="control-log")
            self._log_thread.start()

            logging.info(f"[REC] session started: {session_dir} (cams: {started})")
            return {"ok": True, "session": name, "cameras": started}

    def stop(self):
        with self._lock:
            if not self.active:
                return {"ok": False, "error": "not recording"}

            results = {name: cam.stop_recording() for name, cam in self.cameras.items()}
            self._log_stop.set()
            self._log_thread.join(timeout=2.0)

            duration = time.monotonic() - self.started_at
            self.last_session = {
                "session": self.session_name,
                "duration_sec": round(duration, 1),
                "files": results,
            }
            self.active = False
            self.session_dir = None
            self.session_name = None
            self.started_at = None

            logging.info(f"[REC] session stopped: {self.last_session}")
            return {"ok": True, **self.last_session}

    def toggle(self):
        return self.stop() if self.active else self.start()

    def _control_log_loop(self, path, stop_event):
        interval = 1.0 / rc.CONTROL_LOG_HZ
        with open(path, "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["t_wall", "t_rel",
                             *[f"{n}_frame" for n in self.cameras],
                             "angle", "speed", "direction"])
            t0 = time.monotonic()
            while not stop_event.is_set():
                s = self.bot.status()
                writer.writerow([
                    f"{time.time():.3f}", f"{time.monotonic() - t0:.3f}",
                    *[cam.recorded_frames for cam in self.cameras.values()],
                    s["angle"], s["speed"], s["direction"],
                ])
                stop_event.wait(interval)

    def status(self):
        st = {
            "recording": self.active,
            "session": self.session_name,
            "elapsed_sec": round(time.monotonic() - self.started_at, 1) if self.active else 0,
            "frames": {name: cam.recorded_frames for name, cam in self.cameras.items()}
                      if self.active else {},
            "last_session": self.last_session,
        }
        return st
