"""commands/request 검증·실행과 commands/result 발행.  [F-007]

MqttClient 는 명령을 큐에 넣기만 한다 — 해석·검증·실행은 전부 여기서 한다.
payload 조립은 하지 않는다. 값만 모아 factory.command_result() 에 넘긴다.
⚠️ expires_at 판정이 로봇 시계에 의존한다. 시계가 밀리면 유효한 명령을 버리거나
   만료된 명령을 실행한다 — NTP 동기(systemd time-sync)가 전제다 (계약 §17).
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §12·§13
"""

from __future__ import annotations

import logging
import queue
from collections import deque
from datetime import datetime, timezone

from comms import TopicKind
from comms.codec import utc_now
from comms.contract import SCHEMA_VERSION

log = logging.getLogger(__name__)

#: 처리한 command_id 를 기억하는 개수. 재실행 금지는 boot 안에서만 지키면 된다 (§12.5).
HANDLED_COMMAND_MEMORY = 64

#: 명령별 필수 parameters (§12.5). RESUME_MISSION 은 빈 object 다.
REQUIRED_PARAMETERS = {
    "START_MISSION": ("mission_id", "zone_id"),
    "PAUSE_MISSION": ("reason",),
    "RESUME_MISSION": (),
    "CANCEL_MISSION": ("reason",),
    "EMERGENCY_STOP": ("reason",),
    "CLEAR_EMERGENCY_STOP": ("reason",),
}

#: v1 이름을 유지하되 MVP 에서는 발행하지 않는다 (§12.5). 와도 실행하지 않는다.
RESERVED_COMMAND_TYPES = frozenset({"RETURN_TO_CHARGE", "UPDATE_CONFIG"})

#: 정지를 먼저 하고 그 다음에 발행하는 명령 (계약 §16-10).
STOP_BEFORE_REPORTING = frozenset({"EMERGENCY_STOP"})

# commands/result 의 reason_code (§13.3).
REASON_OK = "OK"
REASON_INTERNAL_ERROR = "INTERNAL_ERROR"
REASON_NO_ACTIVE_MISSION = "NO_ACTIVE_MISSION"
REASON_ALREADY_IN_STATE = "ALREADY_IN_STATE"
REASON_SAFETY_NOT_CLEARED = "SAFETY_CONDITION_NOT_CLEARED"


def _as_datetime(stamp):
    """ISO 8601 → datetime. 읽을 수 없으면 None."""
    if not isinstance(stamp, str):
        return None
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


class CommandRunner:
    """명령 큐를 비우고 결과를 낸다.

    검증에서 떨어진 명령도 결과는 낸다 — 서버가 무응답과 거부를 구분하지 못하면
    관제 화면이 접수 상태에서 영원히 멈춘다.
    """

    def __init__(
        self, client, state, events, *, stop_motor=None, now=utc_now, memory=None
    ) -> None:
        self._client = client
        self._state = state
        self._events = events
        #: EMERGENCY_STOP 이 상태 전이보다 먼저 부르는 물리 정지 (§16-10).
        self._stop = stop_motor
        self._now = now
        #: 이미 처리한 command_id. 재전송돼도 다시 실행하지 않는다 (§12.5).
        self._handled = deque(maxlen=memory or HANDLED_COMMAND_MEMORY)
        self._handlers = {
            "START_MISSION": self._start_mission,
            "PAUSE_MISSION": self._pause_mission,
            "RESUME_MISSION": self._resume_mission,
            "CANCEL_MISSION": self._cancel_mission,
            "EMERGENCY_STOP": self._emergency_stop,
            "CLEAR_EMERGENCY_STOP": self._clear_emergency_stop,
        }

    # -- 큐 소비 ----------------------------------------------------------

    def poll(self) -> int:
        """메인 루프가 매 tick 부른다. 큐가 빌 때까지 처리하고 처리 건수를 돌려준다.

        tick 당 하나만 꺼내면 명령이 0.5초씩 밀린다 — EMERGENCY_STOP 은 그 지연을
        허용하지 않는다.
        """
        handled = 0
        while True:
            try:
                command = self._client.commands.get_nowait()
            except queue.Empty:
                return handled
            handled += 1
            try:
                self.handle(command)
            except Exception:
                # 명령 하나가 넘어져도 다음 명령과 메인 루프는 계속 간다.
                log.exception("command handling failed")

    # -- 검증 (계약 §12) --------------------------------------------------

    def handle(self, command) -> bool:
        """명령 하나를 검증·실행하고 결과를 낸다. 결과를 냈으면 True."""
        received_at = self._now()
        if not isinstance(command, dict):
            log.warning("dropping a command that is not an object")
            return False
        if command.get("schema_version") != SCHEMA_VERSION:
            # 해석할 수 없는 계약이라 command_id 조차 믿을 수 없다 (§3.4).
            log.warning(
                "dropping a command with schema_version=%r",
                command.get("schema_version"),
            )
            return False

        command_id = command.get("command_id")
        command_type = command.get("command_type")
        if not command_id:
            return self._reject(None, received_at, REASON_INTERNAL_ERROR)
        # 중복 판정을 검증보다 앞에 둔다 — 거부한 명령이 재전송돼도 조용해야 한다.
        if command_id in self._handled:
            log.info("ignoring an already handled command (command_id=%s)", command_id)
            return False
        self._handled.append(command_id)

        if command_type in RESERVED_COMMAND_TYPES or command_type not in self._handlers:
            # 예약 명령은 MVP 가 해석할 수 없는 명령과 같은 취급이다.
            return self._reject(command_id, received_at, REASON_INTERNAL_ERROR)
        expires_at = _as_datetime(command.get("expires_at"))
        if expires_at is not None and _as_datetime(received_at) > expires_at:
            # 서버가 만료시각을 안 보냈으면 만료를 우리가 만들어내지 않는다.
            return self._finish(command_id, received_at, "EXPIRED", REASON_OK)
        parameters = command.get("parameters")
        if not isinstance(parameters, dict) or any(
            parameters.get(name) is None for name in REQUIRED_PARAMETERS[command_type]
        ):
            return self._reject(command_id, received_at, REASON_INTERNAL_ERROR)

        return self._run(command_id, command_type, parameters, received_at)

    # -- 실행 -------------------------------------------------------------

    def _run(self, command_id, command_type, parameters, received_at) -> bool:
        stop_first = command_type in STOP_BEFORE_REPORTING
        if not stop_first:
            # 접수를 먼저 알린다 (§13.3). completed_at 은 null 이다.
            self._publish("ACCEPTED", command_id, received_at, REASON_OK)
        try:
            reason_code = self._handlers[command_type](parameters)
        except Exception:
            log.exception("%s failed", command_type)
            status, reason_code = "FAILED", REASON_INTERNAL_ERROR
        else:
            status = "COMPLETED" if reason_code == REASON_OK else "REJECTED"
        if stop_first:
            # 정지를 마친 뒤에야 소켓을 만진다 (§16-10).
            self._publish("ACCEPTED", command_id, received_at, REASON_OK)
        return self._finish(command_id, received_at, status, reason_code)

    def _start_mission(self, parameters) -> str:
        snapshot = self._state.snapshot()
        if snapshot.mission_id is not None:
            return REASON_ALREADY_IN_STATE
        if snapshot.mode == "SAFE_STOP":
            # 전이표는 SAFE_STOP → PATROLLING 을 허용한다. 명령 하나로 안전 정지를
            # 지나쳐 주행이 재개되면 안 된다 — 해제는 CLEAR_EMERGENCY_STOP 이다.
            return REASON_SAFETY_NOT_CLEARED
        # 주행을 먼저 연다 — 여기서 막히면 보관함을 건드리지 않은 채 끝난다.
        self._state.start_patrol()
        self._state.start_mission(
            parameters["mission_id"], zone_id=parameters["zone_id"]
        )
        self._events.mission_started()
        return REASON_OK

    def _pause_mission(self, parameters) -> str:
        if self._state.mission_id is None:
            return REASON_NO_ACTIVE_MISSION
        if not self._state.pause():
            return REASON_ALREADY_IN_STATE
        self._events.mission_paused()
        return REASON_OK

    def _resume_mission(self, parameters) -> str:
        snapshot = self._state.snapshot()
        if snapshot.mission_id is None:
            return REASON_NO_ACTIVE_MISSION
        if snapshot.mode == "SAFE_STOP":
            # 미션 명령으로 안전 정지를 풀지 않는다 — 그건 CLEAR_EMERGENCY_STOP 이다.
            return REASON_SAFETY_NOT_CLEARED
        if not self._state.resume():
            return REASON_ALREADY_IN_STATE
        self._events.mission_resumed()
        return REASON_OK

    def _cancel_mission(self, parameters) -> str:
        snapshot = self._state.snapshot()
        if snapshot.mission_id is None:
            return REASON_NO_ACTIVE_MISSION
        # 집계를 실어 보내고 보관함까지 비우는 것은 events 가 한다.
        self._events.mission_cancelled(parameters["reason"])
        if snapshot.mode != "SAFE_STOP":
            # 서 있는 중이면 선 채로 미션만 끝낸다. 해제는 CLEAR_EMERGENCY_STOP 이다.
            self._state.stand_by()
        return REASON_OK

    def _emergency_stop(self, parameters) -> str:
        """미션 상태는 건드리지 않는다 — 미션 전이는 명시적 미션 명령으로만 한다."""
        # 바퀴부터 세우고 전이·발행을 한다 (§16-10). 정지 실패가 명령을 삼키면 안 되므로
        # 예외는 여기서 막고 상태 전이는 그대로 간다.
        if self._stop is not None:
            try:
                self._stop()
            except Exception:
                log.exception("failed to stop the motor")
        if not self._state.safe_stop("REMOTE_ESTOP"):
            return REASON_ALREADY_IN_STATE
        return REASON_OK

    def _clear_emergency_stop(self, parameters) -> str:
        snapshot = self._state.snapshot()
        if (snapshot.mode, snapshot.reason_code) != ("SAFE_STOP", "REMOTE_ESTOP"):
            # 정지 사유가 여러 개일 수 있고 관리자는 그중 하나만 해제했다.
            return REASON_SAFETY_NOT_CLEARED
        # 어디로 돌아갈지는 미션이 정한다 — 그 판단은 RobotState 안에 하나만 둔다.
        self._state.release_safe_stop()
        return REASON_OK

    # -- 결과 발행 (계약 §13) ---------------------------------------------

    def _reject(self, command_id, received_at, reason_code) -> bool:
        """접수한 적이 없으므로 최종 결과 하나만 낸다."""
        return self._finish(command_id, received_at, "REJECTED", reason_code)

    def _finish(self, command_id, received_at, status, reason_code) -> bool:
        self._publish(status, command_id, received_at, reason_code, self._now())
        return True

    def _publish(
        self, status, command_id, received_at, reason_code, completed_at=None
    ) -> dict:
        # state 는 처리 후 값이다 — 전이가 끝난 뒤에 읽는다 (§13.3).
        after = self._state.snapshot()
        payload = self._client.messages.command_result(
            command_id=command_id,
            status=status,
            received_at=received_at,
            completed_at=completed_at,
            reason_code=reason_code,
            mode=after.mode,
            state_reason_code=after.reason_code,
        )
        self._client.publish(TopicKind.COMMAND_RESULT, payload)
        return payload
