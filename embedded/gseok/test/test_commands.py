"""commands.py 단위 테스트 — 검증·실행·결과 발행.

브로커도 paho 도 없이 돈다. 시계는 주입해서 만료 판정을 실제로 기다리지 않고
검증한다. payload 는 진짜 factory 가 만든다 — 계약 위반은 factory 가 잡아야 한다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §12·§13
"""

<<<<<<< HEAD
=======
import copy
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
import queue

import pytest

from commands import (
    HANDLED_COMMAND_MEMORY,
    REQUIRED_PARAMETERS,
    RESERVED_COMMAND_TYPES,
    CommandRunner,
)
from comms import MessageFactory, TopicKind
from events import EventPublisher
from robot_state import RobotState
from telemetry import TELEMETRY_INTERVAL_SEC, TelemetrySampler

ROBOT_ID = "RBT-001"
MISSION_ID = "MIS-20260726-001"
#: 주입 시계(05:30:xx)보다 뒤·앞.
FUTURE = "2026-07-26T05:31:00.000Z"
PAST = "2026-07-26T05:29:00.000Z"

DEFAULT_PARAMETERS = {
    "START_MISSION": {"mission_id": MISSION_ID, "zone_id": "A-01"},
    "PAUSE_MISSION": {"reason": "operator_request"},
    "RESUME_MISSION": {},
<<<<<<< HEAD
=======
    "COMPLETE_MISSION": {},
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    "CANCEL_MISSION": {"reason": "operator_request"},
    "EMERGENCY_STOP": {"reason": "operator_request"},
    "CLEAR_EMERGENCY_STOP": {"reason": "operator_request"},
}


# --------------------------------------------------------------------------
# 테스트 도구
# --------------------------------------------------------------------------


class RecordingClient:
    """CommandRunner 가 쓰는 부분만. payload 는 진짜 factory 가 만든다."""

    def __init__(self, robot_id: str = ROBOT_ID) -> None:
        self.messages = MessageFactory(robot_id)
        self.commands = queue.Queue()
        self.published = []  # (kind, payload)

    def publish(self, kind, payload, *, wait_sec=None) -> bool:
        self.published.append((kind, payload))
        return True

    def of(self, kind) -> list:
        return [payload for sent, payload in self.published if sent is kind]

    def results(self) -> list:
        return self.of(TopicKind.COMMAND_RESULT)

    def missions(self) -> list:
        return self.of(TopicKind.EVENT_MISSION)


class FakeClock:
    def __init__(self, start: float = 1000.0) -> None:
        self.value = start
        self.stamps = 0

    def monotonic(self) -> float:
        return self.value

    def now(self) -> str:
        self.stamps += 1
        return f"2026-07-26T05:30:{self.stamps:02d}.000Z"

    def advance(self, seconds: float) -> None:
        self.value += seconds


@pytest.fixture
def clock():
    return FakeClock()


@pytest.fixture
def client():
    return RecordingClient()


@pytest.fixture
def state(clock):
    fresh = RobotState(monotonic=clock.monotonic)
    fresh.boot_completed()  # 명령을 받는 것은 기동이 끝난 뒤다
    return fresh


@pytest.fixture
def events(client, state, clock):
    return EventPublisher(client, state, monotonic=clock.monotonic, now=clock.now)


@pytest.fixture
def runner(client, state, events, clock):
    return CommandRunner(client, state, events, now=clock.now)


def command(command_type: str = "EMERGENCY_STOP", **overrides) -> dict:
    payload = {
        "schema_version": "2.0",
        "command_id": overrides.pop("command_id", f"CMD-{command_type}"),
        "command_type": command_type,
        "issued_at": "2026-07-26T05:30:00.000Z",
        "expires_at": FUTURE,
        "requested_by": {"actor_type": "ADMIN", "actor_id": "ADM-0042"},
        "parameters": DEFAULT_PARAMETERS.get(command_type, {}),
    }
    payload.update(overrides)
    return payload


def start_mission(runner, state) -> None:
    runner.handle(command("START_MISSION"))
    assert state.mission_id == MISSION_ID


def statuses(client) -> list:
    return [p["status"] for p in client.results()]


def last(client) -> dict:
    return client.results()[-1]


# --------------------------------------------------------------------------
# 검증 (계약 §12, DoD 1~4)
# --------------------------------------------------------------------------


def test_an_expired_command_is_not_executed(client, runner, state):
    assert runner.handle(command("EMERGENCY_STOP", expires_at=PAST)) is True
    assert statuses(client) == ["EXPIRED"]  # 접수한 적이 없으니 결과는 하나뿐이다
    assert state.mode == "READY"


def test_a_command_without_an_expiry_is_executed(client, runner, state):
    # 서버가 만료시각을 안 보냈으면 만료를 우리가 만들어내지 않는다.
    runner.handle(command("EMERGENCY_STOP", expires_at=None))
    assert statuses(client) == ["ACCEPTED", "COMPLETED"]


def test_a_repeated_command_id_is_ignored(client, runner, state):
    repeated = command("EMERGENCY_STOP")
    runner.handle(repeated)
    assert statuses(client) == ["ACCEPTED", "COMPLETED"]

    state.resume()  # 다시 실행됐다면 여기서 SAFE_STOP 으로 돌아갈 것이다
    assert runner.handle(repeated) is False
    assert statuses(client) == ["ACCEPTED", "COMPLETED"]
    assert state.mode == "PATROLLING"


def test_a_repeated_rejected_command_stays_quiet(client, runner):
    rejected = command("RETURN_TO_CHARGE")
    runner.handle(rejected)
    runner.handle(rejected)
    assert statuses(client) == ["REJECTED"]


def test_the_memory_of_handled_commands_is_bounded(client, state, events, clock):
    """boot 안에서만 유효하면 되고, 무한히 기억하지 않는다."""
    assert HANDLED_COMMAND_MEMORY == 64
    small = CommandRunner(client, state, events, now=clock.now, memory=2)
    for index in range(3):
        small.handle(command("EMERGENCY_STOP", command_id=f"CMD-{index}"))
    # 가장 오래된 것은 잊혔다 — 다시 오면 다시 처리한다.
    assert small.handle(command("EMERGENCY_STOP", command_id="CMD-0")) is True


@pytest.mark.parametrize("command_type", sorted(RESERVED_COMMAND_TYPES))
def test_reserved_commands_are_rejected(client, runner, command_type):
    runner.handle(command(command_type))
    assert statuses(client) == ["REJECTED"]
    assert last(client)["reason_code"] == "INTERNAL_ERROR"


def test_an_unknown_command_type_is_rejected(client, runner):
    runner.handle(command("SELF_DESTRUCT"))
    assert statuses(client) == ["REJECTED"]


def test_a_command_without_an_id_is_rejected(client, runner):
    runner.handle(command("EMERGENCY_STOP", command_id=None))
    assert statuses(client) == ["REJECTED"]


@pytest.mark.parametrize(
    "command_type", [name for name, required in REQUIRED_PARAMETERS.items() if required]
)
def test_missing_parameters_are_rejected(client, runner, state, command_type):
    runner.handle(command(command_type, parameters={}))
    assert statuses(client) == ["REJECTED"]
    assert state.mode == "READY"


def test_a_foreign_schema_version_is_dropped_silently(client, runner):
    """해석할 수 없는 계약이라 command_id 조차 믿을 수 없다 (§3.4)."""
    assert runner.handle(command("EMERGENCY_STOP", schema_version="1.0")) is False
    assert client.published == []


def test_a_non_object_payload_is_dropped(client, runner):
    assert runner.handle(["EMERGENCY_STOP"]) is False
    assert client.published == []


# --------------------------------------------------------------------------
# 결과 발행 (계약 §13, DoD 5·6)
# --------------------------------------------------------------------------


def test_accepted_and_final_share_the_command_id(client, runner):
    sent = command("EMERGENCY_STOP")
    runner.handle(sent)

    accepted, final = client.results()
    assert (accepted["status"], final["status"]) == ("ACCEPTED", "COMPLETED")
    assert accepted["command_id"] == final["command_id"] == sent["command_id"]
    assert accepted["result_id"] != final["result_id"]
    # 접수 시각은 두 메시지가 같은 값을 쓴다.
    assert accepted["received_at"] == final["received_at"]


def test_accepted_leaves_completed_at_null(client, runner):
    runner.handle(command("EMERGENCY_STOP"))
    accepted, final = client.results()
    assert accepted["completed_at"] is None
    assert final["completed_at"] is not None


def test_the_result_payload_matches_the_contract_shape(client, runner):
    runner.handle(command("EMERGENCY_STOP"))
    payload = last(client)
    assert list(payload) == [
        "schema_version",
        "result_id",
        "command_id",
        "mqtt_id",
        "status",
        "received_at",
        "completed_at",
        "reason_code",
        "state",
    ]
    assert payload["mqtt_id"] == ROBOT_ID
    # state 는 처리 후 값이다.
    assert payload["state"] == {"mode": "SAFE_STOP", "reason_code": "REMOTE_ESTOP"}


# --------------------------------------------------------------------------
# 미션 명령 (DoD 7·8·11·12)
# --------------------------------------------------------------------------


def test_start_mission_fills_the_slot_and_starts_patrolling(client, runner, state):
    runner.handle(command("START_MISSION"))
    assert statuses(client) == ["ACCEPTED", "COMPLETED"]
    assert (state.mission_id, state.zone_id) == (MISSION_ID, "A-01")
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")
    assert [p["event_type"] for p in client.missions()] == ["MISSION_STARTED"]


<<<<<<< HEAD
=======
def test_start_mission_preserves_an_opaque_zone_id(client, runner, state):
    """구역 코드는 형식을 해석하지 않고 START_MISSION 값을 그대로 보존한다."""
    # command() 는 모듈 전역 parameters 를 공유한다 — 복사해서 건드린다.
    payload = copy.deepcopy(command("START_MISSION"))
    payload["parameters"]["zone_id"] = "똥방구 설사"
    runner.handle(payload)

    assert statuses(client) == ["ACCEPTED", "COMPLETED"]
    assert (state.mission_id, state.zone_id) == (MISSION_ID, "똥방구 설사")


>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
def test_telemetry_carries_the_mission_after_start(client, runner, state, clock):
    sampler = TelemetrySampler(
        client, state, monotonic=clock.monotonic, now=clock.now, thermal_root="none"
    )
    runner.handle(command("START_MISSION"))
    sampler.poll()
    sample = client.of(TopicKind.TELEMETRY)[-1]
    assert sample["mission_id"] == MISSION_ID
    assert sample["mission"] == {
        "state": "RUNNING",
        "elapsed_sec": 0,
        "incident_count": 0,
    }


def test_cancel_mission_empties_the_slot(client, runner, state, clock):
    sampler = TelemetrySampler(
        client, state, monotonic=clock.monotonic, now=clock.now, thermal_root="none"
    )
    start_mission(runner, state)
    runner.handle(command("CANCEL_MISSION"))

    assert [p["event_type"] for p in client.missions()] == [
        "MISSION_STARTED",
        "MISSION_CANCELLED",
    ]
    assert client.missions()[-1]["result"]["completion_reason"] == "operator_request"
    assert state.mission_id is None
    assert (state.mode, state.reason_code) == ("READY", "NONE")

    clock.advance(TELEMETRY_INTERVAL_SEC)
    sampler.poll()
    sample = client.of(TopicKind.TELEMETRY)[-1]
    assert (sample["mission_id"], sample["mission"]) == (None, None)


<<<<<<< HEAD
=======
def test_complete_mission_publishes_completed_and_empties_the_slot(
    client, state, events, clock
):
    stops = []
    runner = CommandRunner(
        client,
        state,
        events,
        stop_motor=lambda: stops.append(state.mode),
        now=clock.now,
    )
    start_mission(runner, state)
    runner.handle(command("COMPLETE_MISSION"))

    assert stops == ["PATROLLING"]
    assert [p["event_type"] for p in client.missions()] == [
        "MISSION_STARTED",
        "MISSION_COMPLETED",
    ]
    assert client.missions()[-1]["result"]["completion_reason"] == (
        "OPERATOR_COMPLETED"
    )
    assert state.mission_id is None
    assert (state.mode, state.reason_code) == ("READY", "NONE")


def test_complete_while_paused_is_rejected_and_keeps_the_mission(
    client, runner, state
):
    start_mission(runner, state)
    runner.handle(command("PAUSE_MISSION"))
    runner.handle(command("COMPLETE_MISSION"))

    assert statuses(client)[-2:] == ["ACCEPTED", "REJECTED"]
    assert last(client)["reason_code"] == "ALREADY_IN_STATE"
    assert state.mission_id == MISSION_ID
    assert state.mission_state == "PAUSED"
    assert (state.mode, state.reason_code) == ("PAUSED", "REMOTE_PAUSE")
    assert [p["event_type"] for p in client.missions()] == [
        "MISSION_STARTED",
        "MISSION_PAUSED",
    ]


def test_complete_while_safe_stopped_is_rejected_and_keeps_the_mission(
    client, runner, state
):
    start_mission(runner, state)
    runner.handle(command("EMERGENCY_STOP"))
    runner.handle(command("COMPLETE_MISSION"))

    assert statuses(client)[-2:] == ["ACCEPTED", "REJECTED"]
    assert last(client)["reason_code"] == "SAFETY_CONDITION_NOT_CLEARED"
    assert state.mission_id == MISSION_ID
    assert state.mission_state == "PAUSED"
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")
    assert [p["event_type"] for p in client.missions()] == [
        "MISSION_STARTED",
        "MISSION_PAUSED",
    ]


>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
def test_pause_and_resume_move_the_mission(client, runner, state):
    start_mission(runner, state)
    runner.handle(command("PAUSE_MISSION"))
    assert (state.mode, state.reason_code) == ("PAUSED", "REMOTE_PAUSE")
    assert state.mission_state == "PAUSED"

    runner.handle(command("RESUME_MISSION"))
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")
    assert state.mission_state == "RUNNING"
    assert [p["event_type"] for p in client.missions()] == [
        "MISSION_STARTED",
        "MISSION_PAUSED",
        "MISSION_RESUMED",
    ]


@pytest.mark.parametrize(
<<<<<<< HEAD
    "command_type", ["PAUSE_MISSION", "RESUME_MISSION", "CANCEL_MISSION"]
=======
    "command_type",
    ["PAUSE_MISSION", "RESUME_MISSION", "COMPLETE_MISSION", "CANCEL_MISSION"],
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
)
def test_mission_commands_need_an_active_mission(client, runner, command_type):
    runner.handle(command(command_type))
    assert statuses(client) == ["ACCEPTED", "REJECTED"]
    assert last(client)["reason_code"] == "NO_ACTIVE_MISSION"


def test_pausing_twice_reports_already_in_state(client, runner, state):
    start_mission(runner, state)
    runner.handle(command("PAUSE_MISSION", command_id="CMD-PAUSE-1"))
    runner.handle(command("PAUSE_MISSION", command_id="CMD-PAUSE-2"))
    assert last(client)["reason_code"] == "ALREADY_IN_STATE"
    assert last(client)["status"] == "REJECTED"


def test_starting_a_second_mission_reports_already_in_state(client, runner, state):
    start_mission(runner, state)
    runner.handle(command("START_MISSION", command_id="CMD-START-2"))
    assert last(client)["reason_code"] == "ALREADY_IN_STATE"
    # 보관함을 덮어쓰지 않는다.
    assert state.mission_id == MISSION_ID


def test_start_mission_does_not_drive_away_from_a_safety_stop(client, runner, state):
    """전이표는 SAFE_STOP → PATROLLING 을 허용한다. 명령 하나로 지나가면 안 된다."""
    runner.handle(command("EMERGENCY_STOP"))
    runner.handle(command("START_MISSION"))
    assert last(client)["reason_code"] == "SAFETY_CONDITION_NOT_CLEARED"
    assert last(client)["status"] == "REJECTED"
    assert state.mission_id is None
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")


def test_start_mission_works_once_the_stop_is_cleared(client, runner, state):
    runner.handle(command("EMERGENCY_STOP"))
    runner.handle(command("CLEAR_EMERGENCY_STOP"))
    runner.handle(command("START_MISSION"))
    assert last(client)["status"] == "COMPLETED"
    assert (state.mode, state.mission_id) == ("PATROLLING", MISSION_ID)


def test_emergency_stop_halts_the_motor_before_the_transition(client, state, events, clock):
    """§16-10 — 바퀴부터 세우고 그 다음이 전이·발행이다."""
    stops = []
    stopper = CommandRunner(
        client, state, events, stop_motor=lambda: stops.append(state.mode), now=clock.now
    )
    state.start_patrol()
    stopper.handle(command("EMERGENCY_STOP"))
    assert stops == ["PATROLLING"]  # 정지 호출 시점에는 아직 전이 전이다
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")


def test_a_failing_motor_stop_still_reports_the_command(client, state, events, clock):
    def boom():
        raise OSError("i2c bus is gone")

    stopper = CommandRunner(client, state, events, stop_motor=boom, now=clock.now)
    stopper.handle(command("EMERGENCY_STOP"))
    assert statuses(client) == ["ACCEPTED", "COMPLETED"]
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")


def test_resume_does_not_release_a_safety_stop(client, runner, state):
    start_mission(runner, state)
    state.safe_stop("OBSTACLE")
    runner.handle(command("RESUME_MISSION"))
    assert last(client)["reason_code"] == "SAFETY_CONDITION_NOT_CLEARED"
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "OBSTACLE")


def test_cancelling_while_stopped_keeps_the_robot_stopped(client, runner, state):
    start_mission(runner, state)
    runner.handle(command("EMERGENCY_STOP"))
    runner.handle(command("CANCEL_MISSION"))
    assert state.mission_id is None
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")


# --------------------------------------------------------------------------
# 비상정지 (계약 §16-10, DoD 9·10)
# --------------------------------------------------------------------------


def test_emergency_stop_stops_before_it_reports(client, runner, state):
    runner.handle(command("EMERGENCY_STOP"))
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")
    # 첫 발행이 이미 정지 후 상태를 싣고 있다 = 정지가 발행보다 먼저였다.
    accepted, final = client.results()
    assert accepted["state"] == {"mode": "SAFE_STOP", "reason_code": "REMOTE_ESTOP"}
    assert final["state"] == accepted["state"]


<<<<<<< HEAD
def test_emergency_stop_leaves_the_mission_alone(client, runner, state):
    start_mission(runner, state)
    runner.handle(command("EMERGENCY_STOP"))
    assert state.mission_id == MISSION_ID
    assert state.mission_state == "RUNNING"
    assert [p["event_type"] for p in client.missions()] == ["MISSION_STARTED"]


def test_clearing_an_estop_returns_to_the_mission(client, runner, state):
=======
def test_emergency_stop_pauses_the_running_mission(client, runner, state):
    start_mission(runner, state)
    runner.handle(command("EMERGENCY_STOP"))
    assert state.mission_id == MISSION_ID
    assert state.mission_state == "PAUSED"
    assert [p["event_type"] for p in client.missions()] == [
        "MISSION_STARTED",
        "MISSION_PAUSED",
    ]


def test_clearing_an_estop_waits_for_explicit_resume(client, runner, state):
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    start_mission(runner, state)
    runner.handle(command("EMERGENCY_STOP"))
    runner.handle(command("CLEAR_EMERGENCY_STOP"))
    assert last(client)["status"] == "COMPLETED"
<<<<<<< HEAD
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")
=======
    assert (state.mode, state.reason_code) == ("PAUSED", "REMOTE_PAUSE")
    assert state.mission_state == "PAUSED"

    runner.handle(command("RESUME_MISSION"))
    assert last(client)["status"] == "COMPLETED"
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")
    assert state.mission_state == "RUNNING"
    assert [p["event_type"] for p in client.missions()] == [
        "MISSION_STARTED",
        "MISSION_PAUSED",
        "MISSION_RESUMED",
    ]
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84


def test_clearing_an_estop_without_a_mission_goes_to_ready(client, runner, state):
    runner.handle(command("EMERGENCY_STOP"))
    runner.handle(command("CLEAR_EMERGENCY_STOP"))
    assert (state.mode, state.reason_code) == ("READY", "NONE")


def test_clearing_an_estop_keeps_a_paused_mission_paused(client, runner, state):
    start_mission(runner, state)
    runner.handle(command("PAUSE_MISSION"))
    runner.handle(command("EMERGENCY_STOP"))
    runner.handle(command("CLEAR_EMERGENCY_STOP"))
    assert (state.mode, state.reason_code) == ("PAUSED", "REMOTE_PAUSE")


def test_an_obstacle_stop_is_not_cleared_by_the_operator(client, runner, state):
    """정지 사유가 여러 개일 수 있고 관리자는 그중 하나만 해제했다."""
    start_mission(runner, state)
    state.obstacle_detected()
    runner.handle(command("CLEAR_EMERGENCY_STOP"))
    assert statuses(client)[-2:] == ["ACCEPTED", "REJECTED"]
    assert last(client)["reason_code"] == "SAFETY_CONDITION_NOT_CLEARED"
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "OBSTACLE")


def test_a_second_emergency_stop_reports_already_in_state(client, runner):
    runner.handle(command("EMERGENCY_STOP", command_id="CMD-STOP-1"))
    runner.handle(command("EMERGENCY_STOP", command_id="CMD-STOP-2"))
    assert last(client)["reason_code"] == "ALREADY_IN_STATE"


# --------------------------------------------------------------------------
# 큐 소비 (DoD 13)
# --------------------------------------------------------------------------


def test_poll_drains_the_whole_queue_in_one_tick(client, runner, state):
    """tick 당 하나씩 비우면 EMERGENCY_STOP 이 0.5초씩 밀린다."""
    client.commands.put(command("START_MISSION"))
    client.commands.put(command("PAUSE_MISSION"))
    client.commands.put(command("EMERGENCY_STOP"))

    assert runner.poll() == 3
    assert client.commands.empty()
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")
    assert runner.poll() == 0


def test_a_failing_command_does_not_stop_the_queue(client, runner, state, events):
    """실행 중 예외는 FAILED 로 끝나고 다음 명령이 계속 처리된다."""

    def boom(parameters):
        raise RuntimeError("driver is on fire")

    runner._handlers["PAUSE_MISSION"] = boom
    start_mission(runner, state)
    client.commands.put(command("PAUSE_MISSION"))
    client.commands.put(command("EMERGENCY_STOP"))

    assert runner.poll() == 2
    failed = client.results()[-3]
    assert (failed["status"], failed["reason_code"]) == ("FAILED", "INTERNAL_ERROR")
    assert statuses(client)[-2:] == ["ACCEPTED", "COMPLETED"]
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")


def test_a_command_that_cannot_transition_fails_without_touching_the_slot(
    client, clock, events
):
    """기동 중에 온 START_MISSION 은 FAILED 다 — 보관함은 건드리지 않는다."""
    booting = RobotState(monotonic=clock.monotonic)
    runner = CommandRunner(client, booting, events, now=clock.now)

    client.commands.put(command("START_MISSION"))
    assert runner.poll() == 1
    assert last(client)["status"] == "FAILED"
    assert booting.mission_id is None
    assert booting.mode == "BOOTING"
