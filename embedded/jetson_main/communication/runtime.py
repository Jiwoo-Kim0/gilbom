"""robot 메인 제어 루프와 MQTT v2 통신 계층의 조립 경계."""

from __future__ import annotations

import logging
import threading
from functools import partial
from pathlib import Path

from comms import ConfigError, MqttClient, MqttConfig, load_document
from comms.uploader import UploadConfig
from comms.uploader import upload as upload_image
from driving.states import AvoidPhase, DriveCommand, DriveState
from scripts.gps_reader import GpsReader

from communication.commands import DriveCommandRunner
from communication.evidence import EvidenceUploader, EvidenceWriter, prune_evidence
from communication.events import EventPublisher
from communication.robot_state import InvalidTransition, RobotState, StatePublisher
from communication.telemetry import TelemetrySampler

log = logging.getLogger(__name__)


class RuntimeSignals:
    """제어 루프의 최신 상태를 통신 어댑터에 안전하게 공유한다."""

    def __init__(self, *, live: bool) -> None:
        self._lock = threading.Lock()
        self._last_command = DriveCommand(0.0, 0.0, brake=True)
        self._camera_ok = None
        self._motor_ok = bool(live)

    def update(self, command: DriveCommand, *, camera_ok=None) -> None:
        with self._lock:
            self._last_command = command
            if camera_ok is not None:
                self._camera_ok = bool(camera_ok)

    def moving(self) -> bool:
        with self._lock:
            command = self._last_command
            return command.throttle_pct != 0.0 and not command.brake

    def camera_health(self) -> str:
        with self._lock:
            return "UNKNOWN" if self._camera_ok is None else ("OK" if self._camera_ok else "FAULT")

    def motor_health(self) -> str:
        # 장치 생성에 성공했다는 최소 판정. I2C 쓰기 예외는 main이 즉시 종료한다.
        return "OK" if self._motor_ok else "UNKNOWN"


class GpsTelemetryAdapter:
    def __init__(self, reader: GpsReader) -> None:
        self._reader = reader

    def __call__(self) -> dict:
        status = self._reader.status()
        valid = bool(status.get("connected") and status.get("fix"))
        return {
            "valid": valid,
            "lat": status.get("lat") if valid else None,
            "lon": status.get("lon") if valid else None,
            "heading_deg": status.get("course") if valid else None,
        }

    def health(self) -> str:
        status = self._reader.status()
        if not status.get("connected"):
            return "FAULT"
        return "OK" if status.get("fix") else "DEGRADED"


class DriveStateProjector:
    """실제 DriveState를 서버용 RobotState에 단방향 투영한다."""

    # ESCAPE(차도 이탈 선회)가 여기 들어있는 이유: 선회 중에도 로봇은 **달리고 있다**.
    # SAFE_STOP으로 보고하면 서버·관제 화면에 정지한 것으로 보이는데 사실이 아니다.
    # 차도 위험 자체는 서버 reason_code enum에 ROADWAY_RISK가 없어 상태로 표현할 방법이
    # 없고(아래 ESTOP 분기 주석), 선회로 벗어나지 못하면 timeout → ESTOP → SAFE_STOP이
    # 그 실패를 보고한다. TRACE는 점자블록 추종 = 미션 본체이므로 당연히 순찰 중이다.
    _PATROLLING = frozenset(
        {DriveState.CRUISE, DriveState.TRACE, DriveState.INSPECT, DriveState.TURN,
         DriveState.SLOPE, DriveState.DEAD_END, DriveState.ESCAPE}
    )

    def __init__(self, state: RobotState, events=None) -> None:
        self._state = state
        self._events = events
        self._roadway_warning_emitted = False

    def _pause_mission_for_safety(self) -> None:
        """스스로 건 ESTOP도 진행 중 미션을 PAUSED로 내린다 (웹 EMERGENCY_STOP과 동일).

        웹에서 온 EMERGENCY_STOP은 commands._commit_state가 MISSION_PAUSED를 내는데,
        차도 덮임·수동 B 버튼처럼 로봇이 스스로 선 경우는 여기(투영기)만 지나므로
        종전에는 미션이 RUNNING으로 남았다 — 서버 DB에 '수행 중인 미션인데 로봇은
        안전정지'라는 조합이 생긴다. 정지 사유가 무엇이든 순찰이 멈춘 것은 같으므로
        미션 상태도 같아야 한다.

        **재개는 여기서 하지 않는다.** ESTOP의 출구는 수동 해제뿐이고, 그 뒤 미션을
        다시 RUNNING으로 올리는 것은 RESUME_MISSION의 일이다 (사람이 상황을 확인한
        뒤에만 다시 달린다).
        """
        if self._events is None:
            return          # 투영기만 단독으로 쓰는 호출자(테스트 등)
        snapshot = self._state.snapshot()
        if snapshot.mission_id is None or snapshot.mission_state != "RUNNING":
            return
        self._events.mission_paused()

    def sync(self, machine) -> None:
        drive_state = machine.state
        context = machine.context
        snapshot = self._state.snapshot()

        try:
            if drive_state is DriveState.ESTOP:
                if snapshot.mode == "SAFE_STOP":
                    return
                if snapshot.mode == "ERROR":
                    self._state.stand_by()
                if "차도" in context.reason or "통행금지" in context.reason:
                    # 현재 서버 enum에 ROADWAY_RISK가 없다. 계약을 깨는 값을 보내지
                    # 않고 유효한 안전 사유로 보고하되, 로그로 계약 공백을 드러낸다.
                    if not self._roadway_warning_emitted:
                        log.error("MQTT reason_code에 ROADWAY_RISK가 없어 INTERNAL_ERROR로 투영")
                        self._roadway_warning_emitted = True
                    self._state.safe_stop("INTERNAL_ERROR")
                else:
                    self._state.safe_stop("INTERNAL_ERROR")
                self._pause_mission_for_safety()
                return

            if drive_state is DriveState.PAUSED:
                if snapshot.mission_id is not None and snapshot.mode != "PAUSED":
                    self._state.pause("REMOTE_PAUSE")
                    self._state.set_mission_state("PAUSED")
                return

            if drive_state is DriveState.IDLE:
                if "경계 소실" in context.reason or "인지 두절" in context.reason:
                    if snapshot.mode != "ERROR" or snapshot.reason_code != "BOUNDARY_LOST":
                        self._state.fault("BOUNDARY_LOST")
                elif "회차 실패" in context.reason:
                    if snapshot.mode != "ERROR":
                        self._state.fault("INTERNAL_ERROR")
                elif snapshot.mission_id is None and snapshot.mode not in {"READY", "SHUTTING_DOWN"}:
                    self._state.stand_by()
                return

            if drive_state is DriveState.AVOID and context.avoid_phase is AvoidPhase.HOLD:
                if snapshot.mode == "PATROLLING":
                    self._state.safe_stop("OBSTACLE")
                return

            if drive_state in self._PATROLLING or drive_state is DriveState.AVOID:
                if snapshot.mission_id is None:
                    return
                if snapshot.mode == "ERROR":
                    self._state.stand_by()
                    self._state.start_patrol()
                elif snapshot.mode == "SAFE_STOP" and snapshot.reason_code == "OBSTACLE":
                    self._state.release_safe_stop()
                elif snapshot.mode == "PAUSED":
                    self._state.resume()
                elif snapshot.mode == "READY":
                    self._state.start_patrol()
        except InvalidTransition:
            # 상태 투영 오류가 모터 루프를 죽이면 안 된다. 다음 tick에 재시도한다.
            log.exception("drive→MQTT state projection rejected")


class CommunicationRuntime:
    """MQTT 수명·명령·상태·텔레메트리를 한 경계에 묶는다."""

    def __init__(self, *, client, state, state_publisher, telemetry,
                 commands, projector, signals, gps_reader,
                 events, evidence, uploader) -> None:
        self.client = client
        self.state = state
        self._state_publisher = state_publisher
        self._telemetry = telemetry
        self._commands = commands
        self._projector = projector
        self._signals = signals
        self._gps_reader = gps_reader
        self._events = events
        self._evidence = evidence
        self._uploader = uploader

    @classmethod
    def build(cls, comms_config_path: str, robot_config: dict, *, live: bool):
        config_path = Path(comms_config_path).resolve()
        document = load_document(config_path)
        # 상대 outbox_path·ca_cert 는 MqttConfig 가 config 기준으로 풀어 준다.
        mqtt_config = MqttConfig.from_document(document, base_dir=config_path.parent)
        outbox_dir = Path(mqtt_config.outbox_path).parent

        client = MqttClient(mqtt_config)
        signals = RuntimeSignals(live=live)
        gps_cfg = robot_config["hardware"]["gps"]
        gps_reader = GpsReader(str(gps_cfg["device"]), int(gps_cfg["baud"]))
        gps = GpsTelemetryAdapter(gps_reader)
        state = RobotState(
            health_probes={
                "camera": signals.camera_health,
                "gps": gps.health,
                "motor": signals.motor_health,
            },
            outbox=client.outbox,
            # Outbox 와 같은 디렉터리다 — health.storage 가 감시하는 자리와
            # 미션 사본이 놓이는 자리를 갈라 놓지 않는다.
            storage_dir=outbox_dir,
        )
        state.boot_completed()
        state_publisher = StatePublisher(client, state)
        client.on_connected = state_publisher.on_connected
        interval = float((document.get("telemetry") or {}).get("interval_sec", 5.0))
        telemetry = TelemetrySampler(
            client, state, gps=gps, motor=signals.moving, interval_sec=interval
        )

        incident_cfg = document.get("incident") or {}
        events = EventPublisher(
            client, state,
            gps=gps,
            model_name=incident_cfg.get("model_name"),
            model_version=str(incident_cfg.get("model_version")),
            # 장애물은 주행 FSM(AVOID)이 세그멘테이션으로 판정한다 — 이 로봇엔
            # LiDAR 가 없으므로 EventPublisher 의 자체 감지 경로는 쓰지 않는다.
            obstacle_source=None,
            # 장치 FAULT 로 주행을 막는 판단은 주행 FSM 이 한다. 여기서 또 걸면
            # 같은 사실로 두 계층이 각자 정지를 만들어 서로를 못 푼다.
            sensor_fault_stops_driving=False,
        )
        evidence = uploader = None
        robot_uuid = document.get("robot_uuid") or None
        upload_config = _upload_config(document)
        evidence_dir = Path(incident_cfg.get("evidence_dir", "var/evidence"))
        if not evidence_dir.is_absolute():
            evidence_dir = config_path.parent / evidence_dir
        if robot_uuid and upload_config is not None:
            prune_evidence(evidence_dir)   # 지난 실행이 남긴 사진 정리
            evidence = EvidenceWriter(
                evidence_dir,
                long_edge_px=incident_cfg["evidence_long_edge_px"],
                quality=incident_cfg["evidence_jpeg_quality"],
                max_bytes=incident_cfg["evidence_max_bytes"],
                min_quality=incident_cfg["evidence_min_jpeg_quality"],
            )
            # config 를 여기서 묶어 넘긴다. 안 넘기면 uploader 가 자기 기본 경로
            # (config.yaml)를 읽는데 upload 절은 이 문서(comms.yaml)에만 있어서
            # 매 업로드가 ConfigError 로 조용히 실패한다 — 0804 실외에서 실측.
            uploader = EvidenceUploader(
                robot_uuid, upload=partial(upload_image, config=upload_config))
        elif not robot_uuid:
            # ROBOT_UUID 가 없으면 업로드가 400 으로 유실된다. 사진을 만들지도 않고
            # 결함 event 만 image_id: null 로 내보낸다 (§8.3) — 로그로 드러내 둔다.
            log.warning("ROBOT_UUID 가 없다 — 증거 사진 없이 결함만 보고한다")

        return cls(
            client=client,
            state=state,
            state_publisher=state_publisher,
            telemetry=telemetry,
            commands=DriveCommandRunner(client, state, events),
            projector=DriveStateProjector(state, events),
            signals=signals,
            gps_reader=gps_reader,
            events=events,
            evidence=evidence,
            uploader=uploader,
        )

    def start(self) -> None:
        self._gps_reader.start()
        self._close_orphan_mission()
        self.client.start()

    def _close_orphan_mission(self) -> None:
        """지난 프로세스가 미션 중에 죽었으면 그 미션을 FAILED 로 닫는다.

        재개하지 않는다 — 어디까지 순찰했는지 모르는 채로 바퀴가 다시 도는 것은
        사람 없이 출발하는 일이다. 서버 쪽에서는 종료 event 를 못 받은 미션이
        RUNNING 으로 남아 다음 START_MISSION 을 막으므로, 닫아 주는 것이 요점이다.

        접속 전에 부른다: event 는 Outbox 에 들어가고 접속 직후 `_drain_outbox` 가
        먼저 내보낸다. 브로커가 죽어 있어도 사본은 지워지고 재전송은 Outbox 몫이다.
        """
        if not self.state.take_orphan_mission():
            return
        try:
            payload = self._events.mission_failed("PROCESS_RESTARTED")
            log.warning("고아 미션 종료 보고: %s (incident %s건)",
                        payload.get("mission_id"),
                        payload.get("result", {}).get("incident_count"))
        except Exception:
            # 보고에 실패해도 기동은 계속한다. 사본이 남아 다음 기동에 다시 시도한다.
            log.exception("고아 미션 종료 보고 실패")

    def recent_commands(self) -> list:
        """서버에서 온 최근 명령과 그 처리 결과 (최신이 뒤). 관제·대시보드 표시용."""
        return list(self._commands.recent_results)

    def poll_before_step(self, machine) -> bool:
        return self._commands.poll_before_step(machine)

    def poll_after_step(self, machine, command: DriveCommand, *, camera_ok=None) -> None:
        # 이 함수는 main.apply_command 직후에 호출된다.
        self._signals.update(command, camera_ok=camera_ok)
        self._commands.poll_after_step(machine)
        self._projector.sync(machine)
        self._events.poll()          # health·battery 사건 (§9·§11)
        self._drain_uploads()        # 업로드가 끝난 결함을 발행한다
        self._state_publisher.poll()
        self._telemetry.poll()

    # -- 결함 보고 -------------------------------------------------------

    def report_inspection(self, verdict, image, captured_at: str) -> None:
        """검사 1회의 판정을 관제로 옮긴다. main.run_inspection 이 부른다.

        **발행까지 기다리지 않는다**: 사진 저장은 여기서(수 ms) 하고 업로드는 워커에
        맡긴 뒤 곧바로 돌아온다. 그래서 검사 정차는 판정까지로 끝나고, 사진이 올라가는
        동안 로봇은 이미 다음 블록을 찾아 달린다. 발행은 업로드가 끝난 뒤 메인 루프의
        poll_after_step → _drain_uploads 가 한다 — 워커가 직접 발행하면 seq 가 발행
        순서와 어긋나 서버가 조용히 버린다 (§3.5).

        정상 관측은 event 가 아니다 (§11.3) — 미션 집계만 올린다. 중복 제거는 FSM 의
        INSPECT 쿨다운이 이미 했으므로 여기 오는 것은 블록 하나당 한 번이다.
        """
        if not verdict.is_defect:
            if self.state.mission_id is not None:
                self.state.record_normal_detection()
            return
        height, width = image.shape[0], image.shape[1]
        box = _bbox_norm(verdict.defect_box, width, height)
        if box is None or verdict.defect_confidence is None:
            # 판정은 불량인데 근거 상자가 없다 — 계약이 필수로 요구하는 값이라
            # payload 를 만들 수 없다. 서버가 조용히 버리게 두지 않고 여기서 남긴다.
            log.error("[INSPECT] 불량 판정에 유효한 검출 상자가 없어 보고를 건너뛴다: %s",
                      verdict.reason)
            return
        observation = self._events.observe_incident(
            {
                # 검사 모델의 클래스는 normal·defect_full·defect_partial 셋뿐이라
                # 선형/점형을 구분하지 않는다 (models/README.md). 계약이 그 경우를
                # 위해 UNKNOWN 을 뒀다 (§8.3).
                "block_type": "UNKNOWN",
                "defect_type": "DAMAGED",     # MVP 는 이 값 하나뿐이다
                "confidence": verdict.defect_confidence,
                "bbox_norm": box,
                # damage_ratio 는 싣지 않는다 — band_argmax 는 면적 비율을 계산하지
                # 않는다. 없는 값을 지어내면 관제의 심각도 판단이 거짓이 된다.
            },
            image_width=width,
            image_height=height,
            captured_at=captured_at,
        )
        self._submit_evidence(observation, image)

    def report_missing_braille(self, image, captured_at: str = None) -> None:
        """횡단보도 이음부 점자블록 **미설치** 1건을 관제로 옮긴다. main 루프가 부른다.

        파손과 같은 topic·같은 event_type 으로 나가고 defect_type 만 MISSING 이다 —
        관제 입장에서 둘 다 '이 좌표의 점자블록에 문제가 있다' 이고, 지도 핀·증거 사진
        경로가 완전히 같다. bbox_norm·confidence 는 null 이다 (contract 의
        UNLOCALIZED_DEFECT_TYPES 주석).

        중복 제거는 호출부 몫이다 — 파손은 INSPECT 정차가 블록당 1회를 보장하지만
        미설치는 접근하는 수십 프레임에 계속 참이라, 호출부의 ReportGate(연속 프레임
        확정 + 쿨다운)를 지나온 것만 여기 온다. 서버 event_id 멱등 처리로는 못 막는다:
        프레임마다 새 event_id 가 붙으므로 서버에는 전부 다른 사건으로 보인다.
        """
        if image is None:
            # 계약이 evidence.image_width·image_height 를 필수로 요구한다 — 프레임이
            # 없으면 만들 수 없다. 로컬 events.jsonl 에는 이미 남았으니 조용히 넘기지
            # 않고 남긴다 (mock 인지로 돌릴 때 여기 온다).
            log.warning("[미설치] 원본 프레임이 없어 서버 보고를 건너뛴다")
            return
        height, width = image.shape[0], image.shape[1]
        observation = self._events.observe_incident(
            {
                # 있어야 할 블록이 무엇인지는 관측하지 못했다 — 없는 것의 종류를
                # 추측하지 않는다 (§8.3 의 UNKNOWN).
                "block_type": "UNKNOWN",
                "defect_type": "MISSING",
            },
            image_width=width,
            image_height=height,
            captured_at=captured_at,
        )
        self._submit_evidence(observation, image)

    def _submit_evidence(self, observation, image) -> None:
        """관측 1건을 업로드 워커에 태운다. 발행은 _drain_uploads 가 한다."""
        if self._uploader is None:
            # 사진 경로가 없는 구성(ROBOT_UUID 없음) — 바로 발행한다.
            self._publish_uploaded([(observation, None)])
            return
        image_path = None if self._evidence is None else self._evidence.write(image)
        self._uploader.submit(observation, image_path)

    def _drain_uploads(self) -> int:
        if self._uploader is None:
            return 0
        return self._publish_uploaded(self._uploader.drain())

    def _publish_uploaded(self, finished) -> int:
        """[(observation, image_id), …] 를 발행하고 건수를 돌려준다."""
        published = 0
        for observation, image_id in finished:
            try:
                self._events.report_incident(observation, image_id=image_id)
                published += 1
            except Exception:
                # 결함 하나가 넘어져도 나머지와 메인 루프는 계속 간다.
                log.exception("결함 발행 실패 (event_id=%s)", observation.event_id)
        return published

    def shutdown(self) -> None:
        self._gps_reader.stop()
        if self._uploader is not None:
            # 밀린 업로드를 끝내고 그 결과까지 발행한 뒤에 소켓을 닫는다.
            self._publish_uploaded(self._uploader.close())
        # 미션을 먼저 닫는다 — 결함 event 는 활성 미션을 전제하므로 위 업로드
        # 마무리보다 뒤, 소켓을 닫기보다는 앞이어야 한다.
        self._close_active_mission()
        try:
            self.state.begin_shutdown()
            self._state_publisher.publish(force=True, wait_sec=1.0)
        except Exception:
            log.exception("failed to publish shutting-down state")
        self.client.shutdown()

    def _close_active_mission(self) -> None:
        """종료 시점에 미션이 살아 있으면 그 자리에서 FAILED 로 닫는다.

        다음 기동의 `_close_orphan_mission` 에 미뤄도 결과는 같지만, 그때까지
        서버 미션이 RUNNING 으로 남아 새 START_MISSION 을 막는다. 밤새 세워 두면
        밤새 막힌다. 소켓이 아직 살아 있는 이 자리가 가장 빠른 시점이다.

        재개는 하지 않는다 — 종료가 곧 미션 실패다(`take_orphan_mission` 참조).
        kill -9·전원 차단은 여기를 지나지 못한다. 그쪽은 mission.json 선기록과
        다음 기동의 복구가 받는다.
        """
        if self.state.mission_id is None:
            return
        try:
            payload = self._events.mission_failed("GRACEFUL_SHUTDOWN")
            log.warning("종료 — 진행 중이던 미션을 닫는다: %s (incident %s건)",
                        payload.get("mission_id"),
                        payload.get("result", {}).get("incident_count"))
        except Exception:
            # 못 닫아도 종료는 진행한다. 사본이 남아 다음 기동이 다시 시도한다.
            log.exception("종료 시 미션 종료 보고 실패")


def _upload_config(document: dict):
    """comms 설정의 upload 절 → UploadConfig. 없거나 비면 None (업로드만 끈다).

    비어 있어도 기동은 막지 않는다 — 사진 없이 결함만 보고하는 구성이 계약상
    유효하다 (§8.3). 다만 그 사실을 로그로 드러낸다: 조용히 None 을 돌려주면
    URL 하나가 비었을 때 '업로드는 도는데 서버에 사진이 없다' 로 보인다.
    """
    try:
        return UploadConfig.from_document(document)
    except ConfigError as error:
        log.warning("증거 사진 업로드 설정이 없다 (%s) — 결함은 image_id 없이 보고한다",
                    error)
        return None


def _bbox_norm(box, width: int, height: int):
    """픽셀 상자 → 정규화 [x_min, y_min, x_max, y_max]. 만들 수 없으면 None.

    서버는 각 값이 0~1 이고 x_min < x_max, y_min < y_max 인 **강한 부등식**을
    요구한다 (§8.3). 화면 밖으로 삐져나온 상자를 자르다 폭이 0 이 되면 보낼 수 없다.
    """
    if not box or width <= 0 or height <= 0:
        return None
    x_min, x_max = sorted((box[0] / width, box[2] / width))
    y_min, y_max = sorted((box[1] / height, box[3] / height))
    values = [max(0.0, min(1.0, value)) for value in (x_min, y_min, x_max, y_max)]
    if values[0] >= values[2] or values[1] >= values[3]:
        return None
    return values
