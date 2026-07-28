"""
DrivingModule 최소 통합 예제
============================
auto_navigator + motor_driver + config 를 카메라/YOLO 루프에 연결하는 뼈대.
원본 프로젝트의 전체 연동(FastAPI 대시보드, MJPEG 스트림, 듀얼 카메라)은
~/SSAFY/segTest/main.py 참고.

계약 요약:
  1. 매 프레임마다 process_vision_navigation() 호출  -> 프레임 워치독을 살린다
  2. 운영자 링크에서 주기적으로 notify_heartbeat()   -> 데드맨 스위치
  3. 둘 다 살아있어야 set_auto_mode(True)가 성공한다
  4. 종료 시 shutdown() -> stop() -> cleanup()
"""

import time
import threading
import cv2
from ultralytics import YOLO

import config
from auto_navigator import AutoNavigator


def heartbeat_loop(nav: AutoNavigator, stop_event: threading.Event):
    """실전에서는 대시보드/조종기가 POST /api/heartbeat 로 보내는 신호.
    이 데모에서는 로컬 스레드가 대신 보낸다 (= 항상 누군가 지켜보고 있다고 가정)."""
    while not stop_event.is_set():
        nav.notify_heartbeat()
        time.sleep(0.5)  # WATCHDOG_LINK_TIMEOUT_SEC(2.5s)보다 충분히 짧게


def main():
    model = YOLO(config.YOLO_MODEL_PATH)
    nav = AutoNavigator()  # config.HARDWARE_MODE에 따라 모터 드라이버 자동 선택(실패 시 MOCK)

    # (선택) free-space 인지 모듈이 생기면 여기서 플러그인:
    # nav.set_evidence_provider(my_free_space_module)
    # config.REQUIRE_DRIVABLE_EVIDENCE = True  # 실외 주행 전 필수

    cap = cv2.VideoCapture(config.NAV_CAMERA_INDEX)
    cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))  # USB 허브 공유 시 필수
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, config.CAMERA_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, config.CAMERA_HEIGHT)

    stop_event = threading.Event()
    hb = threading.Thread(target=heartbeat_loop, args=(nav, stop_event), daemon=True)
    hb.start()

    try:
        # 무장은 하트비트 + 프레임이 모두 신선해야 성공하므로, 첫 프레임을 먼저 돌린다
        armed = False
        while True:
            ret, frame = cap.read()
            if not ret:
                time.sleep(0.01)
                continue

            results = model.predict(frame, conf=config.CONF_THRESHOLD,
                                    iou=config.IOU_THRESHOLD, verbose=False)

            # 인지 -> 상태머신 -> (auto 모드면) 모터 구동 -> HUD/텔레메트리 반환
            hud_frame, telemetry = nav.process_vision_navigation(frame, results)

            if not armed:
                res = nav.set_auto_mode(True)
                armed = res.get("success", False)
                if not armed:
                    print(f"arm refused: {res.get('detail')}")

            print(f"[{telemetry['nav_state']}] {telemetry['action_status']}")

            # cv2.imshow("nav", hud_frame); cv2.waitKey(1)  # 모니터 있으면 HUD 확인

    except KeyboardInterrupt:
        pass
    finally:
        stop_event.set()
        nav.set_auto_mode(False)
        nav.shutdown()
        nav.motor_driver.stop()
        nav.motor_driver.cleanup()
        cap.release()


if __name__ == "__main__":
    main()
