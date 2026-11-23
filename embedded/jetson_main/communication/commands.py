"""MQTT 명령을 주행 FSM 요청으로 바꾸고, 실제 적용 후 result를 보낸다.

Paho 네트워크 스레드는 MqttClient.commands 큐에 payload만 넣는다.
이 클래스의 poll_before_step()/poll_after_step()은 모두 제어 메인 루프에서
호출되므로 모터·FSM의 single-writer 규칙을 깨지 않는다.

핵심 계약:
  - 일반 명령은 검증 후 ACCEPTED, 실제 DriveState 반영 후 COMPLETED.
  - EMERGENCY_STOP은 실제 브레이크 명령이 적용된 후 ACCEPTED/COMPLETED.
  - 통신 RobotState는 서버 투영이지 모터 제어권자가 아니다.
"""

from __future__ import annotations

import logging
import queue
import re
from collections import OrderedDict, deque
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from comms import TopicKind
from comms.codec import utc_now
from comms.contract import SCHEMA_VERSION, ZONE_ID_PATTERN
from driving.states import PAUSABLE_STATES, DriveState

log = logging.getLogger(__name__)

HANDLED_COMMAND_MEMORY = 64
#: 대시보드가 보여줄 최근 명령 수. 사람이 훑는 용도라 짧게 둔다.
RECENT_RESULT_MEMORY = 20
REQUIRED_PARAMETERS = {
    "START_MISSION": ("mission_id", "zone_id"),
    "PAUSE_MISSION": ("reason",),
    "RESUME_MISSION": (),
    "COMPLETE_MISSION": (),
    "CANCEL_MISSION": ("reason",),
    "EMERGENCY_STOP": ("reason",),
    "CLEAR_EMERGENCY_STOP": ("reason",),
}
RESERVED_COMMAND_TYPES = frozenset({"RETURN_TO_CHARGE", "UPDATE_CONFIG"})
STOP_BEFORE_REPORTING = frozenset({"COMPLETE_MISSION", "EMERGENCY_STOP"})

#: 조건을 따지지 않고 상태를 맞추는 명령 — 전제조건·만료·대기 충돌로 거부하지 않는다.
#:
#: 취소를 거부하면 서버 미션이 RUNNING 으로 남고, 그 상태로는 다음 START_MISSION 이
#: 막힌다. 고아 미션(robot_state.take_orphan_mission)은 재기동이 풀어 주지만 이쪽은
#: 사람이 취소를 다시 눌러도 같은 이유로 또 거부되므로 스스로 풀리지 않는다.
#: 거부할 이유였던 것들은 전부 '이미 취소된 것과 같은 상태' 이기도 하다 —
#: 미션이 없으면 닫을 것이 없고, ESTOP 이면 이미 서 있다. 따라서 거부보다 수렴이 맞다.
#:
#: 만료(expires_at)도 보지 않는다: 로봇이 끊긴 사이 브로커가 물고 있던 취소가 30초를
#: 넘겨 도착하는 것은 오작동이 아니라 정상 경로다. 늦게 온 취소가 **다른** 미션을
#: 죽이는 사고는 _commit_state 가 parameters.mission_id 를 보고 막는다.
UNCONDITIONAL_COMMAND_TYPES = frozenset({"CANCEL_MISSION"})

REASON_OK = "OK"
REASON_INTERNAL_ERROR = "INTERNAL_ERROR"
REASON_NO_ACTIVE_MISSION = "NO_ACTIVE_MISSION"
REASON_ALREADY_IN_STATE = "ALREADY_IN_STATE"
REASON_SAFETY_NOT_CLEARED = "SAFETY_CONDITION_NOT_CLEARED"
REASON_MISSION_MISMATCH = "MISSION_MISMATCH"
REASON_COMMAND_TTL_EXPIRED = "COMMAND_TTL_EXPIRED"
REASON_STOP_APPLICATION_FAILED = "STOP_APPLICATION_FAILED"
#: mission.result.completion_reason 에 싣는 값 — reason_code 와 다른 필드다.
REASON_OPERATOR_CANCELLED = "OPERATOR_CANCELLED"
COMPLETION_REASONS = frozenset({
    "PLANNED_DURATION_REACHED",
    "OPERATOR_COMPLETED",
})


def _as_datetime(stamp) -> Optional[datetime]:
    if not isinstance(stamp, str):
        return None
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


@dataclass(frozen=True)
class PendingCommand:
    command_id: str
    command_type: str
    parameters: dict
    received_at: str
    expires_at: Optional[datetime]
    stop_before_reporting: bool = False


class DriveCommandRunner:
    """commands/request 검증·FSM 요청·적용 확인·commands/result 발행."""

    def __init__(self, client, state, events, *, now=utc_now, memory=None,
                 start_interlock=None) -> None:
        self._client = client
        self._state = state
        self._events = events
        self._now = now
        #: 출발 인터록 (driving/start_interlock.py). 있으면 START_MISSION 은 곧바로
        #: 달리지 않고 로봇 몸체의 터치를 기다린다 — 관제가 누른 출발과 '로봇 앞에
        #: 사람이 있다'는 사실은 다른 정보다. 실제 request_start 는 메인 루프의
        #: interlock.poll() 이 낸다. 없으면 종전대로 즉시 요청한다.
        self._interlock = start_interlock
        self._memory = memory or HANDLED_COMMAND_MEMORY
        self._pending: "OrderedDict[str, PendingCommand]" = OrderedDict()
        self._handled: "OrderedDict[str, dict]" = OrderedDict()
        # 관제·대시보드용 최근 명령 로그. commands/result payload 에는 command_type 이
        # 없어서(계약이 command_id 로만 잇는다) _handled 로는 '무엇이 왔는지'를 알 수
        # 없다. 현장에서 '서버가 눌렀는데 로봇이 받았나'를 확인할 수단이 필요하다.
        self.recent_results: "deque[dict]" = deque(maxlen=RECENT_RESULT_MEMORY)

    @property
    def pending_count(self) -> int:
        return len(self._pending)

    def poll_before_step(self, machine) -> bool:
        """메인 루프 tick 전에 명령을 모두 요청하고, 즉시 정지 필요 여부를 돌려준다."""
        while True:
            try:
                command = self._client.commands.get_nowait()
            except queue.Empty:
                return any(self._urgent(pending, machine)
                           for pending in self._pending.values())
            try:
                self._accept(command, machine)
            except Exception:
                # 명령 하나가 잘못돼도 ESTOP 포함 뒤 명령을 계속 소비한다.
                log.exception("command handling failed")

    @staticmethod
    def _urgent(pending: PendingCommand, machine) -> bool:
        """이 대기 명령이 '인지를 기다리지 않는 즉시 정지'를 요구하는가.

        urgent 경로(main 루프)는 새 YOLO 추론을 건너뛰고 frame=None 으로 step 을
        돈다. 취소·완료·ESTOP 은 어느 상태에서든 그 tick 에 적용되므로 항상 urgent.

        일시정지는 **적용 가능한 상태에서만** urgent 다. ESCAPE(차도 이탈 선회)는
        PAUSABLE_STATES 에서 일부러 빠져 있는데(선회 중 브레이크 = 차도 위 정지),
        그 상태의 대기 일시정지까지 urgent 로 돌리면 pending 인 매 tick 이
        frame=None → 인지 두절 폴백 **브레이크**가 되어 로봇이 차도 위에 서고,
        인지를 안 읽으니 선회가 끝나지도 못해(핸들러 미도달 — timeout→ESTOP 도
        안 걸린다) 명령이 만료될 때까지 그대로다 — 뺀 설계를 정면으로 우회하는
        경로였다. 적용 불가 상태에서는 정상 경로(인지 포함)로 선회를 계속하고,
        poll_after_step 의 재요청이 선회가 끝난 첫 tick 에 일시정지를 적용시킨다.
        """
        if pending.command_type in {"COMPLETE_MISSION", "CANCEL_MISSION",
                                    "EMERGENCY_STOP"}:
            return True
        if pending.command_type != "PAUSE_MISSION":
            return False
        return machine.state in PAUSABLE_STATES

    def poll_after_step(self, machine) -> int:
        """해당 tick의 모터 명령 적용 후, 목표 DriveState에 닿은 result를 낸다."""
        completed = 0
        now_dt = _as_datetime(self._now())
        for command_id, pending in list(self._pending.items()):
            if (pending.command_type not in UNCONDITIONAL_COMMAND_TYPES
                    and pending.expires_at is not None and now_dt is not None
                    and now_dt > pending.expires_at):
                self._finish(pending, "EXPIRED", REASON_COMMAND_TTL_EXPIRED)
                completed += 1
                continue
            if (machine.state is DriveState.ESTOP
                    and pending.command_type in {
                        "START_MISSION", "PAUSE_MISSION",
                        "RESUME_MISSION", "COMPLETE_MISSION"
                    }):
                self._finish(pending, "REJECTED", REASON_SAFETY_NOT_CLEARED)
                completed += 1
                continue
            if not self._is_applied(pending.command_type, machine.state):
                # 일시정지는 차도 이탈 선회(ESCAPE) 중에 FSM이 그 tick에 버린다
                # (driving/states.py PAUSABLE_STATES — 선회 중 브레이크는 차도 위
                # 정지다). 요청은 1회성이므로 재요청하지 않으면 선회가 끝난 뒤에도
                # 영영 적용되지 않고 expires_at까지 기다렸다가 EXPIRED로 끝난다.
                # 대기 중인 동안 매 tick 다시 넣어 선회 직후 첫 tick에 적용시킨다.
                #
                # 취소도 같다 — ESCAPE 가드가 취소 가드보다 먼저 return 하므로 선회 중
                # 요청은 그 tick에 버려진다(state_machine 가드 2 / 2.5). 취소는 만료로
                # 끝나지도 않으니(UNCONDITIONAL_COMMAND_TYPES) 재요청이 없으면 대기가
                # 영원히 남아 result 도 MISSION_CANCELLED 도 안 나간다.
                if pending.command_type in {"PAUSE_MISSION", "CANCEL_MISSION"}:
                    self._request(machine, pending)
                continue
            try:
                self._commit_state(pending)
            except Exception:
                log.exception("command state commit failed: %s", pending.command_type)
                reason = (REASON_STOP_APPLICATION_FAILED
                          if pending.command_type == "COMPLETE_MISSION"
                          else REASON_INTERNAL_ERROR)
                self._finish(pending, "FAILED", reason)
            else:
                if pending.stop_before_reporting:
                    # 이 함수는 apply_command 뒤에만 호출된다. 즉, 바퀴가 멈춘 뒤 ACK.
                    self._publish("ACCEPTED", pending, REASON_OK)
                self._finish(pending, "COMPLETED", REASON_OK)
            completed += 1
        return completed

    # -- 수신·검증 --------------------------------------------------------

    def _accept(self, command, machine) -> bool:
        received_at = self._now()
        if not isinstance(command, dict):
            log.warning("dropping a command that is not an object")
            return False
        if command.get("schema_version") != SCHEMA_VERSION:
            log.warning("dropping command with schema_version=%r", command.get("schema_version"))
            return False

        command_id = command.get("command_id")
        command_type = command.get("command_type")
        if not command_id:
            log.warning("dropping command without command_id")
            return False
        if command_id in self._handled:
            self._client.publish(TopicKind.COMMAND_RESULT, self._handled[command_id])
            return True
        if command_id in self._pending:
            log.info("ignoring command still pending (command_id=%s)", command_id)
            return False
        if command_type in RESERVED_COMMAND_TYPES or command_type not in REQUIRED_PARAMETERS:
            return self._reject(command_id, received_at, REASON_INTERNAL_ERROR,
                                command_type=str(command_type))

        expires_at = _as_datetime(command.get("expires_at"))
        received_dt = _as_datetime(received_at)
        if (command_type not in UNCONDITIONAL_COMMAND_TYPES
                and expires_at is not None and received_dt is not None
                and received_dt > expires_at):
            return self._reject(command_id, received_at, REASON_COMMAND_TTL_EXPIRED, "EXPIRED",
                                command_type=command_type)
        parameters = command.get("parameters")
        if not isinstance(parameters, dict) or any(
            parameters.get(name) is None for name in REQUIRED_PARAMETERS[command_type]
        ):
            return self._reject(command_id, received_at, REASON_INTERNAL_ERROR,
                                command_type=command_type)
        if command_type == "COMPLETE_MISSION":
            completion_reason = parameters.get("completion_reason")
            mission_id = parameters.get("mission_id")
            # 무중단 전환 중에는 구형 빈 parameters도 받는다. 한 필드만 온 불완전한
            # 신규 payload나 허용되지 않은 완료 사유는 실행하지 않는다.
            if (parameters
                    and (set(parameters) != {"mission_id", "completion_reason"}
                         or not mission_id
                         or completion_reason not in COMPLETION_REASONS)):
                return self._reject(command_id, received_at, REASON_INTERNAL_ERROR,
                                    command_type=command_type)

        # 적용 대기 중인 미션 명령이 두 개 겹치면, 둘 다 같은 다음
        # DriveState를 보고 다른 mission_id를 순서대로 커밋할 수 있다.
        # EMERGENCY_STOP은 안전상, 취소는 상태 동기화상 기존 대기를 선점한다.
        preempting = command_type == "EMERGENCY_STOP" or command_type in UNCONDITIONAL_COMMAND_TYPES
        if self._pending and not preempting:
            return self._reject(command_id, received_at, REASON_ALREADY_IN_STATE,
                                command_type=command_type)
        if preempting and any(p.command_type == command_type for p in self._pending.values()):
            return self._reject(command_id, received_at, REASON_ALREADY_IN_STATE,
                                command_type=command_type)
        if command_type in UNCONDITIONAL_COMMAND_TYPES:
            # 선점한 대기는 그 자리에서 닫는다. ESTOP 은 poll_after_step 의 ESTOP
            # 가드가 곧바로 REJECTED 로 걷어 가지만 취소가 닿는 곳은 IDLE 이라 그
            # 가드에 걸리지 않는다 — 남겨 두면 만료까지 대기가 살아 있고, 그동안
            # 다음 START_MISSION 이 위의 '대기 충돌'로 거부된다. 취소 직후 새 미션을
            # 시작하는 것이 관제의 정상 흐름이므로 여기서 비워야 한다.
            for superseded in list(self._pending.values()):
                self._finish(superseded, "REJECTED", REASON_ALREADY_IN_STATE)

        reason = self._precondition(command_type, machine, parameters)
        if reason is not None:
            return self._reject(command_id, received_at, reason,
                                command_type=command_type)

        pending = PendingCommand(
            command_id=str(command_id),
            command_type=command_type,
            parameters=parameters,
            received_at=received_at,
            expires_at=expires_at,
            stop_before_reporting=(command_type in STOP_BEFORE_REPORTING),
        )
        self._pending[pending.command_id] = pending
        self._request(machine, pending)
        if not pending.stop_before_reporting:
            self._publish("ACCEPTED", pending, REASON_OK)
        return True

    def _precondition(self, command_type: str, machine, parameters: dict) -> Optional[str]:
        if command_type in UNCONDITIONAL_COMMAND_TYPES:
            return None
        snapshot = self._state.snapshot()
        if command_type == "START_MISSION":
            if snapshot.mission_id is not None:
                return REASON_ALREADY_IN_STATE
            if machine.state is not DriveState.IDLE:
                return REASON_ALREADY_IN_STATE
            if machine.state is DriveState.ESTOP or snapshot.mode == "SAFE_STOP":
                return REASON_SAFETY_NOT_CLEARED
        elif command_type in {"PAUSE_MISSION", "RESUME_MISSION", "COMPLETE_MISSION"}:
            if snapshot.mission_id is None:
                return REASON_NO_ACTIVE_MISSION
            if command_type == "PAUSE_MISSION" and machine.state is DriveState.PAUSED:
                return REASON_ALREADY_IN_STATE
            if command_type == "RESUME_MISSION":
                if machine.state is DriveState.ESTOP or snapshot.mode == "SAFE_STOP":
                    return REASON_SAFETY_NOT_CLEARED
                if machine.state not in {DriveState.PAUSED, DriveState.IDLE}:
                    return REASON_ALREADY_IN_STATE
            if command_type == "COMPLETE_MISSION":
                if machine.state is DriveState.ESTOP or snapshot.mode == "SAFE_STOP":
                    return REASON_SAFETY_NOT_CLEARED
                target_mission_id = parameters.get("mission_id")
                if (target_mission_id is not None
                        and target_mission_id != snapshot.mission_id):
                    return REASON_MISSION_MISMATCH
                if snapshot.mission_state != "RUNNING":
                    return REASON_ALREADY_IN_STATE
        elif command_type == "EMERGENCY_STOP":
            if machine.state is DriveState.ESTOP:
                return REASON_ALREADY_IN_STATE
        elif command_type == "CLEAR_EMERGENCY_STOP":
            if machine.state is not DriveState.ESTOP:
                return REASON_SAFETY_NOT_CLEARED
            if (snapshot.mode, snapshot.reason_code) != ("SAFE_STOP", "REMOTE_ESTOP"):
                return REASON_SAFETY_NOT_CLEARED
        return None

    def _start(self, machine, source: str) -> None:
        """출발 요청 — 인터록이 걸려 있으면 터치 확인이 올 때까지 보류한다.

        보류 중에도 명령은 pending 으로 남아 있다: 터치가 TTL(expires_at) 안에
        오지 않으면 계약대로 EXPIRED 로 끝나고(_finish 가 무장도 거둔다), 관제는
        '출발하지 않았다' 를 그 결과로 알게 된다. 조용히 성공으로 처리하지 않는다.
        """
        if self._interlock is None or self._interlock.arm(source=source):
            machine.request_start()

    def _request(self, machine, pending: PendingCommand) -> None:
        command_type = pending.command_type
        if command_type == "START_MISSION":
            zone_id = pending.parameters["zone_id"]
            if not re.match(ZONE_ID_PATTERN, str(zone_id)):
                log.warning("zone_id %r does not match %s", zone_id, ZONE_ID_PATTERN)
            self._start(machine, "서버 START_MISSION")
        elif command_type == "PAUSE_MISSION":
            machine.request_pause()
        elif command_type == "RESUME_MISSION":
            # CLEAR_EMERGENCY_STOP과 경계 소실 폴백은 IDLE에 남는다.
            # 이 경우의 원격 RESUME는 사람이 보낸 수동 재시작 요청이다 — 즉 '서 있던
            # 로봇을 다시 달리게 하는' 출발이므로 인터록을 지난다. 달리다 선
            # PAUSED 에서의 재개는 그대로다 (그때 로봇은 이미 멀리 있다).
            if machine.state is DriveState.IDLE:
                self._start(machine, "서버 RESUME_MISSION")
            else:
                machine.request_resume()
        elif command_type == "COMPLETE_MISSION":
            # 주행 FSM의 기존 종료 요청을 재사용한다. 통신 의미는 아래 commit에서
            # MISSION_COMPLETED로 확정하며 driving 코드는 변경하지 않는다.
            machine.request_cancel()
        elif command_type == "CANCEL_MISSION":
            machine.request_cancel()
        elif command_type == "EMERGENCY_STOP":
            # 접수해 둔 출발 신호부터 버린다 — 비상 정지를 받은 뒤에 누군가 로봇을
            # 만졌다고 그 출발이 살아나면 안 된다.
            self._disarm_start()
            machine.request_estop()
        elif command_type == "CLEAR_EMERGENCY_STOP":
            machine.request_estop_release()

    # -- 적용 확인·상태 커밋 --------------------------------------------

    @staticmethod
    def _is_applied(command_type: str, drive_state: DriveState) -> bool:
        targets = {
            "START_MISSION": {DriveState.CRUISE},
            "PAUSE_MISSION": {DriveState.PAUSED},
            "RESUME_MISSION": {DriveState.CRUISE},
            "COMPLETE_MISSION": {DriveState.IDLE},
            "CANCEL_MISSION": {DriveState.IDLE, DriveState.ESTOP},
            "EMERGENCY_STOP": {DriveState.ESTOP},
            "CLEAR_EMERGENCY_STOP": {DriveState.IDLE},
        }
        return drive_state in targets[command_type]

    def _commit_state(self, pending: PendingCommand) -> None:
        kind = pending.command_type
        snapshot = self._state.snapshot()
        if kind == "START_MISSION":
            if snapshot.mode == "ERROR":
                self._state.stand_by()
            self._state.start_patrol()
            self._state.start_mission(
                pending.parameters["mission_id"], zone_id=pending.parameters["zone_id"]
            )
            self._events.mission_started()
        elif kind == "PAUSE_MISSION":
            if snapshot.mode == "ERROR":
                self._state.stand_by()
                self._state.start_patrol()
            self._state.pause("REMOTE_PAUSE")
            self._events.mission_paused()
        elif kind == "RESUME_MISSION":
            if snapshot.mode == "ERROR":
                self._state.stand_by()
                self._state.start_patrol()
            elif snapshot.mode == "READY":
                self._state.start_patrol()
            else:
                self._state.resume()
            self._events.mission_resumed()
        elif kind == "COMPLETE_MISSION":
            completion_reason = pending.parameters.get(
                "completion_reason", "OPERATOR_COMPLETED")
            self._events.mission_completed(
                completion_reason,
                causation_command_id=pending.command_id)
            self._state.stand_by()
        elif kind == "CANCEL_MISSION":
            self._cancel_mission(pending, snapshot)
        elif kind == "EMERGENCY_STOP":
            if snapshot.mode == "ERROR":
                self._state.stand_by()
            self._state.safe_stop("REMOTE_ESTOP")
            if snapshot.mission_id is not None and snapshot.mission_state == "RUNNING":
                self._events.mission_paused()
        elif kind == "CLEAR_EMERGENCY_STOP":
            if snapshot.mission_id is not None:
                # 해제 즉시 자동 출발하지 않는다. RESUME_MISSION을 별도로 받는다.
                self._state.pause("REMOTE_PAUSE")
                self._state.set_mission_state("PAUSED")
            else:
                self._state.release_safe_stop()

    def _cancel_mission(self, pending: PendingCommand, snapshot) -> None:
        """취소를 상태에 반영한다 — 로컬 보관함이 어떤 상태든 서버 미션을 닫는다.

        서버가 닫아야 하는 미션은 `parameters.mission_id` 가 지목한 것이다. 로컬
        보관함은 세 경우가 있고 셋 다 거부하지 않는다:

          ① 같은 미션이 살아 있다 — 정상 경로. 집계를 실어 종료 event 를 낸다.
          ② 보관함이 비었다 — 재기동으로 사라졌거나(고아 미션 정리) 애초에 START 를
            못 받았다. 서버만 RUNNING 으로 알고 있으니 그 미션 앞으로 종료 event 를
            낸다. 집계는 0 이다 — 세어 둔 값이 없고, 없는 값을 지어내지 않는다.
          ③ 다른 미션이 살아 있다 — 늦게 도착한 취소다. **살아 있는 미션은 건드리지
            않고** 지목된 미션만 닫는다. 여기서 보관함을 취소하면 서버가 취소하려던
            것과 다른 미션이 죽는다 (만료를 보지 않기로 한 대가를 여기서 치른다).

        mode 는 SAFE_STOP 이면 유지한다 — 취소는 미션을 닫는 명령이고, 차도 위·원격
        ESTOP 으로 선 로봇을 READY(정상 대기)로 바꿔 보고하면 관제가 사람을 보내지
        않는다. ③ 에서도 순찰을 멈추지 않는다: 지금 돌고 있는 미션은 취소 대상이 아니다.
        """
        # 관리자 사유 원문은 completion_reason 에 싣지 않는다. 이 필드는 나머지 종료
        # 경로가 ROUTE_COMPLETED·OPERATOR_COMPLETED·INTERNAL_ERROR 처럼 enum 값을 넣는
        # 자리이고, 서버가 enum 으로 검증하면 자유 문자열을 실은 취소는 전부
        # MQTT_PAYLOAD_VALIDATION_FAILED 로 조용히 버려진다 — 미션이 안 닫히는 그
        # 결과가 이 변경으로 막으려던 바로 그것이다. 사유 원문은 서버가 스스로 만든
        # 값이므로 우리가 되돌려 줄 필요도 없다. 로그에만 남긴다.
        reason = REASON_OPERATOR_CANCELLED
        log.info("취소 사유(관리자): %s", pending.parameters["reason"])
        target = pending.parameters.get("mission_id") or snapshot.mission_id
        if snapshot.mission_id is not None and target == snapshot.mission_id:
            self._events.mission_cancelled(reason)
        elif target is not None:
            log.warning("취소 대상(%s)이 보관함(%s)과 다르다 — 지목된 미션만 닫는다",
                        target, snapshot.mission_id)
            self._events.mission_cancelled_for(target, reason)
            if snapshot.mission_id is not None:
                return          # ③ 살아 있는 미션의 mode 를 건드리지 않는다
        else:
            # mission_id 도 없고 보관함도 비었다 — 닫을 미션을 특정할 수 없다.
            # 그래도 거부하지 않는다: 바퀴는 이미 섰고 result 는 COMPLETED 로 나간다.
            log.error("취소에 mission_id 가 없고 활성 미션도 없다 — 종료 event 없이 정지만 반영한다")
        if snapshot.mode != "SAFE_STOP":
            self._state.stand_by()

    # -- result ----------------------------------------------------------

    def _reject(self, command_id, received_at, reason_code, status="REJECTED",
                command_type="") -> bool:
        # command_type 은 result payload 에 들어가지 않는다(계약에 없다) — 대시보드
        # 로그에 '무엇이 거절됐는지' 남기기 위해서만 받는다.
        pending = PendingCommand(
            command_id=str(command_id), command_type=command_type, parameters={},
            received_at=received_at, expires_at=None,
        )
        payload = self._publish(status, pending, reason_code, completed_at=self._now())
        self._remember(pending.command_id, payload)
        return True

    def _disarm_start(self) -> None:
        if self._interlock is not None:
            self._interlock.disarm()

    def _finish(self, pending: PendingCommand, status: str, reason_code: str) -> None:
        if status != "COMPLETED" and pending.command_type in {"START_MISSION",
                                                              "RESUME_MISSION"}:
            # 만료·거절로 끝난 출발이다. 무장을 남겨 두면 한참 뒤의 터치 한 번에
            # 아무도 시키지 않은(서버는 실패로 아는) 출발이 된다.
            self._disarm_start()
        payload = self._publish(status, pending, reason_code, completed_at=self._now())
        self._pending.pop(pending.command_id, None)
        self._remember(pending.command_id, payload)

    def _publish(self, status: str, pending: PendingCommand, reason_code: str,
                 completed_at=None) -> dict:
        after = self._state.snapshot()
        payload = self._client.messages.command_result(
            command_id=pending.command_id,
            status=status,
            received_at=pending.received_at,
            completed_at=completed_at,
            reason_code=reason_code,
            mode=after.mode,
            state_reason_code=after.reason_code,
        )
        self._client.publish(TopicKind.COMMAND_RESULT, payload)
        self.recent_results.append({
            "at": completed_at or pending.received_at,
            "command_type": pending.command_type or "UNKNOWN",
            "status": status,
            "reason_code": reason_code,
        })
        return payload

    def _remember(self, command_id: str, payload: dict) -> None:
        self._handled[command_id] = payload
        while len(self._handled) > self._memory:
            self._handled.popitem(last=False)
