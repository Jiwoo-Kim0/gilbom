"""OrinCar 진입점 — 메인 루프 + 상태머신.

역할: 세 축(drive / detect / comms)과 sensors를 '조립'만 한다.
여기에 하드웨어 접근 코드나 판정 로직을 직접 쓰지 말 것.
  - mode·reason_code 짝과 health 판정 → robot_state.RobotState
  - state 발행 타이밍(§5.1)           → robot_state.StatePublisher
  - telemetry 표본·발행 타이밍(§6)    → telemetry.TelemetrySampler
  - event 4종 판정·발행(§8~§11)       → events.EventPublisher
  - 명령 검증·실행·결과(§12·§13)      → commands.CommandRunner
  - 장치별 health 판정                → 각 하드웨어 모듈의 health()
  - 라이다·모터·카메라·추론 판정      → drive/·detect/ 각 모듈
요구 추적: F-001(초기화) F-007(시스템 관리) + MQTT 계약 tbs/v2 §4~§13

⚠️ 이 프로세스가 시리얼·카메라·I2C 를 소유한다. driving_test 앱과 동시에 띄우면
   /dev/ttyUSB0 · /dev/video* · I2C 를 서로 뺏는다 — 둘 중 하나만 실행할 것.
"""

from __future__ import annotations

import logging
import queue
import signal
import threading
from pathlib import Path

from commands import CommandRunner
from comms import MqttClient, MqttConfig
from comms.config import load_document
from events import OBSTACLE_CONFIRM_SEC, EventPublisher
from robot_state import BatteryThresholds, RobotState, StatePublisher
from telemetry import TelemetrySampler

log = logging.getLogger("orincar")

CONFIG_PATH = Path(__file__).resolve().parent / "config.yaml"
#: 메인 루프 tick. 전이를 발행까지 끌고 가는 지연의 상한이자 30초 타이머의 분해능.
LOOP_INTERVAL_SEC = 0.5
#: 기동 시 접속을 기다려 주는 시간. 못 붙어도 계속 간다 — 브로커가 죽어 있어도
#: 로봇은 떠야 하고, presence·state 는 재접속 훅이 다시 보낸다.
CONNECT_WAIT_SEC = 5.0
#: 종료 직전 마지막 state 가 소켓에 실리기를 기다리는 시간.
SHUTDOWN_PUBLISH_WAIT_SEC = 2.0


def health_probes() -> dict:
    """하드웨어 모듈의 health() 를 계약 멤버 이름에 묶는다.

    판정은 각 모듈이 한다 — 여기서는 이름만 연결한다.
    storage 는 하드웨어 모듈이 없어서 RobotState 가 직접 판정한다 (계약 §5.3).
    """
    from detect import camera
    from drive import motor, safety
    from sensors import battery, gps

    return {
        "camera": camera.health,
        "lidar": safety.health,
        "gps": gps.health,
        "battery": battery.health,
        "motor": motor.health,
    }


def sensor_reads() -> dict:
    """telemetry 표본의 재료가 되는 읽기 함수를 계약 이름에 묶는다.

    값 판정은 각 모듈과 TelemetrySampler 가 한다 — 여기서는 이름만 연결한다.
    battery 는 RobotState 를 거쳐 state·telemetry 가 같은 값을 보게 한다.
    """
    from drive import motor
    from sensors import battery, gps

    return {
        "gps": gps.read_position,
        "battery": battery.read,
        "motor": motor.is_driving,
    }


def event_sources() -> dict:
    """event 판정의 재료를 이름에 묶는다.

    2단계 확정·등급 전이 판정은 EventPublisher 안에 있다 — 여기서는 연결만 한다.
    """
    from detect import inference
    from drive import safety
    from sensors import gps

    return {
        "lidar": safety.check,
        "gps": gps.read_position,
        "model_name": inference.MODEL_NAME,
        "model_version": inference.MODEL_VERSION,
    }


def configure_hardware(document: dict) -> None:
    """설정만 각 하드웨어 모듈에 넘긴다. 장치는 아직 열지 않는다.

    여는 것은 run() 이다 — build() 만 부르는 테스트가 카메라·시리얼을 잡으면 안 된다.
    """
    from detect import camera, inference
    from drive import motor, safety

    safety.configure(document.get("safety"))
    motor.configure(document.get("drive"))
    camera.configure(document.get("detect"))
    inference.configure(document.get("detect"))


def start_hardware() -> None:
    """시리얼·I2C·카메라·가중치를 연다. 하나가 실패해도 나머지는 계속 뜬다.

    실패는 예외가 아니라 health() 의 FAULT 로 보고된다 (계약 §5.3).
    """
    from detect import camera, inference
    from drive import motor, safety

    for module in (safety, motor, camera, inference):
        try:
            module.start()
        except Exception:
            log.exception("%s failed to start", module.__name__)


def stop_motion() -> None:
    """바퀴만 세운다. 종료 경로에서 가장 먼저 하는 일이다 (§16-10)."""
    from drive import motor

    try:
        motor.stop()
    except Exception:
        log.warning("failed to stop the wheels", exc_info=True)


def release_devices() -> None:
    """시리얼·카메라·I2C 를 놓아준다. 기동 전이면 아무 일도 하지 않는다.

    마지막 state 를 발행한 뒤에 부른다 — 먼저 닫으면 health 가 '검사 전(UNKNOWN)' 으로
    되돌아가서, 종료 직전에 무엇이 고장이었는지가 기록에서 지워진다.
    """
    from detect import camera
    from drive import motor, safety

    for stop in (motor.stop, safety.stop, camera.stop):
        try:
            stop()
        except Exception:
            log.warning("hardware shutdown failed", exc_info=True)


def detect_loop(detections: "queue.Queue", stopping) -> None:
    """카메라 → 추론 → 결과 큐. 별도 스레드에서 돈다.

    추론을 0.5초 tick 에 태우면 YOLO 한 장이 길어질 때 EMERGENCY_STOP 처리와
    30초 state 주기가 같이 밀린다. 확정·중복 제거 판정은 detect 안에 있다.
    """
    from detect import camera, inference

    interval = inference.interval_sec()
    while not stopping.wait(interval):
        try:
            frame = camera.read()
            detection = inference.infer(frame)
            if detection is None:
                continue
            if not detection.get("normal"):
                # 증거 저장은 파일 I/O 라 메인 루프가 아니라 여기서 한다.
                path, meta = camera.capture_evidence(frame)
                detection = dict(detection, image_path=path, **meta)
            detections.put(detection)
        except Exception:
            log.exception("detection loop failed")


def drain_detections(events: EventPublisher, state: RobotState, detections) -> None:
    """확정된 관측을 계약 경로로 넘긴다. 판정은 하지 않는다 — 배분만 한다."""
    while True:
        try:
            detection = detections.get_nowait()
        except queue.Empty:
            return
        try:
            if detection.get("normal"):
                if state.mission_id is not None:
                    # 정상 관측은 event 가 아니다. 미션 집계로만 센다 (§11.3).
                    state.record_normal_detection()
                continue
            events.report_incident(
                detection,
                image_width=detection["image_width"],
                image_height=detection["image_height"],
                image_path=detection.get("image_path"),
                captured_at=detection["captured_at"],
            )
        except Exception:
            log.exception("failed to report a detection")


def build(config_path=CONFIG_PATH):
    """설정 로드 → 모듈 조립. 아직 아무 것도 발행하지 않는다.  [F-001]"""
    document = load_document(config_path)
    configure_hardware(document)
    # ca_cert 같은 상대경로는 config.yaml 이 있는 디렉터리 기준으로 푼다 — systemd 가
    # WorkingDirectory 없이 띄워도 CA 를 찾는다.
    config = MqttConfig.from_document(
        document, base_dir=Path(config_path).resolve().parent
    )
    client = MqttClient(config)
    state = RobotState(
        health_probes=health_probes(),
        outbox=client.outbox,
        # Outbox 가 이미 만들어 둔 디렉터리다 (MqttClient 생성 시점).
        storage_dir=Path(config.outbox_path).parent,
        battery_thresholds=BatteryThresholds.from_document(document),
    )
    from drive import motor

    publisher = StatePublisher(client, state)
    sampler = TelemetrySampler(client, state, **sensor_reads())
    safety = document.get("safety") or {}
    # 물리 정지는 MQTT 발행보다 먼저다 (§16-10) — 그 호출을 두 경로에 물려 준다.
    events = EventPublisher(
        client,
        state,
        obstacle_confirm_sec=safety.get("obstacle_confirm_sec", OBSTACLE_CONFIRM_SEC),
        stop_motor=motor.stop,
        sensor_fault_stops_driving=safety.get("sensor_fault_stops_driving", True),
        # 증거 이미지 업로드 form 의 robot_id — DB 내부 UUID 다. mqtt_id 와 다른 값이라
        # config 에서 따로 받는다 (config.yaml robot.uuid).
        upload_robot_uuid=(document.get("robot") or {}).get("uuid"),
        **event_sources(),
    )
    commands = CommandRunner(client, state, events, stop_motor=motor.stop)
    # 연결 직후 1회 발행 (계약 §5.1 (a)). 재접속 때마다 다시 불린다.
    # paho 네트워크 스레드에서 불리므로 RobotState·StatePublisher 는 락으로 보호돼 있다.
    # telemetry 에는 이 훅이 없다 — 재접속했다고 과거 표본을 보내지 않는다 (§6.1).
    client.on_connected = publisher.on_connected
    return client, state, publisher, sampler, events, commands


def run(
    client,
    state: RobotState,
    publisher: StatePublisher,
    sampler: TelemetrySampler,
    events: EventPublisher,
    commands: CommandRunner,
    stopping,
) -> None:
    """기동 전이 → 메인 루프. stopping 이 서면 돌아온다."""
    client.start()
    client.wait_until_connected(timeout=CONNECT_WAIT_SEC)
    start_hardware()

    detections = queue.Queue()
    detector = threading.Thread(
        target=detect_loop, args=(detections, stopping), name="detect", daemon=True
    )
    detector.start()

    state.boot_completed()  # BOOTING → READY  [F-001 초기화 완료]
    publisher.poll()
    # READY 에서 멈춘다. PATROLLING 으로 넘기는 것은 START_MISSION 이다.

    while not stopping.wait(LOOP_INTERVAL_SEC):
        # 명령이 먼저다. 여기서 전이가 일어나면 같은 tick 의 state 가 그 결과를 싣는다.
        # 검증·실행은 commands 안에 있다 — 여기서 해석하지 않는다.
        commands.poll()
        # 전이가 있었으면 즉시(§5.1 (b)), 없어도 30초마다(§5.1 (c)) 발행한다.
        # 판정은 publisher 안에 있다 — 여기서 비교하지 않는다.
        publisher.poll()
        # telemetry 는 5초 주기 하나뿐이다 (§6.1). 30초·5초 둘 다 0.5초 tick 으로
        # 충분해서 스레드를 따로 두지 않는다.
        sampler.poll()
        # 장애물 재확인(§10.2)과 배터리 등급 전이(§9)를 본다. 발행 조건 판정은
        # events 안에 있다 — 여기서 비교하지 않는다.
        events.poll()
        # 추론 스레드가 확정한 관측만 꺼내 배분한다. 여기서 추론하지 않는다.
        drain_detections(events, state, detections)


def shutdown(client, state: RobotState, publisher: StatePublisher) -> None:
    """SHUTTING_DOWN 을 마지막 state 로 남기고 끊는다.  [F-007]

    presence OFFLINE(GRACEFUL_SHUTDOWN) 은 client.shutdown() 이 보낸다.
    바로 뒤에 disconnect 가 오므로 state 가 소켓에 실릴 때까지 기다린다 —
    안 기다리면 큐에 남은 채 끊겨서 마지막 상태가 SHUTTING_DOWN 이 아닌 직전 값으로 남는다.
    """
    # 바퀴가 먼저다. 장치를 닫는 것은 마지막 state 를 보낸 뒤다 — 먼저 닫으면
    # health 가 UNKNOWN 으로 되돌아가 종료 시점의 고장이 기록에서 사라진다.
    stop_motion()
    try:
        state.begin_shutdown()
        publisher.publish(force=True, wait_sec=SHUTDOWN_PUBLISH_WAIT_SEC)
    except Exception:  # 종료 경로에서 예외로 프로세스를 막지 않는다.
        log.warning("failed to publish SHUTTING_DOWN state", exc_info=True)
    release_devices()
    client.shutdown()


def main() -> int:
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    client, state, publisher, sampler, events, commands = build()
    stopping = threading.Event()

    def _request_stop(signum, _frame):
        # systemd stop 은 SIGTERM 을 보낸다. 핸들러에서는 깃발만 세우고
        # 실제 종료 발행은 메인 스레드가 한다.
        log.info("signal %s received, shutting down", signum)
        stopping.set()

    signal.signal(signal.SIGTERM, _request_stop)

    try:
        run(client, state, publisher, sampler, events, commands, stopping)
    except KeyboardInterrupt:
        log.info("interrupted, shutting down")
    finally:
        shutdown(client, state, publisher)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


# 이후 티켓에서 이 조립에 더할 것 (지금은 범위 밖)
#   주행·추론 — drive 스텝 + safety.check() / detect 프레임 추론  [F-002~F-004]
#
# 배포는 scripts/ 에 있다  [F-007]
#   orincar.service      systemd unit — Restart=always, After=/Wants=time-sync.target
#                        (expires_at 판정이 NTP 동기를 전제한다, 계약 §17)
#   orincar.env.example  실기 주입값 템플릿 — 로봇 등록·Credential 발급 응답에서 채운다
#   README.md            주입·기동·장애 분류 절차
