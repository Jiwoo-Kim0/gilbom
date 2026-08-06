"""robot 메인 제어 루프와 MQTT v2 통신 계층의 조립 경계."""

from __future__ import annotations

import logging
import threading
from functools import partial
from pathlib import Path

from comms import ConfigError, MqttClient, MqttConfig, load_document
from comms.uploader import UploadConfig
from comms.uploader import upload as upload_image
from driving.states import PAUSABLE_STATES, AvoidPhase, DriveCommand, DriveState
from hardware.battery import build_from_config as build_battery_from_config
from scripts.gps_reader import GpsReader

from communication.commands import DriveCommandRunner
from communication.evidence import EvidenceUploader, EvidenceWriter, prune_evidence
from communication.events import EventPublisher
from communication.robot_state import (
    BatteryThresholds, InvalidTransition, RobotState, StatePublisher,
)
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
    # DEAD_END(재정향)는 여기 없다 — 아래 _project_dead_end 가 따로 받는다.
    _PATROLLING = frozenset(
        {DriveState.CRUISE, DriveState.TRACE, DriveState.INSPECT, DriveState.TURN,
         DriveState.SLOPE, DriveState.ESCAPE}
    )

    def __init__(self, state: RobotState, events=None) -> None:
        self._state = state
        self._events = events
        self._roadway_warning_emitted = False
        self._dead_end_warning_emitted = False
        #: '이번 ESTOP 은 원격 조작면에서 눌렀다' — 웹 스레드가 세우고 주행 루프가 읽는다.
        #: FSM 은 누가 request_estop() 했는지 모르므로(Xbox B·터치·웹·배터리가 전부 같은
        #: 요청) 그 구분을 여기로 들고 온다. threading.Event 인 이유는 쓰는 쪽이 웹
        #: 스레드이기 때문이다 — RobotState 를 웹 스레드에서 직접 건드리지 않는다
        #: (mode 전이는 주행 루프 한 곳에서만 일어나야 한다).
        self._operator_estop = threading.Event()

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

    #: IDLE 사유 중 **인지 계열** — 이것만 BOUNDARY_LOST 로 갈린다. 목록에 없는 사유가
    #: 조용히 사라지지 않는 것이 요점이고, 그래서 이 문자열들은 '보고할 것인가'가 아니라
    #: '어느 사유로 보고할 것인가'만 정한다 (_project_idle).
    _PERCEPTION_STOP_MARKERS = ("경계 소실", "인지 두절")

    def _project_idle(self, context, snapshot) -> None:
        """IDLE 투영 — **미션이 도는데 스스로 섰으면 그 자체가 결함이다.**

        종전에는 사유 문자열 세 개로 갈랐고 **매칭이 없으면 아무 일도 하지 않았다.**
        그 기본값이 틀렸다: 로봇은 인도에 선 채 사람을 기다리는데 서버 투영은
        PATROLLING(순찰 중)으로 남는다 — `_pause_mission_for_safety` 와 급경사 ESTOP
        승격이 막으려던 바로 그 조합인데, 정작 가장 흔한 정지가 그 구멍으로 빠졌다.
        실제로 빠지던 둘:
          · "후진 복구 상한 — 전 방향 막힘"  (0805 실기에서 미션이 끝난 33회 중 11회)
          · "재정향 실패(회전 …/타임아웃 …)"
        게다가 세 문자열 중 `"회차 실패"` 는 0804 의 이름 변경(회차 → 재정향) 이후
        FSM 이 **한 번도 내지 않는 죽은 값**이었다 — 문자열 매칭이 조용히 끊긴다는
        것을 그 자체로 보여 준 자리다.

        그래서 기본값을 뒤집는다. 판정은 문자열이 아니라 **미션 상태**가 한다:
          미션이 RUNNING 인데 IDLE  → 결함. 사유 문구가 무엇이든.
          미션이 없다               → 대기 복귀(종전과 같다).
          미션이 RUNNING 이 아니다   → 건드리지 않는다. 서버가 이미 닫았거나
                                     (COMPLETE·CANCEL 은 end_mission 으로 보관함을
                                      비운다) 사람이 세워 둔 것이다
                                     (CLEAR_EMERGENCY_STOP → 미션 PAUSED + IDLE).
        인지 계열만 예외로 먼저 가른다 — 미션 유무와 무관하게 BOUNDARY_LOST 로
        보고하던 종전 동작을 그대로 둔다(수동 주행 중 세그 장애도 사람이 알아야 한다).

        **안전정지 해제도 여기서 한다.** ESTOP 의 유일한 출구가 IDLE 이므로, 사람이
        웹 버튼·터치로 푼 ESTOP 은 이 함수로 들어온다. 종전에는 미션이 살아 있으면
        (ESTOP 진입 때 _pause_mission_for_safety 가 PAUSED 로 내려놨다) 세 분기 어디에도
        걸리지 않아 **mode 가 SAFE_STOP 으로 영영 남았다** — 그 상태에서는 서버의
        RESUME·START·CLEAR_EMERGENCY_STOP 이 전부 거부되어(commands._precondition)
        관제가 로봇을 되살릴 방법이 없어진다. 로컬에서 푼 정지가 원격 조작을 통째로
        잠그는 셈이라, 해제를 서버 투영에도 반영한다.
        """
        if any(marker in context.reason
               for marker in self._PERCEPTION_STOP_MARKERS):
            if snapshot.mode != "ERROR" or snapshot.reason_code != "BOUNDARY_LOST":
                self._state.fault("BOUNDARY_LOST")
            return
        if snapshot.mission_state == "RUNNING":
            if snapshot.mode != "ERROR" or snapshot.reason_code != "INTERNAL_ERROR":
                self._state.fault("INTERNAL_ERROR")
            return
        if snapshot.mode == "SAFE_STOP":
            # 미션 유무는 RobotState 가 본다: 미션이 있으면 PAUSED(관제의 재개 대상),
            # 없으면 READY. CLEAR_EMERGENCY_STOP 이 만드는 값과 같은 자리로 수렴한다.
            self._state.release_safe_stop()
            return
        if (snapshot.mission_id is None
                and snapshot.mode not in {"READY", "SHUTTING_DOWN"}):
            self._state.stand_by()

    def _project_dead_end(self, snapshot) -> None:
        """재정향(후진·U턴)을 관제에 드러낸다 — 계약에 이 기동을 담을 자리가 없다.

        DEAD_END 는 로봇이 **뒤로 물러나며** 방향을 되찾는 기동이고, 후방은 카메라
        사각(완전 무지)이라 사람이 알아야 하는 상황이다. 그런데 종전에는 이 상태가
        _PATROLLING 에 들어 있어 서버에는 '순찰 중'으로 나갔다 — 인도에서 최대 1.6m를
        후진하고 180° 회차하는 동안 관제 화면은 정상 순찰이었다는 뜻이다.

        mode enum 에 '재정향'이 없으므로(계약 §5) 가장 가까운 참을 고른다:
        SAFE_STOP/OBSTACLE = '장애물 때문에 정상 순찰을 못 하고 있다'. 실제로 재정향의
        진입 조건이 그것이고(AVOID 재확인 · 반복 회피), 정지 대기(HOLD)가 이미 같은
        값으로 투영되므로 이 기동 내내 **하나의 사유가 이어진다**. 재정향에 성공해
        CRUISE 로 나가면 아래 _PATROLLING 분기의 release_safe_stop 이 그 자리에서 푼다.
        ROADWAY_RISK 를 INTERNAL_ERROR 로 투영할 때와 같은 처리다 — 계약 공백은
        로그로 드러내 두고, 없는 값을 지어내지 않는다.
        """
        if snapshot.mission_id is None:
            return          # 미션 없이 도는 것은 로컬 시험이다 (형제 분기와 같은 전제)
        if snapshot.mode == "SAFE_STOP":
            return          # 이미 같은 사유로 알렸다
        if not self._dead_end_warning_emitted:
            log.warning("MQTT mode enum 에 재정향(후진·회차)이 없어 SAFE_STOP/OBSTACLE 로 "
                        "투영한다 — 관제에는 '장애물로 정지'로 보인다")
            self._dead_end_warning_emitted = True
        if snapshot.mode == "ERROR":
            self._state.stand_by()      # ERROR 에서는 SAFE_STOP 으로 직행할 수 없다
        self._state.safe_stop("OBSTACLE")

    def note_operator_estop(self) -> None:
        """원격 조작면(웹 대시보드 비상 정지 버튼)에서 정지를 눌렀다.

        **FSM 요청보다 먼저 부른다** — 그래야 주행 루프가 그 요청을 소비해 ESTOP 으로
        들어가는 tick 에 이 사실이 이미 보인다. 래치는 ESTOP 을 벗어날 때 풀린다.
        """
        self._operator_estop.set()

    def sync(self, machine) -> None:
        drive_state = machine.state
        context = machine.context
        snapshot = self._state.snapshot()

        if drive_state is not DriveState.ESTOP:
            # 정지가 풀렸다 — 다음 ESTOP 은 그 자체의 출처로 다시 판정한다.
            self._operator_estop.clear()

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
                elif self._operator_estop.is_set():
                    # 원격 조작면(웹 대시보드 버튼)에서 누른 비상 정지다 — 관제가 누른
                    # EMERGENCY_STOP 과 같은 사건이므로 같은 값으로 보고한다.
                    # INTERNAL_ERROR 로 보고하면 CLEAR_EMERGENCY_STOP 의 전제조건이
                    # (SAFE_STOP, REMOTE_ESTOP) 이라 관제가 풀 수 없고, 웹에서 풀어도
                    # 로봇 고장으로 남는다. **몸체의 물리 정지(Xbox B·터치)는 여기
                    # 오지 않는다** — 래치를 세우지 않으므로 종전대로 현장 해제만
                    # 가능하다 (test_remote_clear_estop_is_rejected_for_a_local_estop).
                    self._state.safe_stop("REMOTE_ESTOP")
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
                self._project_idle(context, snapshot)
                return

            if drive_state is DriveState.DEAD_END:
                self._project_dead_end(snapshot)
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


class BatteryDriveGuard:
    """확정된 배터리 등급을 **주행 FSM 요청**으로 옮긴다 (계약 §9.3 조치의 실행부).

    EventPublisher 쪽 조치(_apply_battery_action)는 서버로 보고할 mode·미션만 바꾼다 —
    RobotState 는 서버 투영이지 모터 제어권자가 아니기 때문이다(commands.py 머리말).
    그 상태로 두면 관제 화면은 LOW_BATTERY 로 서 있다고 말하는데 바퀴는 계속 도는,
    보고와 현실이 어긋난 로봇이 된다. 여기가 그 간극을 메운다.

    등급 판정·히스테리시스·연속 표본 확정은 전부 RobotState 안에서 끝났다 — 여기서는
    확정된 등급만 읽는다. 요청은 다음 tick 의 machine.step() 에서 소비되므로 실제
    정지까지 최대 한 주기(0.5초) 걸린다. 그 자리에서 모터를 세우지 않는 것은
    single writer 규칙이다(하드웨어 쓰기는 main.apply_command 한 곳).

      CRITICAL  달리는 중이면 매 tick ESTOP 을 다시 요청한다. 반복하는 이유는 이
                등급의 출구가 충전뿐이기 때문이다 — 사람이 ESTOP 을 풀어도 잔량이
                그대로면 다시 선다. 임계 잔량에서 다시 출발하는 것을 허용하면
                로봇은 인도 한가운데서 전원이 나간다.
      LOW       LOW 로 떨어진 그 한 번만 일시정지를 요청한다. 반복하지 않는 것은
                '관제가 판단해서 재개할 수 있다'가 이 등급의 뜻이라서다 (§9.3).
                다시 요청하면 서버의 RESUME_MISSION 과 매 tick 싸운다.
    """

    def __init__(self, state, *, enabled: bool = True) -> None:
        self._state = state
        self._enabled = bool(enabled)
        self._requested_grade = None  # LOW 요청을 이미 넣었는지

    def apply(self, machine) -> None:
        if not self._enabled:
            return
        grade = self._state.snapshot().battery_state
        if grade != "LOW":
            # NORMAL 로 올라왔거나(충전 뒤 재기동) CRITICAL 로 더 떨어졌다.
            # 다음 LOW 진입은 새 사건이므로 요청 기록을 지운다.
            self._requested_grade = None
        if grade == "CRITICAL":
            if machine.state is not DriveState.ESTOP:
                log.warning("배터리 임계 — 주행 비상 정지 요청 (해제는 충전 뒤에만)")
                machine.request_estop()
            return
        if grade != "LOW" or self._requested_grade == "LOW":
            return
        if machine.state is DriveState.IDLE or machine.state not in PAUSABLE_STATES:
            # 이미 서 있거나(IDLE) 지금은 세울 수 없는 국면(ESCAPE — 차도 위 정지가
            # 된다)이다. 기록을 남기지 않으므로 다음 tick 에 다시 본다.
            return
        log.warning("배터리 부족 — 순찰 일시정지 요청")
        machine.request_pause()
        self._requested_grade = "LOW"


def _build_state(client, gps: GpsTelemetryAdapter, signals: RuntimeSignals,
                 battery, robot_config: dict, storage_dir) -> RobotState:
    """서버 투영 상태 — health 프로브·저장 경로·배터리 임계를 한 번에 묶는다.

    health.battery 는 추정인 동안 DEGRADED 다. 잔량을 읽는 장치가 없다는 사실을
    health 에서 지우지 않기 위한 것이다 (hardware/battery.py).
    """
    probes = {
        "camera": signals.camera_health,
        "gps": gps.health,
        "motor": signals.motor_health,
    }
    if battery is not None:
        probes["battery"] = battery.health
    state = RobotState(
        health_probes=probes,
        outbox=client.outbox,
        # Outbox 와 같은 디렉터리다 — health.storage 가 감시하는 자리와
        # 미션 사본이 놓이는 자리를 갈라 놓지 않는다.
        storage_dir=storage_dir,
        battery_thresholds=BatteryThresholds.from_document(robot_config),
    )
    state.boot_completed()
    return state


def _build_evidence(document: dict, incident_cfg: dict, config_dir: Path) -> tuple:
    """증거 사진 (writer, uploader). 둘 다 없는 구성이 계약상 유효하다 (§8.3).

    ROBOT_UUID 가 없으면 업로드가 400 으로 유실되므로 사진을 만들지도 않고 결함
    event 만 image_id: null 로 내보낸다 — 조용히 넘기지 않고 로그로 드러내 둔다.
    """
    robot_uuid = document.get("robot_uuid") or None
    upload_config = _upload_config(document)
    evidence_dir = Path(incident_cfg.get("evidence_dir", "var/evidence"))
    if not evidence_dir.is_absolute():
        evidence_dir = config_dir / evidence_dir
    if not (robot_uuid and upload_config is not None):
        if not robot_uuid:
            log.warning("ROBOT_UUID 가 없다 — 증거 사진 없이 결함만 보고한다")
        return None, None

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
    return evidence, EvidenceUploader(
        robot_uuid, upload=partial(upload_image, config=upload_config))


class CommunicationRuntime:
    """MQTT 수명·명령·상태·텔레메트리를 한 경계에 묶는다."""

    def __init__(self, *, client, state, state_publisher, telemetry,
                 commands, projector, signals, gps_reader,
                 events, evidence, uploader, owns_gps_reader=True,
                 battery=None, battery_guard=None) -> None:
        self.client = client
        self.state = state
        self._state_publisher = state_publisher
        self._telemetry = telemetry
        self._commands = commands
        self._projector = projector
        self._signals = signals
        self._gps_reader = gps_reader
        #: 잔량 추정기 (hardware.battery). 없으면 배터리 경로 전체가 꺼진 구성이다.
        self._battery = battery
        self._battery_guard = battery_guard
        # 리더를 빌린 경우(주행 쪽이 이미 열었다) 수명은 그쪽 소유다 — 여기서
        # start/stop 하지 않는다. 같은 시리얼 장치를 두 리더가 열면 커널 tty 버퍼의
        # 바이트를 나눠 읽어 NMEA 문장이 양쪽 다 조각나고, 체크섬 실패로 좌표가
        # 간헐적으로 전부 무효가 된다 (지도 핀·telemetry 위치 동시 타격).
        self._owns_gps_reader = bool(owns_gps_reader)
        self._events = events
        self._evidence = evidence
        self._uploader = uploader

    @property
    def gps_reader(self):
        """이 런타임이 쓰는 GPS 리더 — 같은 장치를 두 번 열지 않게 공유용으로 노출한다."""
        return self._gps_reader

    @classmethod
    def build(cls, comms_config_path: str, robot_config: dict, *, live: bool,
              gps_reader=None, battery_full: bool = False, start_interlock=None):
        """`gps_reader` 를 주면 그 리더를 **공유**한다 (수명은 호출자 소유).

        main·web_test 는 좌표 태깅용 리더를 이미 열어 두므로 그걸 넘겨야 한다 —
        여기서 또 만들면 같은 시리얼 장치를 두 리더가 읽어 NMEA 가 양쪽 다 깨진다
        (0804 좌표 무효의 원인 후보). 안 주면 종전대로 스스로 만들고 소유한다.

        `battery_full` 은 '방금 충전을 끝내고 켰다'는 사람의 선언이다 — 잔량은
        시간으로 역산하므로 로봇 스스로는 충전을 알 수 없다 (hardware/battery.py).

        `start_interlock` 은 터치 센서를 쥔 쪽(main·web_test)이 만들어 넘긴다 —
        서버 START_MISSION 도 로봇 몸체의 터치가 있어야 실제로 출발한다
        (driving/start_interlock.py). 안 주면 종전대로 명령 즉시 출발이다.
        """
        config_path = Path(comms_config_path).resolve()
        document = load_document(config_path)
        # 상대 outbox_path·ca_cert 는 MqttConfig 가 config 기준으로 풀어 준다.
        mqtt_config = MqttConfig.from_document(document, base_dir=config_path.parent)
        client = MqttClient(mqtt_config)
        signals = RuntimeSignals(live=live)

        owns_gps_reader = gps_reader is None
        if gps_reader is None:
            gps_cfg = robot_config["hardware"]["gps"]
            gps_reader = GpsReader(str(gps_cfg["device"]), int(gps_cfg["baud"]))
        gps = GpsTelemetryAdapter(gps_reader)

        # 배터리 (계약 §9) — 잔량을 읽는 장치가 없어 가동 시간으로 역산한다.
        # 임계값(등급)과 추정 파라미터는 둘 다 config.yaml 의 battery 절이다:
        # 튜닝 숫자는 그 파일에만 둔다는 저장소 규칙이고, 절이 통째로 없으면
        # 추정기는 None 이 되어 종전대로 valid=false/UNKNOWN 이 나간다 (§5.5).
        # 상대 state_file 은 comms 설정 기준으로 푼다 — mqtt.outbox_path 와 같은
        # 규칙이라 로봇이 남기는 상태 파일이 한 자리(var/)에 모인다.
        battery_cfg = (robot_config or {}).get("battery") or {}
        battery = build_battery_from_config(robot_config,
                                            config_dir=config_path.parent)
        if battery is not None and battery_full:
            battery.mark_full()

        state = _build_state(client, gps, signals, battery, robot_config,
                             storage_dir=Path(mqtt_config.outbox_path).parent)
        state_publisher = StatePublisher(client, state)
        client.on_connected = state_publisher.on_connected

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
        evidence, uploader = _build_evidence(document, incident_cfg,
                                             config_path.parent)

        return cls(
            client=client,
            state=state,
            state_publisher=state_publisher,
            telemetry=TelemetrySampler(
                client, state, gps=gps, battery=battery, motor=signals.moving,
                interval_sec=float((document.get("telemetry") or {})
                                   .get("interval_sec", 5.0))),
            commands=DriveCommandRunner(client, state, events,
                                        start_interlock=start_interlock),
            projector=DriveStateProjector(state, events),
            signals=signals,
            gps_reader=gps_reader,
            events=events,
            evidence=evidence,
            uploader=uploader,
            owns_gps_reader=owns_gps_reader,
            battery=battery,
            battery_guard=BatteryDriveGuard(
                state,
                # 보고만 하고 주행은 건드리지 않는 구성을 남겨 둔다 — 추정값으로
                # 바퀴를 세우는 것이 아직 이르다고 판단하면 이 스위치만 내린다.
                enabled=battery is not None and bool(
                    battery_cfg.get("stop_driving", True))),
        )

    def start(self) -> None:
        if self._owns_gps_reader:
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

    def note_operator_estop(self) -> None:
        """웹 대시보드 비상 정지 버튼 — 관제의 EMERGENCY_STOP 과 같은 사건으로 보고한다.

        웹 스레드에서 부른다. 실제 상태 전이는 주행 루프의 투영기가 한다
        (DriveStateProjector.note_operator_estop 참조).
        """
        self._projector.note_operator_estop()

    def poll_before_step(self, machine) -> bool:
        return self._commands.poll_before_step(machine)

    def poll_after_step(self, machine, command: DriveCommand, *, camera_ok=None) -> None:
        # 이 함수는 main.apply_command 직후에 호출된다.
        self._signals.update(command, camera_ok=camera_ok)
        self._commands.poll_after_step(machine)
        self._projector.sync(machine)
        self._events.poll()          # health·battery 사건 (§9·§11)
        if self._battery_guard is not None:
            # events 가 정한 조치(§9.3)를 바퀴에 반영한다 — 순서가 중요하다:
            # 등급 확정·mode 전이가 끝난 뒤라야 이번 tick 의 등급으로 요청한다.
            self._battery_guard.apply(machine)
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
        if self._owns_gps_reader:
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
        if self._battery is not None:
            try:
                # 마지막 잔량을 남긴다 — 다음 기동이 여기서 이어받는다. 안 남기면
                # 재시작 한 번에 최대 persist_step_pct 만큼 잔량이 되살아난다.
                self._battery.close()
            except Exception:
                log.exception("배터리 추정 기록 마무리 실패")
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
