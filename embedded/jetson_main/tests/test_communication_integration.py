"""MQTT 명령 ↔ 실제 주행 FSM 통합 경계 테스트."""

import queue
import sqlite3
from dataclasses import replace
from types import SimpleNamespace

import pytest

from comms import MessageFactory, MqttClient, MqttConfig, Outbox, TopicKind
from comms import codec
from communication.commands import DriveCommandRunner
from communication.events import EventPublisher
from communication.robot_state import MISSION_FILE_NAME, RobotState
from communication.runtime import (
    CommunicationRuntime, DriveStateProjector, RuntimeSignals,
)
from driving.state_machine import StateMachine, StateMachineParams
from driving.states import AvoidPhase, DriveCommand, DriveState
from hardware.mocks import MockImu
from perception.interfaces import ObstacleRegion, SegmentationFrame


class RecordingClient:
    def __init__(self):
        self.commands = queue.Queue()
        self.messages = MessageFactory("RBT-TEST")
        self.published = []

    def publish(self, kind, payload, **_kwargs):
        self.published.append((TopicKind(kind), payload))
        return True

    def results(self):
        return [payload for kind, payload in self.published
                if kind is TopicKind.COMMAND_RESULT]


@pytest.fixture
def params(config):
    return StateMachineParams.from_config(config["driving"])


@pytest.fixture
def clear_frame():
    return SegmentationFrame(timestamp=0.0, sidewalk_detected=True)


def wired_session(params):
    """client·state·machine·runner 한 벌. 투영기까지 엮는 테스트는 직접 부른다."""
    client = RecordingClient()
    state = RobotState()
    state.boot_completed()
    machine = StateMachine(params, now=0.0)
    events = EventPublisher(client, state, sensor_fault_stops_driving=False)
    runner = DriveCommandRunner(client, state, events)
    return client, state, machine, runner


@pytest.fixture
def wired(params):
    return wired_session(params)


def mqtt_command(command_id, command_type, parameters):
    return {
        "schema_version": "2.0",
        "command_id": command_id,
        "command_type": command_type,
        "issued_at": "2098-01-01T00:00:00.000Z",
        "expires_at": "2099-01-01T00:00:00.000Z",
        "parameters": parameters,
    }


def apply_one(client, machine, runner, frame, now):
    runner.poll_before_step(machine)
    command = machine.step(frame, None, now)
    runner.poll_after_step(machine)  # 실제 main에서는 apply_command 직후
    return command


def test_mission_start_pause_resume_cancel_round_trip(wired, clear_frame):
    client, state, machine, runner = wired

    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    apply_one(client, machine, runner, clear_frame, 1.0)
    assert machine.state is DriveState.CRUISE
    assert state.snapshot().mission_state == "RUNNING"
    assert [result["status"] for result in client.results()[-2:]] == ["ACCEPTED", "COMPLETED"]

    client.commands.put(mqtt_command("pause", "PAUSE_MISSION", {"reason": "operator"}))
    paused = apply_one(client, machine, runner, clear_frame, 2.0)
    assert machine.state is DriveState.PAUSED
    assert paused.brake
    assert state.snapshot().mission_state == "PAUSED"

    client.commands.put(mqtt_command("resume", "RESUME_MISSION", {}))
    apply_one(client, machine, runner, clear_frame, 3.0)
    assert machine.state is DriveState.CRUISE
    assert state.snapshot().mission_state == "RUNNING"

    client.commands.put(mqtt_command("cancel", "CANCEL_MISSION", {"reason": "operator"}))
    cancelled = apply_one(client, machine, runner, clear_frame, 4.0)
    assert machine.state is DriveState.IDLE
    assert cancelled.brake
    assert state.snapshot().mode == "READY"
    assert state.snapshot().mission_id is None
    assert [event["event_type"] for event in _mission_events(client.published)] == [
        "MISSION_STARTED", "MISSION_PAUSED", "MISSION_RESUMED", "MISSION_CANCELLED"
    ]


def test_complete_mission_stops_then_publishes_terminal_event(wired, clear_frame):
    client, state, machine, runner = wired
    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    apply_one(client, machine, runner, clear_frame, 1.0)

    client.commands.put(mqtt_command("complete", "COMPLETE_MISSION", {
        "mission_id": "MIS-1",
        "completion_reason": "PLANNED_DURATION_REACHED",
    }))
    assert runner.poll_before_step(machine)
    assert [result["command_id"] for result in client.results()] == ["start", "start"]
    command = machine.step(clear_frame, None, 2.0)
    assert machine.state is DriveState.IDLE and command.brake
    runner.poll_after_step(machine)

    assert state.snapshot().mission_id is None
    assert [event["event_type"] for event in _mission_events(client.published)] == [
        "MISSION_STARTED", "MISSION_COMPLETED"
    ]
    completed_event = _mission_events(client.published)[-1]
    assert completed_event["causation_command_id"] == "complete"
    assert completed_event["result"]["completion_reason"] == "PLANNED_DURATION_REACHED"
    completed = client.results()[-1]
    assert completed["command_id"] == "complete"
    assert completed["status"] == "COMPLETED"


def test_pause_during_escape_is_not_urgent_and_applies_after_the_turn(wired, clear_frame):
    """ESCAPE(차도 이탈 선회) 중의 대기 일시정지가 urgent 정지 경로를 타면 안 된다.

    urgent 경로는 frame=None 으로 step 을 돌므로, ESCAPE 에서는 인지 두절 폴백
    **브레이크**가 되어 로봇이 차도 위에 선다 — PAUSABLE_STATES 에서 ESCAPE 를 뺀
    설계를 우회하는 경로였다. 일시정지는 선회가 끝난 첫 tick 에 적용되어야 한다.
    """
    client, state, machine, runner = wired
    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    apply_one(client, machine, runner, clear_frame, 1.0)
    assert machine.state is DriveState.CRUISE

    risk = SegmentationFrame(timestamp=2.0, sidewalk_detected=True,
                             roadway_encroachment_risk=True)
    apply_one(client, machine, runner, risk, 2.0)
    assert machine.state is DriveState.ESCAPE

    client.commands.put(mqtt_command("pause", "PAUSE_MISSION", {"reason": "op"}))
    # 선회 중에는 urgent 가 아니다 — main 은 인지를 계속 읽으며 선회를 잇는다.
    assert not runner.poll_before_step(machine)
    command = machine.step(risk, None, 2.1)
    runner.poll_after_step(machine)
    assert machine.state is DriveState.ESCAPE   # 일시정지가 선회를 끊지 않았다
    assert not command.brake                    # 차도 위 브레이크가 없다
    assert command.throttle_pct != 0.0          # 선회 주행이 계속된다

    # 차도 위험 해소 → clear_sec 유지 → CRUISE 복귀. 재요청된 일시정지가
    # 복귀 후 첫 tick 에 적용된다 (poll_after_step 의 재요청 경로).
    apply_one(client, machine, runner, clear_frame, 2.3)
    assert machine.state is DriveState.ESCAPE   # clear 타이머 진행 중
    apply_one(client, machine, runner, clear_frame, 2.8)
    assert machine.state is DriveState.CRUISE
    assert runner.poll_before_step(machine)     # 이제야 urgent — 즉시 정지 가능 상태
    paused = machine.step(clear_frame, None, 2.9)
    runner.poll_after_step(machine)
    assert machine.state is DriveState.PAUSED
    assert paused.brake
    assert client.results()[-1]["status"] == "COMPLETED"


def _cancel(command_id, mission_id, *, expires_at="2099-01-01T00:00:00.000Z"):
    """백엔드가 보내는 CANCEL_MISSION 모양 그대로 (parameters.mission_id 포함)."""
    command = mqtt_command(command_id, "CANCEL_MISSION", {
        "mission_id": mission_id, "reason": "관리자 요청으로 임무 취소",
    })
    command["expires_at"] = expires_at
    return command


def _start(wired, clear_frame, mission_id="MIS-1", now=1.0):
    client, _state, machine, runner = wired
    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": mission_id, "zone_id": "A-01",
    }))
    apply_one(client, machine, runner, clear_frame, now)


def test_cancel_closes_the_server_mission_with_no_local_mission(wired, clear_frame):
    """보관함이 비어도 취소는 거부하지 않는다 — 서버 미션을 닫아야 한다.

    종전에는 NO_ACTIVE_MISSION 으로 REJECTED 였고, 그러면 서버 미션이 RUNNING 으로
    남아 다음 START_MISSION 까지 막혔다. 재기동으로 보관함이 사라진 뒤 관제가 취소를
    누르는 것이 정확히 이 경우다.
    """
    client, state, machine, runner = wired
    assert state.snapshot().mission_id is None

    client.commands.put(_cancel("cancel", "MIS-GHOST"))
    apply_one(client, machine, runner, clear_frame, 1.0)

    result = client.results()[-1]
    assert (result["status"], result["reason_code"]) == ("COMPLETED", "OK")
    event = _mission_events(client.published)[-1]
    assert event["event_type"] == "MISSION_CANCELLED"
    assert event["mission_id"] == "MIS-GHOST"
    # 세어 둔 값이 없으므로 집계는 0 이다 (계약이 result 전체를 요구한다).
    assert event["result"]["incident_count"] == 0


def test_expired_cancel_is_still_applied(wired, clear_frame):
    """끊긴 사이 브로커가 물고 있던 취소가 30초를 넘겨 도착하는 것은 정상 경로다."""
    _start(wired, clear_frame)
    client, state, machine, runner = wired

    client.commands.put(_cancel("cancel", "MIS-1", expires_at="2000-01-01T00:00:00.000Z"))
    apply_one(client, machine, runner, clear_frame, 2.0)

    assert client.results()[-1]["status"] == "COMPLETED"
    assert state.snapshot().mission_id is None
    assert _mission_events(client.published)[-1]["event_type"] == "MISSION_CANCELLED"


def test_cancel_preempts_pending_and_does_not_block_the_next_mission(wired, clear_frame):
    """취소는 대기 명령을 선점하고 그 자리에서 닫는다 — 다음 START 가 막히면 안 된다."""
    _start(wired, clear_frame)
    client, state, machine, runner = wired

    # ESCAPE 중이 아니어도 COMPLETE 는 IDLE 을 기다리므로 이 tick 에 적용되지 않는다.
    client.commands.put(mqtt_command("complete", "COMPLETE_MISSION", {}))
    runner.poll_before_step(machine)
    assert runner.pending_count == 1

    client.commands.put(_cancel("cancel", "MIS-1"))
    apply_one(client, machine, runner, clear_frame, 2.0)

    assert runner.pending_count == 0
    superseded = [r for r in client.results() if r["command_id"] == "complete"]
    assert superseded[-1]["status"] == "REJECTED"
    assert state.snapshot().mission_id is None

    # 취소 직후 새 미션이 '대기 충돌' 로 거부되지 않는다.
    client.commands.put(mqtt_command("start-2", "START_MISSION", {
        "mission_id": "MIS-2", "zone_id": "A-02",
    }))
    apply_one(client, machine, runner, clear_frame, 3.0)
    assert state.snapshot().mission_id == "MIS-2"


def test_late_cancel_does_not_kill_a_different_live_mission(wired, clear_frame):
    """지목된 미션만 닫는다 — 만료를 보지 않기로 한 대가를 여기서 막는다."""
    _start(wired, clear_frame, mission_id="MIS-NEW")
    client, state, machine, runner = wired

    client.commands.put(_cancel("cancel", "MIS-OLD"))
    apply_one(client, machine, runner, clear_frame, 2.0)

    assert client.results()[-1]["status"] == "COMPLETED"
    # 살아 있는 미션은 그대로다 — 보관함도 mode 도 건드리지 않는다.
    assert state.snapshot().mission_id == "MIS-NEW"
    assert state.snapshot().mode == "PATROLLING"
    event = _mission_events(client.published)[-1]
    assert (event["event_type"], event["mission_id"]) == ("MISSION_CANCELLED", "MIS-OLD")


def test_cancel_during_estop_keeps_safe_stop_but_closes_the_mission(wired, clear_frame):
    """ESTOP 으로 선 로봇을 READY 로 바꿔 보고하지 않는다 — 미션만 닫는다."""
    _start(wired, clear_frame)
    client, state, machine, runner = wired
    client.commands.put(mqtt_command("estop", "EMERGENCY_STOP", {"reason": "operator"}))
    apply_one(client, machine, runner, clear_frame, 2.0)
    assert machine.state is DriveState.ESTOP
    assert state.snapshot().mode == "SAFE_STOP"

    client.commands.put(_cancel("cancel", "MIS-1"))
    apply_one(client, machine, runner, clear_frame, 3.0)

    assert client.results()[-1]["status"] == "COMPLETED"
    assert state.snapshot().mission_id is None
    assert (state.snapshot().mode, state.snapshot().reason_code) == (
        "SAFE_STOP", "REMOTE_ESTOP")
    assert _mission_events(client.published)[-1]["event_type"] == "MISSION_CANCELLED"


def test_cancel_survives_a_tick_that_drops_the_request(wired, clear_frame):
    """ESCAPE 가드가 취소 요청을 버린 tick 이 있어도 대기가 살아 재요청된다.

    만료로 끝나지 않게 만든 대가다 — 재요청이 없으면 그 대기는 영원히 남고
    result 도 MISSION_CANCELLED 도 안 나간다.
    """
    _start(wired, clear_frame)
    client, state, machine, runner = wired
    roadway = SegmentationFrame(timestamp=0.0, sidewalk_detected=True,
                               roadway_encroachment_risk=True)

    client.commands.put(_cancel("cancel", "MIS-1"))
    apply_one(client, machine, runner, roadway, 2.0)
    assert machine.state is DriveState.ESCAPE      # 취소가 이 tick 에 버려졌다
    assert runner.pending_count == 1
    assert state.snapshot().mission_id == "MIS-1"

    for tick in range(3, 13):                      # 선회가 풀리면 첫 tick 에 적용된다
        apply_one(client, machine, runner, clear_frame, float(tick))
        if machine.state is DriveState.IDLE:
            break
    assert machine.state is DriveState.IDLE
    assert runner.pending_count == 0
    assert state.snapshot().mission_id is None
    assert _mission_events(client.published)[-1]["event_type"] == "MISSION_CANCELLED"


def test_complete_mission_is_rejected_while_paused(wired, clear_frame):
    client, state, machine, runner = wired
    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    apply_one(client, machine, runner, clear_frame, 1.0)
    client.commands.put(mqtt_command("pause", "PAUSE_MISSION", {"reason": "operator"}))
    apply_one(client, machine, runner, clear_frame, 2.0)

    client.commands.put(mqtt_command("complete", "COMPLETE_MISSION", {}))
    runner.poll_before_step(machine)

    assert client.results()[-1]["status"] == "REJECTED"
    assert client.results()[-1]["reason_code"] == "ALREADY_IN_STATE"
    assert state.snapshot().mission_state == "PAUSED"
    assert [event["event_type"] for event in _mission_events(client.published)] == [
        "MISSION_STARTED", "MISSION_PAUSED"
    ]


def test_complete_mission_rejects_a_different_mission_id(wired, clear_frame):
    client, state, machine, runner = wired
    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    apply_one(client, machine, runner, clear_frame, 1.0)

    client.commands.put(mqtt_command("complete-other", "COMPLETE_MISSION", {
        "mission_id": "MIS-OTHER",
        "completion_reason": "OPERATOR_COMPLETED",
    }))
    runner.poll_before_step(machine)

    assert client.results()[-1]["status"] == "REJECTED"
    assert client.results()[-1]["reason_code"] == "MISSION_MISMATCH"
    assert state.snapshot().mission_id == "MIS-1"


def test_estop_preempts_pending_complete_without_false_completion(wired, clear_frame):
    client, state, machine, runner = wired
    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    apply_one(client, machine, runner, clear_frame, 1.0)

    client.commands.put(mqtt_command("complete", "COMPLETE_MISSION", {}))
    assert runner.poll_before_step(machine)
    client.commands.put(mqtt_command("estop", "EMERGENCY_STOP", {"reason": "operator"}))
    assert runner.poll_before_step(machine)
    command = machine.step(clear_frame, None, 2.0)
    assert machine.state is DriveState.ESTOP and command.brake
    runner.poll_after_step(machine)

    final = {result["command_id"]: result for result in client.results()
             if result["status"] != "ACCEPTED"}
    assert final["complete"]["status"] == "REJECTED"
    assert final["complete"]["reason_code"] == "SAFETY_CONDITION_NOT_CLEARED"
    assert final["estop"]["status"] == "COMPLETED"
    assert state.snapshot().mission_state == "PAUSED"
    assert [event["event_type"] for event in _mission_events(client.published)] == [
        "MISSION_STARTED", "MISSION_PAUSED"
    ]


def test_expired_command_uses_backend_contract_reason(wired):
    client, _state, machine, runner = wired
    command = mqtt_command("expired", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    })
    command["expires_at"] = "2000-01-01T00:00:00.000Z"
    client.commands.put(command)

    runner.poll_before_step(machine)

    assert client.results()[-1]["status"] == "EXPIRED"
    assert client.results()[-1]["reason_code"] == "COMMAND_TTL_EXPIRED"


class _PassivePaho:
    pass


def _receipt(event_id, status="STORED"):
    return {
        "schema_version": "2.0",
        "event_id": event_id,
        "ingest_status": status,
        "incident_id": None,
        "received_at": "2026-08-04T01:10:23.456Z",
    }


def test_receipt_required_outbox_waits_for_backend_confirmation(tmp_path):
    event_id = "0f2c71ad-cce5-4f34-a6f8-7b41520e5663"
    outbox = Outbox(tmp_path / "outbox.sqlite3", receipt_required=True)
    outbox.enqueue(event_id, TopicKind.EVENT_MISSION, {"event_id": event_id})
    outbox.mark_sent(event_id)
    assert outbox.pending_count() == 1

    config = MqttConfig(
        robot_id="RBT-TEST", host="localhost",
        outbox_path=str(tmp_path / "outbox.sqlite3"), receipt_required=True,
    )
    client = MqttClient(config, outbox=outbox, client_factory=lambda _config: _PassivePaho())
    message = SimpleNamespace(
        topic=client.topics.of(TopicKind.INGEST_RECEIPT),
        payload=codec.encode(_receipt(event_id)),
    )
    client._on_message(None, None, message)

    assert outbox.pending_count() == 0
    outbox.close()


def test_rejected_receipt_is_preserved_without_blind_retry(tmp_path):
    event_id = "0f2c71ad-cce5-4f34-a6f8-7b41520e5663"
    outbox = Outbox(tmp_path / "outbox.sqlite3", receipt_required=True)
    outbox.enqueue(event_id, TopicKind.EVENT_MISSION, {"event_id": event_id})
    outbox.mark_sent(event_id)

    assert outbox.record_receipt(event_id, "REJECTED")
    assert outbox.pending_count() == 0
    assert outbox.prune_sent("9999-12-31T23:59:59.999Z") == 0
    outbox.close()


def test_existing_outbox_is_migrated_without_deleting_events(tmp_path):
    path = tmp_path / "outbox.sqlite3"
    connection = sqlite3.connect(path)
    connection.executescript("""
        CREATE TABLE outbox (
            event_id TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            payload TEXT NOT NULL,
            created_at TEXT NOT NULL,
            attempted_at TEXT,
            attempts INTEGER NOT NULL DEFAULT 0,
            sent_at TEXT
        );
        CREATE INDEX outbox_pending ON outbox (sent_at, created_at);
    """)
    event_id = "0f2c71ad-cce5-4f34-a6f8-7b41520e5663"
    connection.execute(
        "INSERT INTO outbox (event_id, kind, payload, created_at, sent_at)"
        " VALUES (?, ?, ?, ?, ?)",
        (event_id, "events/mission", '{"event_id":"' + event_id + '"}',
         "2026-08-04T01:00:00.000Z", "2026-08-04T01:00:01.000Z"),
    )
    connection.commit()
    connection.close()

    outbox = Outbox(path, receipt_required=True)
    assert outbox.pending_count() == 1
    assert outbox.record_receipt(event_id, "DUPLICATE")
    assert outbox.pending_count() == 0
    outbox.close()


def test_client_subscribes_to_command_and_receipt_topics(tmp_path):
    class Subscriber:
        def __init__(self):
            self.calls = []

        def subscribe(self, topic, qos):
            self.calls.append((topic, qos))
            return 0, len(self.calls)

    config = MqttConfig(
        robot_id="RBT-TEST", host="localhost",
        outbox_path=str(tmp_path / "outbox.sqlite3"),
    )
    client = MqttClient(config, client_factory=lambda _config: _PassivePaho())
    subscriber = Subscriber()
    client._subscribe_inbound(subscriber)

    assert subscriber.calls == [
        (client.topics.of(TopicKind.COMMAND_REQUEST), 1),
        (client.topics.of(TopicKind.INGEST_RECEIPT), 1),
    ]
    client.outbox.close()


def test_estop_ack_is_after_physical_stop(wired, clear_frame):
    client, state, machine, runner = wired
    machine.request_start()
    machine.step(clear_frame, None, 0.0)
    state.start_patrol()
    state.start_mission("MIS-1", zone_id="A-01")

    client.commands.put(mqtt_command("stop", "EMERGENCY_STOP", {"reason": "operator"}))
    runner.poll_before_step(machine)
    assert client.results() == []  # 브레이크 적용 전에는 ACK도 보내지 않는다.
    command = machine.step(clear_frame, None, 1.0)
    assert machine.state is DriveState.ESTOP and command.brake
    runner.poll_after_step(machine)
    assert [result["status"] for result in client.results()] == ["ACCEPTED", "COMPLETED"]
    assert state.snapshot().reason_code == "REMOTE_ESTOP"
    assert state.snapshot().mission_state == "PAUSED"
    assert [event["event_type"] for event in _mission_events(client.published)] == [
        "MISSION_PAUSED"
    ]


def test_estop_preempts_a_pending_start_without_double_mission(wired):
    client, state, machine, runner = wired
    no_sidewalk = SegmentationFrame(timestamp=1.0, sidewalk_detected=False)
    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    apply_one(client, machine, runner, no_sidewalk, 1.0)
    assert machine.state is DriveState.IDLE and runner.pending_count == 1

    client.commands.put(mqtt_command("stop", "EMERGENCY_STOP", {"reason": "operator"}))
    apply_one(client, machine, runner, no_sidewalk, 2.0)
    final = {result["command_id"]: result for result in client.results()
             if result["status"] != "ACCEPTED"}
    assert final["start"]["status"] == "REJECTED"
    assert final["stop"]["status"] == "COMPLETED"
    assert state.snapshot().mission_id is None


def test_clear_estop_requires_explicit_resume(wired, clear_frame):
    client, state, machine, runner = wired
    machine.request_start()
    machine.step(clear_frame, None, 0.0)
    state.start_patrol()
    state.start_mission("MIS-1", zone_id="A-01")
    machine.request_estop()
    machine.step(clear_frame, None, 1.0)
    state.safe_stop("REMOTE_ESTOP")

    client.commands.put(mqtt_command(
        "clear", "CLEAR_EMERGENCY_STOP", {"reason": "operator"}))
    apply_one(client, machine, runner, clear_frame, 2.0)
    assert machine.state is DriveState.IDLE
    assert state.snapshot().mode == "PAUSED"
    assert state.snapshot().mission_state == "PAUSED"

    client.commands.put(mqtt_command("resume", "RESUME_MISSION", {}))
    apply_one(client, machine, runner, clear_frame, 3.0)
    assert machine.state is DriveState.CRUISE
    assert state.snapshot().mode == "PATROLLING"


def test_remote_estop_clear_resume_chain_with_projector(params, clear_frame):
    """서버가 실제로 쏘는 3종(긴급정지 → 해제 → 재개)이 기기에 반영되는가.

    기존 test_clear_estop_requires_explicit_resume 는 ESTOP 을 machine.request_estop()
    과 state.safe_stop() 으로 **직접 만들어** 시작한다. 그래서 EMERGENCY_STOP 명령이
    reason_code 를 REMOTE_ESTOP 으로 남기는지, 그 값이 다음 tick 까지 살아남는지를
    검증하지 않는다 — 그런데 CLEAR_EMERGENCY_STOP 의 전제조건이 정확히 그 값이라
    (commands.py `_precondition`), 어긋나면 현장에서 해제가 거절된다.

    투영기(DriveStateProjector)를 실제 런타임과 **같은 순서로** 함께 돌린다
    (runtime.poll_after_step: commands → projector). 투영기는 ESTOP 상태를 보면
    safe_stop("INTERNAL_ERROR") 로 덮으려 하므로, 순서가 뒤집히거나 조기 return 이
    사라지면 여기서 걸린다.
    """
    client, state, machine, runner = wired_session(params)
    projector = DriveStateProjector(state)

    def tick(now, frame=clear_frame):
        runner.poll_before_step(machine)
        command = machine.step(frame, None, now)
        runner.poll_after_step(machine)      # 실기: apply_command 직후
        projector.sync(machine)              # 실기: commands 다음
        return command

    # 순찰 중 — 서버 START_MISSION 으로 미션을 연다.
    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    tick(1.0)
    assert machine.state is DriveState.CRUISE
    assert state.snapshot().mission_state == "RUNNING"

    # 1) 긴급 정지 — 바퀴가 멈춘 tick 에서 ACCEPTED·COMPLETED 가 함께 나간다.
    client.commands.put(mqtt_command("stop", "EMERGENCY_STOP", {"reason": "operator"}))
    stopped = tick(2.0)
    assert machine.state is DriveState.ESTOP
    assert stopped.brake and stopped.throttle_pct == 0
    assert [r["status"] for r in client.results()[-2:]] == ["ACCEPTED", "COMPLETED"]
    snapshot = state.snapshot()
    assert (snapshot.mode, snapshot.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")
    assert snapshot.mission_state == "PAUSED"      # RUNNING 이었으므로 MISSION_PAUSED

    # 정지 상태로 몇 tick 더 돌아도 투영기가 reason_code 를 덮지 않아야 한다 —
    # 덮이면(INTERNAL_ERROR) 아래 해제가 SAFETY_CONDITION_NOT_CLEARED 로 거절된다.
    tick(3.0)
    tick(4.0)
    assert state.snapshot().reason_code == "REMOTE_ESTOP"

    # 2) 긴급정지 해제 — IDLE 로 나오고 미션은 PAUSED 로 남는다(자동 출발 없음).
    client.commands.put(mqtt_command(
        "clear", "CLEAR_EMERGENCY_STOP", {"reason": "operator"}))
    tick(5.0)
    assert machine.state is DriveState.IDLE
    snapshot = state.snapshot()
    assert (snapshot.mode, snapshot.mission_state) == ("PAUSED", "PAUSED")
    assert client.results()[-1]["status"] == "COMPLETED"

    # 3) 임무 재개 — 해제 뒤 FSM 은 PAUSED 가 아니라 IDLE 이므로 재개는
    #    request_start() 경로를 탄다. 인도가 보이는 프레임이라 CRUISE 로 나간다.
    client.commands.put(mqtt_command("resume", "RESUME_MISSION", {}))
    tick(6.0)
    assert machine.state is DriveState.CRUISE
    snapshot = state.snapshot()
    assert (snapshot.mode, snapshot.mission_state) == ("PATROLLING", "RUNNING")

    mission_events = [payload["event_type"] for kind, payload in client.published
                      if kind is TopicKind.EVENT_MISSION]
    assert mission_events == ["MISSION_STARTED", "MISSION_PAUSED", "MISSION_RESUMED"]


def test_self_triggered_estop_pauses_the_mission_once(params, clear_frame):
    """로봇이 스스로 선 경우(차도 덮임)도 미션을 PAUSED 로 내린다 — 웹 ESTOP 과 동일.

    종전에는 투영기만 지나 미션이 RUNNING 으로 남았고, 서버 DB 에 '수행 중인 미션인데
    로봇은 안전정지'라는 조합이 생겼다. 정지가 계속되는 동안 매 tick 재발행하지
    않는 것까지 확인한다 (이미 SAFE_STOP 이면 조기 return).
    """
    client, state, machine, runner = wired_session(params)
    events = EventPublisher(client, state, sensor_fault_stops_driving=False)
    projector = DriveStateProjector(state, events)
    roadway = SegmentationFrame(timestamp=0.0, sidewalk_detected=True,
                                roadway_critical=True)

    def tick(now, frame):
        runner.poll_before_step(machine)
        machine.step(frame, None, now)
        runner.poll_after_step(machine)
        projector.sync(machine)

    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    tick(1.0, clear_frame)
    assert state.snapshot().mission_state == "RUNNING"

    for now in (2.0, 3.0, 4.0):
        tick(now, roadway)

    snapshot = state.snapshot()
    assert machine.state is DriveState.ESTOP
    assert (snapshot.mode, snapshot.reason_code) == ("SAFE_STOP", "INTERNAL_ERROR")
    assert snapshot.mission_state == "PAUSED"

    mission_events = [payload["event_type"] for kind, payload in client.published
                      if kind is TopicKind.EVENT_MISSION]
    assert mission_events == ["MISSION_STARTED", "MISSION_PAUSED"]


def test_remote_clear_estop_is_rejected_for_a_local_estop(params, clear_frame):
    """물리·자동 ESTOP 은 웹 해제로 못 푼다 — 현장에서 반드시 알아야 하는 동작.

    Xbox B·터치 오조작·차도 덮임(roadway_critical)으로 걸린 ESTOP 은 투영기가
    reason_code 를 INTERNAL_ERROR 로 남긴다. 서버 enum 에 ROADWAY_RISK 가 없어
    생긴 계약 공백이며(runtime.py 주석), 그 결과 CLEAR_EMERGENCY_STOP 의 전제조건이
    깨져 거절된다. 해제는 현장에서(터치·Xbox A) 해야 한다.
    """
    client, state, machine, runner = wired_session(params)
    projector = DriveStateProjector(state)

    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    runner.poll_before_step(machine)
    machine.step(clear_frame, None, 1.0)
    runner.poll_after_step(machine)
    projector.sync(machine)

    machine.request_estop()          # 조작자 B 버튼 / 터치 오조작과 같은 경로
    machine.step(clear_frame, None, 2.0)
    projector.sync(machine)
    assert state.snapshot().reason_code == "INTERNAL_ERROR"

    client.commands.put(mqtt_command(
        "clear", "CLEAR_EMERGENCY_STOP", {"reason": "operator"}))
    runner.poll_before_step(machine)
    machine.step(clear_frame, None, 3.0)
    runner.poll_after_step(machine)
    result = client.results()[-1]
    assert result["status"] == "REJECTED"
    assert result["reason_code"] == "SAFETY_CONDITION_NOT_CLEARED"
    assert machine.state is DriveState.ESTOP     # 여전히 정지 — 현장 해제가 필요하다


def test_web_estop_is_reported_as_a_remote_estop(params, clear_frame):
    """웹 대시보드 비상 정지는 관제가 누른 것과 **같은 사건**으로 보고한다.

    앞 테스트의 물리 정지(Xbox B·터치)와 갈리는 자리다: 웹 버튼은 원격 조작면이므로
    관제가 CLEAR_EMERGENCY_STOP 으로 풀 수 있어야 한다. 종전에는 이것도
    INTERNAL_ERROR 로 나가 관제가 풀 수 없었고, 그러면 그 뒤 RESUME·START 까지 전부
    거부되어 버튼 한 번에 원격 조작이 통째로 잠겼다.
    """
    client, state, machine, runner = wired_session(params)
    projector = DriveStateProjector(state, runner._events)

    def tick(now):
        runner.poll_before_step(machine)
        machine.step(clear_frame, None, now)
        runner.poll_after_step(machine)
        projector.sync(machine)

    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    tick(1.0)

    # web_test.DrivingSession.request_estop 과 같은 순서 (알림 먼저, FSM 요청 뒤)
    projector.note_operator_estop()
    machine.request_estop()
    tick(2.0)

    snapshot = state.snapshot()
    assert (snapshot.mode, snapshot.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")
    assert snapshot.mission_state == "PAUSED"

    client.commands.put(mqtt_command(
        "clear", "CLEAR_EMERGENCY_STOP", {"reason": "operator"}))
    tick(3.0)
    assert client.results()[-1]["status"] == "COMPLETED"
    assert machine.state is DriveState.IDLE

    client.commands.put(mqtt_command("resume", "RESUME_MISSION", {}))
    tick(4.0)
    assert client.results()[-1]["status"] == "COMPLETED"
    assert machine.state is DriveState.CRUISE


def test_local_estop_release_unpins_the_server_projection(params, clear_frame):
    """현장에서 푼 ESTOP 은 서버 투영도 함께 풀어야 한다.

    ESTOP 의 출구는 IDLE 뿐이고 미션은 진입 때 PAUSED 로 내려가 있다. 종전에는
    _project_idle 의 세 분기 어디에도 걸리지 않아 mode 가 SAFE_STOP 으로 남았고,
    그 값에서는 CLEAR·RESUME·START 가 전부 거부된다 — 로컬에서 푼 정지가 원격 조작을
    영구히 잠갔다. 물리 정지(원격 해제 불가)에서도 복구 경로가 있어야 하므로
    이 확인은 앞 테스트와 별개다.
    """
    client, state, machine, runner = wired_session(params)
    projector = DriveStateProjector(state, runner._events)

    def tick(now):
        runner.poll_before_step(machine)
        machine.step(clear_frame, None, now)
        runner.poll_after_step(machine)
        projector.sync(machine)

    client.commands.put(mqtt_command("start", "START_MISSION", {
        "mission_id": "MIS-1", "zone_id": "A-01",
    }))
    tick(1.0)

    machine.request_estop()          # 물리 정지 — 알림 없음(INTERNAL_ERROR 로 나간다)
    tick(2.0)
    assert state.snapshot().reason_code == "INTERNAL_ERROR"

    machine.request_estop_release()  # 현장에서 해제 (터치·Xbox A·웹 해제 버튼)
    tick(3.0)
    snapshot = state.snapshot()
    assert machine.state is DriveState.IDLE
    assert (snapshot.mode, snapshot.reason_code) == ("PAUSED", "REMOTE_PAUSE")
    assert snapshot.mission_state == "PAUSED"

    # 관제가 다시 붙는다 — 해제 뒤에는 재개가 먹어야 한다.
    client.commands.put(mqtt_command("resume", "RESUME_MISSION", {}))
    tick(4.0)
    assert client.results()[-1]["status"] == "COMPLETED"
    assert machine.state is DriveState.CRUISE
    assert state.snapshot().mode == "PATROLLING"


def test_local_estop_release_without_a_mission_returns_to_ready(params, clear_frame):
    """미션 없이(로컬 시험 주행) 건 ESTOP 을 풀면 READY 로 돌아온다."""
    client, state, machine, runner = wired_session(params)
    projector = DriveStateProjector(state, runner._events)

    machine.request_start()
    machine.step(clear_frame, None, 1.0)
    projector.sync(machine)

    machine.request_estop()
    machine.step(clear_frame, None, 2.0)
    projector.sync(machine)
    assert state.snapshot().mode == "SAFE_STOP"

    machine.request_estop_release()
    machine.step(clear_frame, None, 3.0)
    projector.sync(machine)
    assert (state.snapshot().mode, state.snapshot().reason_code) == ("READY", "NONE")


def test_paused_cancel_works_during_camera_loss(params, clear_frame):
    machine = StateMachine(params, now=0.0)
    machine.request_start()
    machine.step(clear_frame, None, 0.0)
    machine.request_pause()
    machine.step(None, None, 1.0)
    assert machine.state is DriveState.PAUSED
    machine.request_cancel()
    command = machine.step(None, None, 2.0)
    assert machine.state is DriveState.IDLE and command.brake


class _ShutdownStub:
    """`CommunicationRuntime.shutdown()` 이 미션을 닫는 부분만 떼어 쓴다.

    실제 shutdown 은 gps·uploader·소켓까지 건드리므로, 미션 종료 판단만 보려면
    필요한 두 속성만 있으면 된다.
    """

    def __init__(self, state, events):
        self.state = state
        self._events = events


def _mission_events(published):
    return [payload for kind, payload in published if kind is TopicKind.EVENT_MISSION]


def test_shutdown_closes_the_active_mission(tmp_path):
    client = RecordingClient()
    state = RobotState(storage_dir=tmp_path)
    state.boot_completed()
    state.start_patrol()
    state.start_mission("MIS-1", zone_id="A-01")
    state.record_incident()
    events = EventPublisher(client, state, sensor_fault_stops_driving=False)

    CommunicationRuntime._close_active_mission(_ShutdownStub(state, events))

    finished = _mission_events(client.published)
    assert [event["event_type"] for event in finished] == ["MISSION_FAILED"]
    assert finished[0]["result"]["completion_reason"] == "GRACEFUL_SHUTDOWN"
    assert finished[0]["result"]["incident_count"] == 1
    # 닫았으면 사본도 지워져야 한다 — 남으면 다음 기동이 같은 미션을 또 닫는다.
    assert state.mission_id is None
    assert not (tmp_path / MISSION_FILE_NAME).exists()


def test_shutdown_without_an_active_mission_publishes_nothing(tmp_path):
    client = RecordingClient()
    state = RobotState(storage_dir=tmp_path)
    state.boot_completed()
    events = EventPublisher(client, state, sensor_fault_stops_driving=False)

    CommunicationRuntime._close_active_mission(_ShutdownStub(state, events))

    assert _mission_events(client.published) == []


def test_runtime_build_uses_current_event_publisher_contract(tmp_path):
    """실제 main 경로의 조립 인자가 EventPublisher 생성자와 어긋나지 않는다."""
    comms_config = tmp_path / "comms.yaml"
    comms_config.write_text(
        """
robot:
  id: RBT-TEST
mqtt:
  host: localhost
  port: 1883
  username: RBT-TEST
  password: test-password
  require_tls: false
  outbox_path: var/outbox.sqlite3
telemetry:
  interval_sec: 5.0
incident:
  model_name: test-model
  model_version: test-version
""".strip(),
        encoding="utf-8",
    )
    robot_config = {
        "hardware": {
            "gps": {"device": "missing-test-device", "baud": 9600},
        },
    }

    runtime = CommunicationRuntime.build(
        str(comms_config), robot_config, live=False,
    )
    try:
        assert isinstance(runtime._events, EventPublisher)
        assert runtime._events._obstacle_source is None
    finally:
        runtime.client.outbox.close()


def _build_runtime(tmp_path, extra_yaml: str, **build_kwargs):
    """실제 main 경로와 같은 조립. `extra_yaml` 만 comms.yaml 뒤에 붙인다."""
    comms_config = tmp_path / "comms.yaml"
    comms_config.write_text(
        """
robot:
  id: RBT-TEST
mqtt:
  host: localhost
  port: 1883
  username: RBT-TEST
  password: test-password
  require_tls: false
  outbox_path: var/outbox.sqlite3
incident:
  model_name: test-model
  model_version: test-version
  evidence_long_edge_px: 1280
  evidence_jpeg_quality: 80
  evidence_max_bytes: 512000
  evidence_min_jpeg_quality: 40
""".strip() + "\n" + extra_yaml,
        encoding="utf-8",
    )
    robot_config = {"hardware": {"gps": {"device": "missing-test-device", "baud": 9600}}}
    return CommunicationRuntime.build(str(comms_config), robot_config, live=False,
                                      **build_kwargs)


def test_runtime_build_gives_the_uploader_the_configured_endpoint(tmp_path):
    """업로드 config 는 comms.yaml 에만 있다 — 조립이 그걸 uploader 에 넘겨야 한다.

    안 넘기면 uploader 가 기본 경로(config.yaml)를 읽고, 그 파일엔 upload 절이
    없어서 매 업로드가 ConfigError 로 조용히 실패한다 (0804 실외에서 실측 —
    사진이 서버에 한 장도 안 올라갔다).
    """
    runtime = _build_runtime(tmp_path, """
robot_uuid: 3c85a85b-e875-4c7b-8af0-c0cc4b3a8bf5
upload:
  url: https://example.test/api/v1/images
  api_key: test-key
""".strip() + "\n")
    try:
        config = runtime._uploader._upload.keywords["config"]
        assert config.url == "https://example.test/api/v1/images"
        assert config.api_key == "test-key"
    finally:
        runtime._uploader.close()
        runtime.client.outbox.close()


def test_runtime_build_skips_evidence_when_upload_is_unconfigured(tmp_path):
    """URL·키가 비면 사진을 만들지도 않는다 — 올릴 곳이 없는 JPEG 은 eMMC 만 채운다.

    결함 보고 자체는 계속된다 (image_id: null, §8.3).
    """
    runtime = _build_runtime(
        tmp_path, "robot_uuid: 3c85a85b-e875-4c7b-8af0-c0cc4b3a8bf5\n")
    try:
        assert runtime._uploader is None
        assert runtime._evidence is None
    finally:
        runtime.client.outbox.close()


def test_runtime_build_shares_an_external_gps_reader(tmp_path):
    """주행 쪽이 넘긴 GPS 리더는 그대로 쓰고, 수명(start/stop)은 넘겨받지 않는다.

    여기서 리더를 또 만들면 같은 시리얼 장치를 두 리더가 열고 NMEA 문장을 나눠
    읽어 **양쪽 좌표가 다** 깨진다 — 실운영 조합(--perception real --comms-config)
    에서 지도 핀·telemetry 위치가 통째로 무효가 되는 원인이었다.
    """
    class _StubReader:
        def start(self):
            raise AssertionError("빌린 리더를 start 하면 안 된다 — 수명은 호출자 소유")

        def stop(self):
            raise AssertionError("빌린 리더를 stop 하면 안 된다 — 수명은 호출자 소유")

        def status(self):
            return {}

    stub = _StubReader()
    runtime = _build_runtime(tmp_path, "", gps_reader=stub)
    try:
        assert runtime.gps_reader is stub          # 새로 만들지 않고 공유한다
        assert runtime._owns_gps_reader is False   # start()/shutdown() 이 건드리지 않는다
    finally:
        runtime.client.outbox.close()

    owned = _build_runtime(tmp_path, "")
    try:
        assert owned._owns_gps_reader is True      # 안 넘기면 종전대로 스스로 소유
    finally:
        owned.client.outbox.close()


def _incident_kwargs(**overrides):
    """incident_event 의 필수 인자 한 벌. 검사 대상 값만 덮어쓴다."""
    kwargs = {
        "occurred_at": codec.utc_now(),
        "captured_at": codec.utc_now(),
        "location_valid": False,
        "lat": None,
        "lon": None,
        "zone_id": "A-01",
        "block_type": "UNKNOWN",
        "image_id": None,
        "image_width": 1280,
        "image_height": 720,
        "model_name": "sidewalk-seg",
        "model_version": "0731",
    }
    kwargs.update(overrides)
    return kwargs


def test_missing_incident_carries_no_box_or_confidence():
    """미설치는 파손과 같은 event_type 이고 bbox_norm·confidence 만 null 이다."""
    messages = MessageFactory("RBT-TEST")
    payload = messages.incident_event(**_incident_kwargs(defect_type="MISSING"))

    assert payload["event_type"] == "BRAILLE_BLOCK_INCIDENT_DETECTED"
    assert payload["incident"] == {
        "asset_type": "BRAILLE_BLOCK",
        "block_type": "UNKNOWN",
        "defect_type": "MISSING",
        "confidence": None,
    }
    assert payload["evidence"]["bbox_norm"] is None
    # 사진 규격은 파손과 같다 — 좌표 없는 사진이어도 지도 핀의 증거는 사진이다.
    assert payload["evidence"]["image_width"] == 1280
    assert payload["evidence"]["image_height"] == 720


def test_damaged_incident_still_requires_box_and_confidence():
    """MISSING 을 열어 준 것이 파손 쪽 필수값까지 풀어 주지는 않는다.

    서버는 둘이 빠진 파손 payload 를 조용히 버리고 로봇은 PUBACK 을 받아 Outbox 에서
    지운다 — 이 예외가 유실을 알아챌 수 있는 마지막 자리다.
    """
    messages = MessageFactory("RBT-TEST")
    with pytest.raises(ValueError, match="bbox_norm"):
        messages.incident_event(**_incident_kwargs(confidence=0.9))
    with pytest.raises(ValueError, match="confidence"):
        messages.incident_event(**_incident_kwargs(bbox_norm=[0.1, 0.1, 0.9, 0.9]))


def test_report_missing_braille_publishes_without_an_uploader():
    """업로드 구성이 없어도(ROBOT_UUID 없음) 미설치 사건은 그대로 나간다."""
    client = RecordingClient()
    state = RobotState()
    state.boot_completed()
    runtime = CommunicationRuntime.__new__(CommunicationRuntime)
    runtime._events = EventPublisher(client, state, model_name="seg", model_version="1")
    runtime._uploader = runtime._evidence = None

    runtime.report_missing_braille(SimpleNamespace(shape=(720, 1280, 3)))

    kind, payload = client.published[-1]
    assert kind is TopicKind.EVENT_INCIDENT
    assert payload["incident"]["defect_type"] == "MISSING"
    assert payload["evidence"]["image_id"] is None


def test_report_missing_braille_without_a_frame_publishes_nothing():
    """프레임이 없으면 image_width 를 만들 수 없다 — 지어내지 않고 건너뛴다.

    _events 를 None 으로 둔 것이 검사다: 발행을 시도했다면 AttributeError 로 터진다.
    """
    runtime = CommunicationRuntime.__new__(CommunicationRuntime)
    runtime._events = runtime._uploader = runtime._evidence = None

    runtime.report_missing_braille(None)


def test_runtime_signals_report_last_applied_command():
    signals = RuntimeSignals(live=True)
    assert not signals.moving()
    signals.update(DriveCommand(60.0, 0.0), camera_ok=True)
    assert signals.moving()
    assert signals.camera_health() == "OK"
    signals.update(DriveCommand(0.0, 0.0, brake=True), camera_ok=False)
    assert not signals.moving()
    assert signals.camera_health() == "FAULT"


def test_projector_distinguishes_avoid_hold_from_bypass(params, clear_frame):
    state = RobotState()
    state.boot_completed()
    state.start_patrol()
    state.start_mission("MIS-1", zone_id="A-01")
    machine = StateMachine(params, now=0.0)
    machine.request_start()
    machine.step(clear_frame, None, 0.0)
    projector = DriveStateProjector(state)

    # HOLD(정지 대기)는 **비껴갈 방향이 아예 없을 때만** 나온다 — 장애물이 보인다는
    # 것만으로는 정지하지 않고 곧바로 SWERVE로 비껴간다(0803 '자꾸 정지만 함' 수정).
    # 그래서 SAFE_STOP 투영을 보려면 all_directions_blocked를 세워야 한다.
    blocked = SegmentationFrame(
        timestamp=1.0,
        sidewalk_detected=True,
        obstacles=(ObstacleRegion(lateral_bias=0.3, proximity=1.0, blocking=True),),
        all_directions_blocked=True,
    )
    imu = MockImu()
    machine.step(blocked, imu.read(), 1.0)
    projector.sync(machine)
    assert machine.context.avoid_phase is AvoidPhase.HOLD
    assert state.snapshot().mode == "SAFE_STOP"

    # 재확인 시점에 비껴갈 방향이 생기면 회차가 아니라 회피 선회로 나간다 —
    # 로봇이 다시 움직이므로 투영은 SAFE_STOP에서 PATROLLING으로 돌아와야 한다.
    bypassable = SegmentationFrame(
        timestamp=2.0,
        sidewalk_detected=True,
        obstacles=(ObstacleRegion(lateral_bias=0.3, proximity=1.0, blocking=True),),
    )
    recheck = 1.0 + params.avoid_recheck_sec
    machine.step(bypassable, imu.read(), recheck)
    assert machine.context.avoid_phase is AvoidPhase.SWERVE
    projector.sync(machine)
    assert state.snapshot().mode == "PATROLLING"


def test_projector_shows_dead_end_reorientation(params, clear_frame):
    """재정향(후진·U턴)이 관제에 보여야 한다 — 종전에는 '순찰 중'으로 나갔다.

    DEAD_END 는 로봇이 뒤로 물러나며 방향을 되찾는 기동이고 후방은 카메라 사각이다.
    그 사이 서버가 PATROLLING 을 보고 있으면, 인도에서 후진하는 로봇을 아무도
    모른다. mode enum 에 '재정향'이 없어 가장 가까운 참(SAFE_STOP/OBSTACLE =
    장애물 때문에 정상 순찰을 못 한다)으로 투영한다.
    """
    state = RobotState()
    state.boot_completed()
    state.start_patrol()
    state.start_mission("MIS-1", zone_id="A-01")
    machine = StateMachine(params, now=0.0)
    machine.request_start()
    machine.step(clear_frame, None, 0.0)
    projector = DriveStateProjector(state)

    blocked = SegmentationFrame(
        timestamp=1.0, sidewalk_detected=True,
        obstacles=(ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True),),
        all_directions_blocked=True,
    )
    machine.step(blocked, None, 1.0)                      # AVOID(HOLD)
    recheck = 1.0 + params.avoid_recheck_sec
    machine.step(blocked, None, recheck)                  # → DEAD_END(후진)
    assert machine.state is DriveState.DEAD_END

    projector.sync(machine)
    snapshot = state.snapshot()
    assert (snapshot.mode, snapshot.reason_code) == ("SAFE_STOP", "OBSTACLE")

    # 탈출해서 순찰로 돌아오면 그 자리에서 풀린다 (형제 분기의 release_safe_stop)
    machine._context = replace(machine.context, state=DriveState.CRUISE)
    projector.sync(machine)
    assert state.snapshot().mode == "PATROLLING"


class TestIdleProjection:
    """IDLE 투영 — **미션이 도는데 스스로 섰으면 그 자체가 결함이다.**

    종전에는 사유 문자열 세 개로 갈랐고 매칭이 없으면 아무 일도 하지 않았다. 그래서
    가장 흔한 두 정지("후진 복구 상한 — 전 방향 막힘", "재정향 실패")가 서버에
    PATROLLING(순찰 중)으로 남았다 — 로봇은 인도에 서 있는데 관제는 모른다.
    세 문자열 중 `"회차 실패"` 는 0804 의 이름 변경(회차 → 재정향) 뒤로 FSM 이 한 번도
    내지 않는 죽은 값이었다. 이 클래스가 고정하는 것은 '어느 사유로 보고하는가'가 아니라
    **'빠지는 사유가 없다'** 이다.
    """

    def running(self, reason: str) -> RobotState:
        """미션이 RUNNING 인 채 로봇이 그 사유로 IDLE 에 떨어진 상태를 만든다."""
        state = RobotState()
        state.boot_completed()
        state.start_patrol()
        state.start_mission("MIS-1", zone_id="A-01")
        return state

    def sync(self, state, reason: str, params) -> None:
        machine = StateMachine(params, now=0.0)
        machine._context = replace(machine.context, state=DriveState.IDLE,
                                   reason=reason)
        DriveStateProjector(state).sync(machine)

    def test_stuck_reverse_recovery_is_reported(self, params):
        # 0805 실기에서 미션이 끝난 33회 중 11회가 이 사유였고, 그동안 서버는
        # 순찰 중으로 알고 있었다
        state = self.running("후진 복구 상한 — 전 방향 막힘")
        self.sync(state, "후진 복구 상한 — 전 방향 막힘 (U턴 탈출 3회 소진) "
                         "→ 정지 + 수동 대기", params)
        snapshot = state.snapshot()
        assert snapshot.mode == "ERROR"
        assert snapshot.reason_code == "INTERNAL_ERROR"

    def test_reorientation_failure_is_reported(self, params):
        # 종전 매칭 문자열은 '회차 실패'였는데 FSM 은 '재정향 실패'를 낸다
        state = self.running("재정향 실패")
        self.sync(state, "재정향 실패(회전 312° 초과 — 갇힘) → 정지 + 수동 대기", params)
        assert state.snapshot().mode == "ERROR"

    def test_an_unknown_reason_still_reports(self, params):
        """**이 시험이 요점이다** — 사유 문구가 또 바뀌어도 조용히 사라지지 않는다."""
        state = self.running("...")
        self.sync(state, "다음 사람이 새로 지은 사유 문구", params)
        assert state.snapshot().mode == "ERROR"

    def test_boundary_loss_still_maps_to_its_own_code(self, params):
        # 인지 계열만 따로 갈린다 (종전 동작 유지)
        state = self.running("경계 소실")
        self.sync(state, "인도 경계 소실 (U턴 탈출 3회 소진) → 정지 + 수동 대기", params)
        snapshot = state.snapshot()
        assert snapshot.mode == "ERROR"
        assert snapshot.reason_code == "BOUNDARY_LOST"

    def test_no_mission_returns_to_standby(self, params):
        # 미션 없이 도는 것은 로컬 시험이다 — 결함이 아니라 대기 복귀 (종전 동작)
        state = RobotState()
        state.boot_completed()
        state.start_patrol()
        self.sync(state, "재정향 실패(타임아웃) → 정지 + 수동 대기", params)
        assert state.snapshot().mode == "READY"

    def test_a_paused_mission_is_left_alone(self, params):
        """사람이 세워 둔 미션은 건드리지 않는다 — CLEAR_EMERGENCY_STOP 뒤의 조합이다.

        그 명령은 미션을 PAUSED 로 내리고 FSM 은 IDLE 에 남는다. 여기서 결함을 내면
        '사람이 해제해 둔 로봇'이 매 tick ERROR 로 보고된다.
        """
        state = self.running("...")
        state.pause("REMOTE_PAUSE")
        state.set_mission_state("PAUSED")
        self.sync(state, "ESTOP 수동 해제", params)
        snapshot = state.snapshot()
        assert snapshot.mode == "PAUSED"
        assert snapshot.mission_state == "PAUSED"
