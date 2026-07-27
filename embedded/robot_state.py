"""로봇 상태 FSM · health 취합 · state 발행 타이밍.  [F-007]

state 에 실리는 값의 판정은 전부 여기서 한다 — main.py 는 조립만 한다.
payload 조립은 하지 않는다. 값만 모아 factory.state() 에 넘긴다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §5
"""

from __future__ import annotations

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
    BATTERY_STATES,
    HEALTH_MEMBERS,
    HEALTH_STATES,
    MODES,
    REASON_CODES,
    SAFETY_REASON_CODES,
)

log = logging.getLogger(__name__)


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
# health.storage 판정 (계약 §5.3)
# --------------------------------------------------------------------------

#: Outbox 에 이만큼 쌓이면 저장 계층이 정상 속도로 비워지지 않는다고 본다.
STORAGE_PENDING_DEGRADED = 100
#: 파일 쓰기 검사 주기. 매 tick 마다 디스크를 건드리지 않기 위한 것이다.
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
    mission_id: str
    mission_state: str
    zone_id: str
    battery_valid: bool
    battery_percent: float
    battery_state: str
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
        self._storage_cache = None  # (checked_at, verdict)

        self._lock = threading.RLock()
        self._mode = "BOOTING"
        self._reason_code = "NONE"
        # 미션 보관함 — 채우는 것은 START_MISSION 을 처리하는 R-5 다.
        self._mission_id = None
        self._mission_state = None
        self._zone_id = None
        # 배터리 측정 경로가 붙기 전이다. 이전 값·더미 값을 만들지 않는다 (§5.5).
        self._battery_valid = False
        self._battery_percent = None
        self._battery_state = "UNKNOWN"

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

    def snapshot(self) -> Snapshot:
        # health 는 FSM 필드를 읽지 않는다. 락 밖에서 먼저 모아 두면 느린 장치
        # 검사가 메인 루프의 전이를 막지 않는다.
        members = tuple(self.health().items())
        with self._lock:
            return Snapshot(
                mode=self._mode,
                reason_code=self._reason_code,
                mission_id=self._mission_id,
                mission_state=self._mission_state,
                zone_id=self._zone_id,
                battery_valid=self._battery_valid,
                battery_percent=self._battery_percent,
                battery_state=self._battery_state,
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

    def safe_stop(self, reason: str) -> bool:
        return self._transition("SAFE_STOP", reason)

    def fault(self, reason: str) -> bool:
        return self._transition("ERROR", reason)

    def begin_shutdown(self, reason: str = "NONE") -> bool:
        return self._transition("SHUTTING_DOWN", reason)

    # -- 장애물 (계약 §5.6) -----------------------------------------------
    #
    # State 쪽만 구현한다. 재확인 후 지속된 정지를 events/safety 로 올리는 것은
    # R-4 이고, 짧은 정지는 계약상 State 로만 알린다.

    def obstacle_detected(self) -> bool:
        """최초 감지 — → SAFE_STOP/OBSTACLE. 이미 그 상태면 아무 것도 안 한다."""
        return self.safe_stop("OBSTACLE")

    def obstacle_cleared(self) -> bool:
        """해제 — SAFE_STOP/OBSTACLE 였을 때만 PATROLLING/NONE 으로 돌아간다.

        REMOTE_ESTOP 같은 다른 사유로 서 있는 중이면 장애물이 치워져도 못 움직인다.
        """
        with self._lock:
            if (self._mode, self._reason_code) != ("SAFE_STOP", "OBSTACLE"):
                return False
            return self.resume()

    # -- 배터리 -----------------------------------------------------------

    def set_battery(self, *, valid: bool, percent=None, state: str = "UNKNOWN") -> bool:
        """측정값 갱신. 값 검사를 여기서 해서 발행 시점에 터지지 않게 한다."""
        if state not in BATTERY_STATES:
            raise ValueError(
                f"battery.state: {state!r} is not one of {sorted(BATTERY_STATES)}"
            )
        valid = bool(valid)
        if valid:
            if percent is None:
                raise ValueError("battery: valid=True requires percent")
            if not 0.0 <= percent <= 100.0:
                raise ValueError(f"battery.percent: {percent} out of range 0.0~100.0")
            percent = float(percent)
        else:
            # 못 믿는 측정값은 이전 값을 복사하지 않고 버린다 (§5.5).
            percent = None
            state = "UNKNOWN"
        with self._lock:
            before = (self._battery_valid, self._battery_percent, self._battery_state)
            self._battery_valid = valid
            self._battery_percent = percent
            self._battery_state = state
            return before != (valid, percent, state)

    # -- health 취합 (계약 §5.3) ------------------------------------------

    def health(self) -> dict:
        """6종을 계약 순서로 모은다. 하나도 빠뜨리지 않는다 — 전부 필수 멤버다."""
        members = {}
        for name in HEALTH_MEMBERS:
            members[name] = (
                self._storage_health() if name == "storage" else self._probe(name)
            )
        return members

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
