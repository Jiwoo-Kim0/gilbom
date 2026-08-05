"""로봇 상태 FSM · health 취합 · state 발행 타이밍.  [F-007]

state 에 실리는 값의 판정은 전부 여기서 한다 — main.py 는 조립만 한다.
payload 조립은 하지 않는다. 값만 모아 factory.state() 에 넘긴다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §5
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from comms import TopicKind
from comms.codec import to_iso
from comms.contract import (
    BATTERY_SOURCES,
    HEALTH_MEMBERS,
    HEALTH_STATES,
    MISSION_STATES,
    MODES,
    REASON_CODES,
    SAFETY_REASON_CODES,
)

log = logging.getLogger(__name__)

#: 미션 보관함 디스크 사본. storage_dir 안에 둔다 — Outbox 와 같은 자리라
#: 쓰기 가능 여부를 health.storage 가 이미 감시하고 있다.
MISSION_FILE_NAME = "mission.json"


# --------------------------------------------------------------------------
# 전이 표 (계약 §5.4)
# --------------------------------------------------------------------------

#: 어느 상태에서든 갈 수 있는 곳. SHUTTING_DOWN 은 종점이라 여기서 나가지 못한다.
_ALWAYS_REACHABLE = frozenset({"ERROR", "SHUTTING_DOWN"})

#: 정상 경로.
#:     BOOTING → READY → PATROLLING ↔ PAUSED
#:                     ↕
#:                   SAFE_STOP
#: SAFE_STOP 은 운행 3종(READY·PATROLLING·PAUSED)과 왕복한다 — 장애물은 주행 중,
#: 원격 정지는 대기 중에도 걸릴 수 있기 때문이다.
#: PATROLLING·PAUSED → READY 는 미션이 끝나 대기로 돌아가는 경로다(R-5 에서 사용).
_TRANSITIONS = {
    "BOOTING": frozenset({"READY"}),
    "READY": frozenset({"PATROLLING", "SAFE_STOP"}),
    "PATROLLING": frozenset({"PAUSED", "SAFE_STOP", "READY"}),
    "PAUSED": frozenset({"PATROLLING", "SAFE_STOP", "READY"}),
    "SAFE_STOP": frozenset({"PATROLLING", "PAUSED", "READY"}),
    "ERROR": frozenset({"READY"}),
    "SHUTTING_DOWN": frozenset(),
}

#: SAFE_STOP 으로 넘어갈 수 있는 mode — 전이 표에서 뽑는다(표와 갈라지지 않게).
#: 안전 정지를 새로 걸어도 되는지 묻는 곳(events·bridge)이 이 집합을 본다.
#: SAFE_STOP 자신이 빠져 있는 것이 요점이다 — 이미 선 로봇의 정지 사유를 덮어쓰면
#: 먼저 걸린 사유(관리자 e-stop 등)가 기록에서 지워진다.
SAFE_STOPPABLE_MODES = frozenset(
    mode for mode, targets in _TRANSITIONS.items() if "SAFE_STOP" in targets
)

#: mode 마다 허용되는 reason_code. 이 표 밖의 짝은 만들 수 없다.
#: "왜 이 mode 가 됐는가" 가 mode 와 어긋나면 서버 대시보드가 설명 불가능한 상태를
#: 그리게 되므로, 짝은 전이 메서드를 통해서만 바뀐다.
MODE_REASONS = {
    "BOOTING": frozenset({"NONE"}),
    "READY": frozenset({"NONE"}),
    "PATROLLING": frozenset({"NONE"}),
    "PAUSED": frozenset({"REMOTE_PAUSE", "LOW_BATTERY"}),
    # 안전 정지 사유는 events/safety 의 reason_code 와 같은 집합을 쓴다 (§10.5).
    "SAFE_STOP": SAFETY_REASON_CODES,
    "ERROR": frozenset(
        {
            "INTERNAL_ERROR",
            "LIDAR_FAULT",
            "MOTOR_FAULT",
            "BOUNDARY_LOST",
            "CRITICAL_BATTERY",
        }
    ),
    "SHUTTING_DOWN": frozenset({"NONE", "CRITICAL_BATTERY", "INTERNAL_ERROR"}),
}


class InvalidTransition(ValueError):
    """전이 표에 없는 mode 이동. 호출자의 버그다."""


def _check_pair(mode: str, reason_code: str) -> None:
    if mode not in MODES:
        raise ValueError(f"state.mode: {mode!r} is not one of {sorted(MODES)}")
    if reason_code not in REASON_CODES:
        raise ValueError(
            f"state.reason_code: {reason_code!r} is not one of {sorted(REASON_CODES)}"
        )
    if reason_code not in MODE_REASONS[mode]:
        raise ValueError(
            f"({mode}, {reason_code}) is not a valid pair;"
            f" {mode} allows {sorted(MODE_REASONS[mode])}"
        )


def _can_enter(current: str, target: str) -> bool:
    if target == current:
        return True  # 같은 mode 안에서 reason_code 만 바뀌는 경우
    if current == "SHUTTING_DOWN":
        return False
    if target in _ALWAYS_REACHABLE:
        return True
    return target in _TRANSITIONS[current]


# --------------------------------------------------------------------------
# 배터리 등급 임계값 (계약 §9)
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class BatteryThresholds:
    """등급 판정 임계값. 기본값은 F-007·계약 §9.2 와 같은 값이다.

    내려가는 방향(low_pct·critical_pct)과 올라오는 방향(*_recover_pct)이 다르다 —
    잔량이 전압 기반 추정이라 모터가 물리면 퍼센트가 출렁이는데, 단일 임계값이면
    같은 경계를 오르내리며 BATTERY_LOW/BATTERY_RECOVERED 가 번갈아 나간다.
    """

    low_pct: float = 20.0
    low_recover_pct: float = 25.0
    critical_pct: float = 10.0
    critical_recover_pct: float = 15.0
    #: 등급 전이로 인정하기까지 필요한 연속 표본 수. 배터리는 telemetry 주기에
    #: 읽히므로 2 는 10초 연속 확인을 뜻한다.
    confirm_samples: int = 2

    @classmethod
    def from_document(cls, document: dict) -> "BatteryThresholds":
        """config.yaml 의 battery 절. 없는 값은 기본값을 쓴다."""
        section = (document or {}).get("battery") or {}
        return cls(
            low_pct=float(section.get("low_pct", cls.low_pct)),
            low_recover_pct=float(section.get("low_recover_pct", cls.low_recover_pct)),
            critical_pct=float(section.get("critical_pct", cls.critical_pct)),
            critical_recover_pct=float(
                section.get("critical_recover_pct", cls.critical_recover_pct)
            ),
            confirm_samples=int(section.get("confirm_samples", cls.confirm_samples)),
        )


# --------------------------------------------------------------------------
# health.storage 판정 (계약 §5.3)
# --------------------------------------------------------------------------

#: 하드웨어 프로브 주기. 한 tick 안에서 state·telemetry·event 가 각자 health 를
#: 읽으므로, 0.5초 tick 두 번에 한 번만 실제 장치를 건드리게 묶는다.
HEALTH_PROBE_INTERVAL_SEC = 1.0
#: Outbox 에 이만큼 쌓이면 저장 계층이 정상 속도로 비워지지 않는다고 본다.
STORAGE_PENDING_DEGRADED = 100
#: 파일 쓰기 검사 주기. 매 tick 마다 디스크를 건드리지 않기 위한 것이다.
#: health 캐시보다 긴 것은 디스크 쓰기가 I2C·시리얼 읽기보다 비싸기 때문이다.
STORAGE_PROBE_INTERVAL_SEC = 5.0


def _writable(directory) -> bool:
    """실제로 한 번 써 본다 — 마운트가 read-only 로 떨어지는 경우를 잡는다."""
    probe = Path(directory) / f".health-probe-{os.getpid()}"
    try:
        probe.write_bytes(b"")
        probe.unlink()
        return True
    except OSError:
        return False


# --------------------------------------------------------------------------
# 스냅샷
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Snapshot:
    """어느 한 시점의 state 값 묶음. 읽는 쪽이 락을 신경 쓰지 않게 한다."""

    mode: str
    reason_code: str
    #: 재확인을 통과한 장애물인지 (§10.2). state payload 에는 실리지 않는다.
    obstacle_confirmed: bool
    mission_id: str
    mission_state: str
    zone_id: str
    mission_elapsed_sec: int
    mission_incident_count: int
    mission_normal_detection_count: int
    battery_valid: bool
    battery_percent: float
    battery_state: str
    #: telemetry 에만 실린다 (§6.3). state·event 는 이 값을 싣지 않는다.
    battery_source: str
    #: ((name, value), ...) — dict 와 달리 그대로 비교·해시할 수 있다.
    health: tuple

    @property
    def health_block(self) -> dict:
        return dict(self.health)

    @property
    def publish_key(self) -> tuple:
        """재발행 판정 키 (§5.1 (b)).

        battery.percent 는 뺀다 — 소수점이 끊임없이 흔들려서 넣으면 30초 주기가
        사실상 무의미해진다. 계약이 요구하는 전이 신호는 battery.state 다.
        battery.source·mission 집계도 뺀다 — state payload 에 없는 값이라
        여기에 넣으면 state 가 자기와 무관한 이유로 재발행된다.
        """
        return (
            self.mode,
            self.reason_code,
            self.mission_id,
            self.mission_state,
            self.battery_valid,
            self.battery_state,
            self.health,
        )


# --------------------------------------------------------------------------
# FSM
# --------------------------------------------------------------------------


class RobotState:
    """mode·reason_code·mission·battery·health 의 단일 진실.

    mode 와 reason_code 는 항상 짝으로 바뀐다. 직접 대입할 수 없고
    전이 메서드로만 바뀐다 — 유효하지 않은 짝을 만들 수 없게 하기 위해서다.
    모든 메서드는 락 안에서 돈다: on_connected 훅이 paho 네트워크 스레드에서
    스냅샷을 읽는 동안 메인 루프가 전이할 수 있다.
    """

    def __init__(
        self,
        *,
        health_probes: dict = None,
        outbox=None,
        storage_dir=None,
        battery_thresholds: BatteryThresholds = None,
        monotonic=time.monotonic,
    ) -> None:
        probes = dict(health_probes or {})
        unknown = set(probes) - set(HEALTH_MEMBERS)
        if unknown:
            raise ValueError(f"health probe: unknown member {sorted(unknown)}")
        if "storage" in probes:
            # storage 는 하드웨어 모듈이 없다. 여기서 파일시스템·Outbox 로 판정한다.
            raise ValueError("health.storage is judged here, not by a probe")
        self._probes = probes
        self._outbox = outbox
        self._storage_dir = None if storage_dir is None else str(storage_dir)
        self._monotonic = monotonic
        self._health_cache = None  # (checked_at, members)
        self._storage_cache = None  # (checked_at, verdict)
        self._battery_thresholds = battery_thresholds or BatteryThresholds()

        self._lock = threading.RLock()
        self._mode = "BOOTING"
        self._reason_code = "NONE"
        # 재확인을 통과한 장애물인지 (§10.2). 확정 전에는 events/safety 가 나가지 않는다.
        self._obstacle_confirmed = False
        # 미션 보관함 — 부르는 것은 START_MISSION 을 처리하는 R-5 다.
        # 다섯 값은 start_mission()·end_mission() 으로만 함께 움직인다 (§6.3).
        self._mission_id = None
        self._mission_state = None
        self._zone_id = None
        self._mission_started_at = None
        self._mission_incident_count = None
        self._mission_normal_detection_count = None
        # 배터리 측정 경로가 붙기 전이다. 이전 값·더미 값을 만들지 않는다 (§5.5).
        self._battery_valid = False
        self._battery_percent = None
        self._battery_state = "UNKNOWN"
        self._battery_source = None
        self._battery_candidate = None  # 아직 확정 안 된 등급 후보
        self._battery_candidate_count = 0

    # -- 읽기 (대입 금지 — setter 를 두지 않는다) --------------------------

    @property
    def mode(self) -> str:
        return self._mode

    @property
    def reason_code(self) -> str:
        return self._reason_code

    @property
    def mission_id(self):
        return self._mission_id

    @property
    def mission_state(self):
        return self._mission_state

    @property
    def zone_id(self):
        return self._zone_id

    @property
    def mission_elapsed_sec(self):
        """미션 시작 후 경과 (§6.3). 활성 미션이 없으면 None.

        저장하지 않고 계산한다 — 아무도 주기적으로 갱신하지 않아도 값이 맞는다.
        일시정지 중에도 계속 센다 — 멈춤 여부는 R-5 결정이다.
        """
        with self._lock:
            if self._mission_started_at is None:
                return None
            return int(self._monotonic() - self._mission_started_at)

    @property
    def mission_incident_count(self):
        return self._mission_incident_count

    @property
    def mission_normal_detection_count(self):
        return self._mission_normal_detection_count

    @property
    def obstacle_confirmed(self) -> bool:
        return self._obstacle_confirmed

    @property
    def battery_thresholds(self) -> BatteryThresholds:
        return self._battery_thresholds

    def snapshot(self) -> Snapshot:
        # health 는 FSM 필드를 읽지 않는다. 락 밖에서 먼저 모아 두면 느린 장치
        # 검사가 메인 루프의 전이를 막지 않는다.
        members = tuple(self.health().items())
        with self._lock:
            return Snapshot(
                mode=self._mode,
                reason_code=self._reason_code,
                obstacle_confirmed=self._obstacle_confirmed,
                mission_id=self._mission_id,
                mission_state=self._mission_state,
                zone_id=self._zone_id,
                mission_elapsed_sec=self.mission_elapsed_sec,
                mission_incident_count=self._mission_incident_count,
                mission_normal_detection_count=self._mission_normal_detection_count,
                battery_valid=self._battery_valid,
                battery_percent=self._battery_percent,
                battery_state=self._battery_state,
                battery_source=self._battery_source,
                health=members,
            )

    # -- 전이 (계약 §5.4) -------------------------------------------------

    def _transition(self, mode: str, reason_code: str) -> bool:
        """짝을 검사하고 바꾼다. 바뀐 게 없으면 False — 중복 발행을 막는다."""
        _check_pair(mode, reason_code)
        with self._lock:
            if not _can_enter(self._mode, mode):
                raise InvalidTransition(f"{self._mode} -> {mode} is not allowed")
            if (mode, reason_code) == (self._mode, self._reason_code):
                return False
            log.info(
                "state %s/%s -> %s/%s",
                self._mode,
                self._reason_code,
                mode,
                reason_code,
            )
            self._mode = mode
            self._reason_code = reason_code
            return True

    def boot_completed(self) -> bool:
        """초기화 끝 — BOOTING → READY.  [F-001]"""
        return self._transition("READY", "NONE")

    def start_patrol(self) -> bool:
        """순찰 시작 — → PATROLLING/NONE."""
        return self._transition("PATROLLING", "NONE")

    def pause(self, reason: str = "REMOTE_PAUSE") -> bool:
        return self._transition("PAUSED", reason)

    def resume(self) -> bool:
        """일시정지·안전정지에서 순찰 복귀 — → PATROLLING/NONE."""
        return self._transition("PATROLLING", "NONE")

    def stand_by(self) -> bool:
        """미션이 끝나 대기로 돌아간다 — → READY/NONE.  [R-5 가 부른다]"""
        return self._transition("READY", "NONE")

    def safe_stop(self, reason: str) -> bool:
        return self._transition("SAFE_STOP", reason)

    def release_safe_stop(self) -> bool:
        """안전 정지 해제 — 미션 상태에 맞는 운행 mode 로 돌아간다.

        정지 사유가 풀렸을 때 어디로 돌아갈지는 미션이 정한다. 호출자마다 이 판단을
        따로 하면 원격 해제와 센서 복구가 서로 다른 mode 로 돌아가게 된다.
        """
        with self._lock:
            if self._mode != "SAFE_STOP":
                return False
            if self._mission_state == "RUNNING":
                return self.resume()
            if self._mission_state == "PAUSED":
                return self.pause()
            return self.stand_by()

    def fault(self, reason: str) -> bool:
        return self._transition("ERROR", reason)

    def begin_shutdown(self, reason: str = "NONE") -> bool:
        return self._transition("SHUTTING_DOWN", reason)

    # -- 장애물 (계약 §5.6, §10.2) ----------------------------------------
    #
    # 짧은 정지는 계약상 State 로만 알린다. events/safety 는 재확인을 통과한 뒤에만
    # 나가므로, "확정됐는가" 를 여기에 둔다 — SAFETY_CLEARED 가 SAFETY_STOPPED 없이
    # 나가는 경로를 구조로 막는다.

    def obstacle_detected(self) -> bool:
        """최초 감지 — → SAFE_STOP/OBSTACLE. 전이가 없었으면 False.

        이미 서 있으면 사유를 덮어쓰지 않는다. 운영자가 누른 REMOTE_ESTOP 이
        OBSTACLE 로 바뀌면 그 정지가 기록에서 지워지고, 장애물이 치워졌다는 이유로
        자동 재개 대상이 된다. 전이 표가 SAFE_STOP 으로 보내 주는 mode 에서만 선다.
        """
        with self._lock:
            if "SAFE_STOP" not in _TRANSITIONS[self._mode]:
                return False
            changed = self.safe_stop("OBSTACLE")
            if changed:
                # 새 사건이다. 재확인 전이라 아직 확정이 아니다.
                self._obstacle_confirmed = False
            return changed

    def confirm_obstacle(self) -> bool:
        """재확인에서도 있더라 — 확정. 이미 확정이면 False.

        반복 발행 금지(§10.2)를 여기서 지킨다: 확정은 사건당 한 번만 True 다.
        """
        with self._lock:
            if (self._mode, self._reason_code) != ("SAFE_STOP", "OBSTACLE"):
                return False
            if self._obstacle_confirmed:
                return False
            self._obstacle_confirmed = True
            return True

    def obstacle_cleared(self) -> bool:
        """해제 — SAFE_STOP/OBSTACLE 였을 때만 PATROLLING/NONE 으로 돌아간다.

        REMOTE_ESTOP 같은 다른 사유로 서 있는 중이면 장애물이 치워져도 못 움직인다.
        확정 여부는 여기서 지워지므로, 해제를 알릴지는 부르기 전에 읽어야 한다.
        """
        with self._lock:
            if (self._mode, self._reason_code) != ("SAFE_STOP", "OBSTACLE"):
                # 사유가 바뀌어 서 있는 동안은 확정 깃발을 그대로 둔다 — 다음
                # obstacle_detected() 가 리셋하고, 그 사이의 해제 발행은 이 False 가 막는다.
                return False
            resumed = self.resume()
            self._obstacle_confirmed = False
            return resumed

    # -- 미션 보관함 (계약 §6.3) ------------------------------------------
    #
    # mission_id 가 있으면 telemetry 는 state·elapsed_sec·incident_count 를 전부
    # 실어야 한다. 네 값을 따로 대입하면 한쪽만 채워진 순간 factory 가 표본을 거부하고
    # telemetry 가 조용히 멈춘다 — 그래서 mode·reason_code 처럼 메서드로만 바뀐다.

    def start_mission(self, mission_id: str, *, zone_id=None, state="RUNNING") -> None:
        """미션 시작 — 보관함 다섯 값을 한 번에 채운다.  [R-5 가 부른다]

        mode 는 건드리지 않는다. PATROLLING 으로 넘기는 것은 start_patrol() 이다.
        """
        if not mission_id:
            raise ValueError("mission_id is required")
        if state not in MISSION_STATES:
            raise ValueError(
                f"mission.state: {state!r} is not one of {sorted(MISSION_STATES)}"
            )
        with self._lock:
            self._mission_id = mission_id
            self._mission_state = state
            self._zone_id = zone_id
            self._mission_started_at = self._monotonic()
            self._mission_incident_count = 0
            self._mission_normal_detection_count = 0
        # 락 밖에서 쓴다 — 디스크 I/O 가 상태 락을 잡고 있으면 paho 네트워크
        # 스레드의 snapshot() 이 그동안 막힌다.
        self._persist_mission()

    def set_mission_state(self, state: str) -> None:
        if state not in MISSION_STATES:
            raise ValueError(
                f"mission.state: {state!r} is not one of {sorted(MISSION_STATES)}"
            )
        with self._lock:
            self._require_mission()
            self._mission_state = state
        self._persist_mission()

    def record_incident(self) -> int:
        """확정 Incident 1건. telemetry.mission.incident_count 가 여기서 온다.

        발행한 event 만 센다 — 탐지했지만 발행하지 않은 것을 세면
        mission.result.incident_count 와 실제 Event 수가 어긋난다.
        """
        with self._lock:
            self._require_mission()
            self._mission_incident_count += 1
            count = self._mission_incident_count
        # 집계도 사본에 반영한다 — 재시작 후 종료 event 의 result 가 실제 발행 수와
        # 어긋나면 서버 통계가 조용히 틀어진다.
        self._persist_mission()
        return count

    def record_normal_detection(self) -> int:
        """정상 관측 1건 (§11.3). mission.result.normal_detection_count 가 여기서 온다.

        카메라 프레임 수가 아니다 — tracking·cooldown 으로 중복을 제거한 뒤의 관측만
        들어온다. 중복 제거는 detect 몫이고 여기서는 세기만 한다.
        """
        with self._lock:
            self._require_mission()
            self._mission_normal_detection_count += 1
            count = self._mission_normal_detection_count
        self._persist_mission()
        return count

    def end_mission(self) -> None:
        """미션 종료 — 보관함을 비운다. mission·mission_id 가 다시 null 이 된다.

        집계도 함께 사라진다. mission 종료 event 의 result 는 이걸 부르기 전에 만든다.
        디스크 사본도 여기서 지운다 — 종료 event 를 만든 **뒤**에 부르는 자리이므로,
        이 시점이면 그 event 는 이미 Outbox 에 들어가 있다 (§7).
        """
        with self._lock:
            self._mission_id = None
            self._mission_state = None
            self._zone_id = None
            self._mission_started_at = None
            self._mission_incident_count = None
            self._mission_normal_detection_count = None
        self._forget_mission()

    # -- 미션 영속화 (§11 · 재시작 복구) -----------------------------------

    def _mission_file(self):
        """미션 보관함 사본 경로. storage_dir 가 없으면 영속화도 없다."""
        if self._storage_dir is None:
            return None
        return Path(self._storage_dir) / MISSION_FILE_NAME

    def _persist_mission(self) -> None:
        """미션 보관함을 디스크에 남긴다 — 원자적 교체.

        재시작하면 monotonic 시계가 0 부터 다시 시작하므로 경과시간을 그대로 저장할
        수 없다. 벽시계 시작 시각을 적고 복구할 때 monotonic 을 되맞춘다.
        실패해도 예외를 올리지 않는다 — 사본을 못 남기는 것이 미션을 못 받는 이유가
        되면 안 된다. 그때는 재시작 복구만 사라진다.
        """
        path = self._mission_file()
        if path is None:
            return
        with self._lock:
            if self._mission_id is None:
                return
            elapsed = self._monotonic() - self._mission_started_at
            box = {
                "mission_id": self._mission_id,
                "mission_state": self._mission_state,
                "zone_id": self._zone_id,
                "started_at": to_iso(_utc_now() - timedelta(seconds=elapsed)),
                "incident_count": self._mission_incident_count,
                "normal_detection_count": self._mission_normal_detection_count,
            }
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix(".tmp")
            tmp.write_text(json.dumps(box, ensure_ascii=False), encoding="utf-8")
            os.replace(tmp, path)      # 반쯤 쓰인 파일을 읽는 창을 없앤다
        except OSError:
            log.warning("미션 사본을 남기지 못했다 (%s) — 재시작 복구가 안 된다",
                        path, exc_info=True)

    def _forget_mission(self) -> None:
        path = self._mission_file()
        if path is None:
            return
        try:
            path.unlink(missing_ok=True)
        except OSError:
            log.warning("미션 사본을 지우지 못했다: %s", path, exc_info=True)

    def take_orphan_mission(self) -> bool:
        """지난 프로세스가 남긴 미션을 보관함에 되살린다. 되살렸으면 True.

        **주행을 재개하지 않는다.** 로봇이 어디까지 순찰했는지 모르는 채로 바퀴가
        다시 도는 것은 사람 없이 출발하는 일이고 명세가 금지한다. 되살리는 것은
        종료 event 를 제대로 만들기 위해서다 — 호출부(runtime)가 곧바로
        `MISSION_FAILED` 를 발행해 서버의 고아 미션을 닫는다.

        경과시간은 벽시계 기준으로 이어진다. 파일이 없거나 깨졌으면 False 다 —
        복구 실패로 기동을 막지 않는다.
        """
        path = self._mission_file()
        if path is None or not path.is_file():
            return False
        try:
            box = json.loads(path.read_text(encoding="utf-8"))
            mission_id = box["mission_id"]
            started_at = datetime.fromisoformat(box["started_at"].replace("Z", "+00:00"))
        except (OSError, ValueError, KeyError, TypeError):
            log.warning("미션 사본을 읽을 수 없다 (%s) — 버린다", path, exc_info=True)
            self._forget_mission()
            return False
        elapsed = max(0.0, (_utc_now() - started_at).total_seconds())
        with self._lock:
            if self._mission_id is not None:
                # 이미 활성 미션이 있으면 덮지 않는다 — 새 명령이 먼저 도착한 경우다.
                return False
            self._mission_id = mission_id
            self._mission_state = box.get("mission_state") or "RUNNING"
            self._zone_id = box.get("zone_id")
            self._mission_started_at = self._monotonic() - elapsed
            self._mission_incident_count = int(box.get("incident_count") or 0)
            self._mission_normal_detection_count = int(
                box.get("normal_detection_count") or 0)
        log.warning("지난 실행의 미션을 발견했다 (%s, 경과 %.0f초) — 재개하지 않고 종료 보고한다",
                    mission_id, elapsed)
        return True

    def _require_mission(self) -> None:
        if self._mission_id is None:
            raise ValueError("no active mission; call start_mission() first")

    # -- 배터리 -----------------------------------------------------------

    def set_battery(self, *, valid: bool, percent=None, source: str = "MEASURED") -> bool:
        """센서 원시값 갱신. 등급은 여기서 판정한다.

        state 와 telemetry 가 둘 다 배터리를 싣는다. 각자 센서를 읽으면 같은 시점에
        서로 다른 잔량을 보고하게 되므로, 읽기는 여기로만 들어오고 두 발행 경로는
        스냅샷에서 읽는다. 등급(NORMAL·LOW·CRITICAL)도 마찬가지다 — 호출자가 주면
        같은 잔량을 두고 state·telemetry·event 가 서로 다른 등급을 말할 수 있다.
        `source` 는 telemetry 에만 실린다 (§6.3). 추정으로 채운 값이면 호출자가
        ESTIMATED 를 명시해야 한다 — 기본값은 측정 성공을 뜻하는 MEASURED 다.
        """
        valid = bool(valid)
        if valid:
            if percent is None:
                raise ValueError("battery: valid=True requires percent")
            if not 0.0 <= percent <= 100.0:
                raise ValueError(f"battery.percent: {percent} out of range 0.0~100.0")
            if source not in BATTERY_SOURCES:
                raise ValueError(
                    f"battery.source: {source!r} is not one of {sorted(BATTERY_SOURCES)}"
                )
            percent = float(percent)
        else:
            # 못 믿는 측정값은 이전 값을 복사하지 않고 버린다 (§5.5).
            percent = None
            source = None
        with self._lock:
            before = (
                self._battery_valid,
                self._battery_percent,
                self._battery_state,
                self._battery_source,
            )
            state = self._commit_grade("UNKNOWN" if not valid else self._graded(percent))
            self._battery_valid = valid
            self._battery_percent = percent
            self._battery_state = state
            self._battery_source = source
            return before != (valid, percent, state, source)

    def _graded(self, percent: float) -> str:
        """히스테리시스 등급 판정 (계약 §9.3).

        내려갈 때는 low_pct·critical_pct 를, 올라올 때는 *_recover_pct 를 쓴다.
        """
        limits = self._battery_thresholds
        current = self._battery_state
        if current == "CRITICAL":
            if percent >= limits.low_recover_pct:
                return "NORMAL"
            return "LOW" if percent >= limits.critical_recover_pct else "CRITICAL"
        if current == "LOW":
            if percent < limits.critical_pct:
                return "CRITICAL"
            return "NORMAL" if percent >= limits.low_recover_pct else "LOW"
        if percent < limits.critical_pct:
            return "CRITICAL"
        return "LOW" if percent < limits.low_pct else "NORMAL"

    def _commit_grade(self, candidate: str) -> str:
        """confirm_samples 만큼 연속으로 같은 등급이어야 전이로 인정한다.

        측정 무효(UNKNOWN)와 기동 후 첫 판정은 바로 반영한다 — 흔들림이 아니라
        등급이 없다가 생긴(혹은 사라진) 것이다.
        """
        current = self._battery_state
        if candidate == current:
            self._battery_candidate = None
            self._battery_candidate_count = 0
            return current
        if candidate == "UNKNOWN" or current == "UNKNOWN":
            self._battery_candidate = None
            self._battery_candidate_count = 0
            return candidate
        if candidate == self._battery_candidate:
            self._battery_candidate_count += 1
        else:
            self._battery_candidate = candidate
            self._battery_candidate_count = 1
        if self._battery_candidate_count < self._battery_thresholds.confirm_samples:
            return current
        self._battery_candidate = None
        self._battery_candidate_count = 0
        return candidate

    # -- health 취합 (계약 §5.3) ------------------------------------------

    def health(self) -> dict:
        """6종을 계약 순서로 모은다. 하나도 빠뜨리지 않는다 — 전부 필수 멤버다.

        HEALTH_PROBE_INTERVAL_SEC 동안은 직전 판정을 그대로 돌려준다. 대가는
        FAULT 감지가 최대 그만큼 늦는 것이고, 얻는 것은 장치를 초당 수십 번
        때리지 않는 것이다.
        """
        now = self._monotonic()
        cached = self._health_cache
        if cached is not None and now - cached[0] < HEALTH_PROBE_INTERVAL_SEC:
            return dict(cached[1])
        members = {}
        for name in HEALTH_MEMBERS:
            members[name] = (
                self._storage_health() if name == "storage" else self._probe(name)
            )
        self._health_cache = (now, members)
        return dict(members)

    def _probe(self, name: str) -> str:
        probe = self._probes.get(name)
        if probe is None:
            return "UNKNOWN"  # 붙이지 않은 장치 = 검사 전
        try:
            value = probe()
        except Exception:
            # 검사를 했는데 실패한 것이므로 UNKNOWN 이 아니라 FAULT 다.
            log.exception("health.%s probe raised", name)
            return "FAULT"
        if value not in HEALTH_STATES:
            log.error("health.%s: %r is not a health enum", name, value)
            return "FAULT"
        return value

    def _storage_health(self) -> str:
        if self._storage_dir is None and self._outbox is None:
            return "UNKNOWN"  # 판정 근거가 없다
        now = self._monotonic()
        cached = self._storage_cache
        if cached is not None and now - cached[0] < STORAGE_PROBE_INTERVAL_SEC:
            return cached[1]
        verdict = self._probe_storage()
        self._storage_cache = (now, verdict)
        return verdict

    def _probe_storage(self) -> str:
        if self._storage_dir is not None and not _writable(self._storage_dir):
            return "FAULT"
        if self._outbox is not None:
            try:
                pending = self._outbox.pending_count()
            except Exception:
                log.exception("outbox pending_count failed")
                return "FAULT"
            if pending >= STORAGE_PENDING_DEGRADED:
                return "DEGRADED"
        return "OK"


# --------------------------------------------------------------------------
# 발행 타이밍 (계약 §5.1)
# --------------------------------------------------------------------------

#: 변화가 없어도 이 주기로 한 번은 발행한다.
STATE_INTERVAL_SEC = 30.0


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class StatePublisher:
    """state 를 언제 보낼지 정한다 — 연결 직후 1회 / 전이 즉시 / 30초 주기.

    payload 는 factory.state() 가 만든다. 여기서는 값만 넘긴다.
    전이로 발행하면 30초 타이머를 리셋해서 같은 상태를 두 번 보내지 않는다.
    락은 on_connected(paho 네트워크 스레드)와 poll(메인 루프)이 겹칠 때
    updated_at 이 뒤엉키거나 같은 상태가 두 번 나가는 것을 막는다.
    """

    def __init__(
        self,
        client,
        state: RobotState,
        *,
        interval_sec: float = STATE_INTERVAL_SEC,
        monotonic=time.monotonic,
        now=_utc_now,
    ) -> None:
        self._client = client
        self._state = state
        self._interval = interval_sec
        self._monotonic = monotonic
        self._now = now
        self._lock = threading.Lock()
        self._last_key = None
        self._last_at = None  # 직전 updated_at (밀리초 절단 datetime)
        self._next_due = None  # monotonic 기한. None 이면 아직 한 번도 안 보냈다.

    # -- 훅 ---------------------------------------------------------------

    def on_connected(self) -> None:
        """MqttClient 가 접속 직후 부른다 (§5.1 (a)). 재접속 때마다 다시 불린다."""
        try:
            self.publish(force=True)
        except Exception:
            # 네트워크 스레드에서 불린다 — 예외가 올라가면 paho 루프가 죽는다.
            log.exception("failed to publish state on connect")

    def poll(self) -> bool:
        """메인 루프가 매 tick 부른다. 전이가 있거나 주기가 됐으면 발행."""
        return self.publish(force=False)

    # -- 발행 -------------------------------------------------------------

    def publish(self, *, force: bool = False, wait_sec: float = None) -> bool:
        with self._lock:
            snapshot = self._state.snapshot()
            due = self._next_due is None or self._monotonic() >= self._next_due
            if not force and not due and snapshot.publish_key == self._last_key:
                return False
            payload = self._client.messages.state(
                mode=snapshot.mode,
                reason_code=snapshot.reason_code,
                health_block=snapshot.health_block,
                battery_valid=snapshot.battery_valid,
                battery_percent=snapshot.battery_percent,
                battery_state=snapshot.battery_state,
                mission_id=snapshot.mission_id,
                mission_state=snapshot.mission_state,
                updated_at=self._next_updated_at(),
            )
            self._last_key = snapshot.publish_key
            # 전이로 보냈든 주기로 보냈든 여기서 타이머를 다시 건다.
            self._next_due = self._monotonic() + self._interval
        # 락을 놓고 보낸다. on_connected 는 paho 네트워크 스레드에서 불리므로,
        # 락을 쥔 채 publish 하면 메인 루프의 발행이 네트워크 루프를 멈춰 세운다.
        self._client.publish(TopicKind.STATE, payload, wait_sec=wait_sec)
        return True

    def _next_updated_at(self) -> str:
        """단조 증가하는 updated_at.

        서버는 기존 updated_at 보다 새로운 경우에만 갱신한다(§3.4). to_iso 는
        밀리초 3자리라, 같은 밀리초에 두 번 발행하면 뒤엣것이 통째로 버려진다.
        직전 값과 같거나 이전이면 1ms 밀어서 순서를 지킨다.
        """
        stamp = self._now().astimezone(timezone.utc)
        stamp = stamp.replace(microsecond=(stamp.microsecond // 1000) * 1000)
        if self._last_at is not None and stamp <= self._last_at:
            stamp = self._last_at + timedelta(milliseconds=1)
        self._last_at = stamp
        return to_iso(stamp)
