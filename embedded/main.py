"""OrinCar 진입점 — 메인 루프 + 상태머신.

역할: 세 축(drive / detect / comms)과 sensors를 '조립'만 한다.
여기에 하드웨어 접근 코드나 판정 로직을 직접 쓰지 말 것.
  - mode·reason_code 짝과 health 판정 → robot_state.RobotState
  - state 발행 타이밍(§5.1)           → robot_state.StatePublisher
  - 장치별 health 판정                → 각 하드웨어 모듈의 health()
요구 추적: F-001(초기화) F-007(시스템 관리) + MQTT 계약 tbs/v2 §4·§5
"""

from __future__ import annotations

import logging
import signal
import threading
from pathlib import Path

from comms import MqttClient, MqttConfig
from robot_state import RobotState, StatePublisher

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


def build(config_path=CONFIG_PATH):
    """설정 로드 → 모듈 조립. 아직 아무 것도 발행하지 않는다.  [F-001]"""
    config = MqttConfig.load(config_path)
    client = MqttClient(config)
    state = RobotState(
        health_probes=health_probes(),
        outbox=client.outbox,
        # Outbox 가 이미 만들어 둔 디렉터리다 (MqttClient 생성 시점).
        storage_dir=Path(config.outbox_path).parent,
    )
    publisher = StatePublisher(client, state)
    # 연결 직후 1회 발행 (계약 §5.1 (a)). 재접속 때마다 다시 불린다.
    # paho 네트워크 스레드에서 불리므로 RobotState·StatePublisher 는 락으로 보호돼 있다.
    client.on_connected = publisher.on_connected
    return client, state, publisher


def run(client, state: RobotState, publisher: StatePublisher, stopping) -> None:
    """기동 전이 → 메인 루프. stopping 이 서면 돌아온다."""
    client.start()
    client.wait_until_connected(timeout=CONNECT_WAIT_SEC)

    state.boot_completed()  # BOOTING → READY  [F-001 초기화 완료]
    publisher.poll()
    # READY 에서 멈춘다. PATROLLING 으로 넘기는 것은 START_MISSION 이다 (R-5).

    while not stopping.wait(LOOP_INTERVAL_SEC):
        # 전이가 있었으면 즉시(§5.1 (b)), 없어도 30초마다(§5.1 (c)) 발행한다.
        # 판정은 publisher 안에 있다 — 여기서 비교하지 않는다.
        publisher.poll()


def shutdown(client, state: RobotState, publisher: StatePublisher) -> None:
    """SHUTTING_DOWN 을 마지막 state 로 남기고 끊는다.  [F-007]

    presence OFFLINE(GRACEFUL_SHUTDOWN) 은 client.shutdown() 이 보낸다.
    바로 뒤에 disconnect 가 오므로 state 가 소켓에 실릴 때까지 기다린다 —
    안 기다리면 큐에 남은 채 끊겨서 마지막 상태가 SHUTTING_DOWN 이 아닌 직전 값으로 남는다.
    """
    try:
        state.begin_shutdown()
        publisher.publish(force=True, wait_sec=SHUTDOWN_PUBLISH_WAIT_SEC)
    except Exception:  # 종료 경로에서 예외로 프로세스를 막지 않는다.
        log.warning("failed to publish SHUTTING_DOWN state", exc_info=True)
    client.shutdown()


def main() -> int:
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    client, state, publisher = build()
    stopping = threading.Event()

    def _request_stop(signum, _frame):
        # systemd stop 은 SIGTERM 을 보낸다. 핸들러에서는 깃발만 세우고
        # 실제 종료 발행은 메인 스레드가 한다.
        log.info("signal %s received, shutting down", signum)
        stopping.set()

    signal.signal(signal.SIGTERM, _request_stop)

    try:
        run(client, state, publisher, stopping)
    except KeyboardInterrupt:
        log.info("interrupted, shutting down")
    finally:
        shutdown(client, state, publisher)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


# 이후 티켓에서 이 조립에 더할 것 (지금은 범위 밖)
#   R-3 telemetry 5초 주기 발행 — position/battery/jetson_temp_c  [F-007]
#     telemetry.system.jetson_temp_c 는 tj-thermal(/sys thermal zone) 읽기
#   R-4 events 4종 — incident(업로드 후 image_id) / safety / battery / mission
#     장애물이 재확인 후에도 지속될 때만 SAFETY_STOPPED 1회 (계약 §10.2)
#   R-5 commands/request 처리 → commands/result 응답
#     START_MISSION 이 mission_id·zone_id 를 채우고 state.start_patrol() 을 부른다
#     (RobotState 의 미션 보관함)
#     expires_at 검증이 시계에 의존하므로 NTP 동기(timedatectl) 전제 (계약 §17)
#   주행·추론 — drive 스텝 + safety.check() / detect 프레임 추론  [F-002~F-004]
#   배포  [F-007]
#     systemd 서비스 등록: 부팅 자동 시작 + Restart=always(비정상 종료 자동 복구)
#     unit 파일·설치 안내는 scripts/ 에 작성
