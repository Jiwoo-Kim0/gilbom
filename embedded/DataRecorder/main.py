import os
import sys
import logging
from contextlib import asynccontextmanager

# DrivingModule provides motor_driver and the shared hardware config.
# It must precede this dir's siblings for `import config` to resolve there.
sys.path.insert(0, "/home/ubuntu/SSAFY/DrivingModule")

from fastapi import FastAPI
from fastapi.responses import StreamingResponse, JSONResponse, FileResponse

import config as drive_config
import recorder_config as rc
from camera import CameraWorker
from bot import BotController
from recorder import RecorderManager

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s - %(levelname)s - %(message)s")

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

cameras = {
    "nav": CameraWorker("nav", drive_config.NAV_CAMERA_INDEX,
                        drive_config.CAMERA_WIDTH, drive_config.CAMERA_HEIGHT,
                        drive_config.CAMERA_FPS),
    "front": CameraWorker("front", drive_config.FRONT_CAMERA_INDEX,
                          drive_config.FRONT_CAMERA_WIDTH, drive_config.FRONT_CAMERA_HEIGHT,
                          drive_config.FRONT_CAMERA_FPS),
}

bot = BotController()
recorder = RecorderManager(cameras, bot)
bot.on_record_toggle = recorder.toggle


@asynccontextmanager
async def lifespan(app: FastAPI):
    for cam in cameras.values():
        cam.start()
    bot.start()
    yield
    if recorder.active:
        recorder.stop()
    bot.stop()
    for cam in cameras.values():
        cam.stop()


app = FastAPI(title="SSAFY Bot DataRecorder", lifespan=lifespan)


@app.get("/")
async def index():
    return FileResponse(os.path.join(BASE_DIR, "templates", "index.html"))


@app.get("/stream/{cam_name}")
async def stream(cam_name: str):
    cam = cameras.get(cam_name)
    if cam is None:
        return JSONResponse({"error": f"unknown camera '{cam_name}'"}, status_code=404)
    return StreamingResponse(cam.mjpeg_generator(),
                             media_type="multipart/x-mixed-replace; boundary=frame")


@app.post("/api/record/start")
async def record_start():
    return recorder.start()


@app.post("/api/record/stop")
async def record_stop():
    return recorder.stop()


@app.get("/api/status")
async def status():
    return {
        "bot": bot.status(),
        "recorder": recorder.status(),
        "cameras": {name: {"alive": cam.alive_frame, "captured": cam.capture_count}
                    for name, cam in cameras.items()},
    }


if __name__ == "__main__":
    import uvicorn
    # Pass the app object, not "main:app" - the string form re-imports this
    # module and initializes the motor driver and cameras a second time.
    uvicorn.run(app, host=rc.HOST, port=rc.PORT)
