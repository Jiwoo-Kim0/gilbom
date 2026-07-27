"""robot_state.py 단위 테스트 — FSM · health 취합 · state 발행 타이밍.

브로커도 paho 도 없이 돈다. 시계는 주입해서 30초 주기와 updated_at 단조 증가를
실제로 기다리지 않고 검증한다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §5
"""

import json
from datetime import datetime, timedelta, timezone

import pytest

from comms import MessageFactory, MqttConfig, MqttClient, TopicKind
from comms.contract import HEALTH_MEMBERS, HEALTH_STATES, MODES, REASON_CODES
from robot_state import (
    HEALTH_PROBE_INTERVAL_SEC,
    MODE_REASONS,
    STATE_INTERVAL_SEC,
    STORAGE_PROBE_INTERVAL_SEC,
    InvalidTransition,
    RobotState,
    StatePublisher,
)

ROBOT_ID = "RBT-001"
STATE_TOPIC = f"tbs/v2/robots/{ROBOT_ID}/state"


# --------------------------------------------------------------------------
# 테스트 도구
# --------------------------------------------------------------------------


class RecordingClient:
    """StatePublisher 가 쓰는 부분만. payload 는 진짜 factory 가 만든다."""

    def __init__(self, robot_id: str = ROBOT_ID) -> None:
        self.messages = MessageFactory(robot_id)
        self.published = []  # (kind, payload)
        self.waited = []  # publish 호출마다의 wait_sec

    def publish(self, kind, payload, *, wait_sec=None) -> bool:
        self.published.append((kind, payload))
        self.waited.append(wait_sec)
        return True

    def states(self) -> list:
        return [p for kind, p in self.published if kind is TopicKind.STATE]


class FakeClock:
    """monotonic 과 UTC 벽시계를 함께 움직이는 가짜 시계."""

    def __init__(self, start: float = 1000.0) -> None:
        self.value = start
        self.wall = datetime(2026, 7, 26, 5, 0, 0, tzinfo=timezone.utc)

    def monotonic(self) -> float:
        return self.value

    def now(self) -> datetime:
        return self.wall

    def advance(self, seconds: float) -> None:
        self.value += seconds
        self.wall += timedelta(seconds=seconds)


@pytest.fixture
def clock():
    return FakeClock()


@pytest.fixture
def client():
    return RecordingClient()


@pytest.fixture
def state():
    return RobotState()


@pytest.fixture
def publisher(client, state, clock):
    return StatePublisher(
        client, state, monotonic=clock.monotonic, now=clock.now
    )


def patrolling(state: RobotState) -> RobotState:
    state.boot_completed()
    state.start_patrol()
    return state


# --------------------------------------------------------------------------
# FSM — 전이 (계약 §5.4, DoD 1)
# --------------------------------------------------------------------------


def test_starts_in_booting(state):
    assert (state.mode, state.reason_code) == ("BOOTING", "NONE")


def test_boot_to_ready_to_patrolling(state):
    assert state.boot_completed() is True
    assert (state.mode, state.reason_code) == ("READY", "NONE")
    assert state.start_patrol() is True
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")


def test_repeated_transition_reports_no_change(state):
    state.boot_completed()
    assert state.boot_completed() is False  # 이미 READY — 재발행할 이유가 없다


def test_skipping_ready_is_rejected(state):
    with pytest.raises(InvalidTransition):
        state.start_patrol()
    assert state.mode == "BOOTING"


def test_patrolling_pause_resume(state):
    patrolling(state)
    assert state.pause() is True
    assert (state.mode, state.reason_code) == ("PAUSED", "REMOTE_PAUSE")
    assert state.resume() is True
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")


@pytest.mark.parametrize(
    "reach",
    [
        patrolling,
        lambda s: (patrolling(s), s.pause())[0],
        lambda s: (patrolling(s), s.safe_stop("OBSTACLE"))[0],
    ],
)
def test_stand_by_returns_to_ready(state, reach):
    """미션이 끝나 대기로 돌아가는 경로 (전이 표의 PATROLLING·PAUSED·SAFE_STOP → READY)."""
    reach(state)
    assert state.stand_by() is True
    assert (state.mode, state.reason_code) == ("READY", "NONE")


def test_stand_by_from_ready_reports_no_change(state):
    state.boot_completed()
    assert state.stand_by() is False  # 이미 대기다 — 재발행할 이유가 없다


def test_stand_by_during_boot_is_the_same_transition_as_boot_completed(state):
    """BOOTING → READY 는 전이 표에 하나뿐이다. 부르는 이름이 달라도 결과가 같다.

    따로 막지 않는다 — FSM 이 지키는 것은 (mode, reason_code) 짝이지
    어느 메서드를 거쳤는지가 아니다.
    """
    assert state.stand_by() is True
    assert (state.mode, state.reason_code) == ("READY", "NONE")
    assert RobotState().boot_completed() is True


def test_stand_by_is_rejected_after_shutdown(state):
    state.begin_shutdown()
    with pytest.raises(InvalidTransition):
        state.stand_by()


def test_error_and_shutdown_are_reachable_from_anywhere():
    for reach in (lambda s: None, lambda s: s.boot_completed(), patrolling):
        fresh = RobotState()
        reach(fresh)
        assert fresh.fault("INTERNAL_ERROR") is True
        assert fresh.mode == "ERROR"
        assert fresh.begin_shutdown() is True
        assert (fresh.mode, fresh.reason_code) == ("SHUTTING_DOWN", "NONE")


def test_shutting_down_is_terminal(state):
    state.begin_shutdown()
    with pytest.raises(InvalidTransition):
        state.boot_completed()


# --------------------------------------------------------------------------
# FSM — (mode, reason_code) 짝 (DoD 7)
# --------------------------------------------------------------------------


def test_mode_cannot_be_assigned(state):
    # 필드 직접 대입 금지 — 전이 메서드로만 바뀐다.
    with pytest.raises(AttributeError):
        state.mode = "PATROLLING"
    with pytest.raises(AttributeError):
        state.reason_code = "OBSTACLE"


@pytest.mark.parametrize(
    "call",
    [
        lambda s: s.pause("OBSTACLE"),  # PAUSED 는 OBSTACLE 로 서지 않는다
        lambda s: s.safe_stop("NONE"),  # 이유 없는 안전 정지는 없다
        lambda s: s.safe_stop("REMOTE_PAUSE"),
        lambda s: s.fault("NONE"),
        lambda s: s.begin_shutdown("OBSTACLE"),
        lambda s: s.safe_stop("NOPE"),  # enum 자체가 아닌 값
    ],
)
def test_invalid_pairs_are_rejected(state, call):
    patrolling(state)
    with pytest.raises(ValueError):
        call(state)
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")


def test_mode_reasons_table_covers_contract_enums():
    assert set(MODE_REASONS) == set(MODES)
    for mode, reasons in MODE_REASONS.items():
        assert reasons, f"{mode} must allow at least one reason_code"
        assert reasons <= REASON_CODES


def test_every_reachable_pair_is_accepted_by_the_factory():
    """FSM 이 만들 수 있는 짝은 전부 factory.state() 를 통과해야 한다."""
    factory = MessageFactory(ROBOT_ID)
    for mode, reasons in MODE_REASONS.items():
        for reason in reasons:
            payload = factory.state(
                mode=mode,
                reason_code=reason,
                health_block=RobotState().health(),
                battery_valid=False,
                battery_percent=None,
                battery_state="UNKNOWN",
            )
            assert (payload["mode"], payload["reason_code"]) == (mode, reason)


# --------------------------------------------------------------------------
# FSM — 장애물 (계약 §5.6, DoD 6)
# --------------------------------------------------------------------------


def test_obstacle_round_trip(state):
    patrolling(state)
    assert state.obstacle_detected() is True
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "OBSTACLE")
    assert state.obstacle_cleared() is True
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")


def test_repeated_obstacle_detection_does_not_change_state(state):
    patrolling(state)
    state.obstacle_detected()
    assert state.obstacle_detected() is False  # 최초 감지 1회만 전이다


@pytest.mark.parametrize("reason", ["REMOTE_ESTOP", "CRITICAL_BATTERY", "LIDAR_FAULT"])
def test_obstacle_does_not_overwrite_another_safe_stop_reason(state, reason):
    """운영자가 누른 정지가 장애물로 덮이면 기록에서 지워지고 자동 재개 대상이 된다."""
    patrolling(state)
    state.safe_stop(reason)
    assert state.obstacle_detected() is False
    assert (state.mode, state.reason_code) == ("SAFE_STOP", reason)


@pytest.mark.parametrize("mode", ["BOOTING", "ERROR"])
def test_obstacle_is_ignored_where_safe_stop_is_not_reachable(mode):
    """전이 표가 막는 mode 에서는 조용히 False 다 — 예외로 루프를 세우지 않는다."""
    fresh = RobotState()
    if mode == "ERROR":
        fresh.fault("INTERNAL_ERROR")
    assert fresh.obstacle_detected() is False
    assert fresh.mode == mode


def test_obstacle_cleared_does_not_release_other_safe_stops(state):
    patrolling(state)
    state.safe_stop("REMOTE_ESTOP")
    assert state.obstacle_cleared() is False
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")


def test_obstacle_publishes_two_states_and_no_safety_event(client, publisher, state):
    patrolling(state)
    publisher.poll()
    before = len(client.published)

    state.obstacle_detected()
    publisher.poll()
    state.obstacle_cleared()
    publisher.poll()

    sent = client.published[before:]
    assert [p["mode"] for _, p in sent] == ["SAFE_STOP", "PATROLLING"]
    assert [p["reason_code"] for _, p in sent] == ["OBSTACLE", "NONE"]
    # Safety Event 발행은 R-4 다. 이번 범위에서는 나가지 않는다.
    assert {kind for kind, _ in client.published} == {TopicKind.STATE}


# --------------------------------------------------------------------------
# health 취합 (계약 §5.3, DoD 5)
# --------------------------------------------------------------------------


def test_health_always_carries_all_six_members(state):
    members = state.health()
    assert tuple(members) == HEALTH_MEMBERS
    assert set(members.values()) <= HEALTH_STATES


def test_stub_health_is_unknown(state):
    # 하드웨어 판정 전이다. 가짜 OK 가 아니라 '검사 전' 이 정확한 값이다.
    assert set(state.health().values()) == {"UNKNOWN"}


def test_hardware_module_stubs_return_unknown():
    from detect import camera
    from drive import motor, safety
    from sensors import battery, gps

    for module in (camera, motor, safety, battery, gps):
        assert module.health() == "UNKNOWN"


def test_main_wires_every_hardware_probe():
    import main

    probes = main.health_probes()
    # storage 만 RobotState 가 직접 판정한다.
    assert set(probes) == set(HEALTH_MEMBERS) - {"storage"}
    assert RobotState(health_probes=probes).health() == {
        name: "UNKNOWN" for name in HEALTH_MEMBERS
    }


def test_probe_values_are_reported(state):
    reported = RobotState(health_probes={"camera": lambda: "DEGRADED"})
    assert reported.health()["camera"] == "DEGRADED"


def test_probe_that_raises_is_fault():
    def broken():
        raise OSError("no device")

    # 검사를 했는데 실패한 것이다 — UNKNOWN(검사 전)이 아니라 FAULT.
    assert RobotState(health_probes={"lidar": broken}).health()["lidar"] == "FAULT"


def test_probe_returning_non_enum_is_fault():
    faulty = RobotState(health_probes={"gps": lambda: "FINE"})
    assert faulty.health()["gps"] == "FAULT"


def test_storage_probe_cannot_be_injected():
    with pytest.raises(ValueError):
        RobotState(health_probes={"storage": lambda: "OK"})


def test_unknown_probe_member_is_rejected():
    with pytest.raises(ValueError):
        RobotState(health_probes={"wheels": lambda: "OK"})


# --------------------------------------------------------------------------
# health.storage (계약 §5.3)
# --------------------------------------------------------------------------


class FakeOutbox:
    def __init__(self, pending: int = 0) -> None:
        self.pending = pending

    def pending_count(self) -> int:
        return self.pending


def test_storage_is_unknown_without_evidence(state):
    assert state.health()["storage"] == "UNKNOWN"


def test_storage_ok_when_writable(tmp_path, clock):
    wired = RobotState(
        storage_dir=tmp_path, outbox=FakeOutbox(), monotonic=clock.monotonic
    )
    assert wired.health()["storage"] == "OK"


def test_storage_fault_when_directory_is_missing(tmp_path, clock):
    wired = RobotState(storage_dir=tmp_path / "gone", monotonic=clock.monotonic)
    assert wired.health()["storage"] == "FAULT"


def test_storage_degraded_when_outbox_piles_up(tmp_path, clock):
    wired = RobotState(
        storage_dir=tmp_path, outbox=FakeOutbox(pending=100), monotonic=clock.monotonic
    )
    assert wired.health()["storage"] == "DEGRADED"


def test_hardware_probes_are_cached_between_ticks(clock):
    """스텁은 공짜지만 I2C·시리얼이 붙으면 tick 마다 장치를 때리게 된다."""
    calls = []
    wired = RobotState(
        health_probes={"camera": lambda: (calls.append(1), "OK")[1]},
        monotonic=clock.monotonic,
    )
    for _ in range(4):  # 0.5초 tick 두 번 = 캐시 한 주기
        wired.health()
        clock.advance(0.25)
    assert len(calls) == 1

    clock.advance(HEALTH_PROBE_INTERVAL_SEC)
    wired.health()
    assert len(calls) == 2


def test_one_tick_probes_each_member_at_most_once(client, clock):
    """publisher·sampler·events 가 한 tick 에 각자 health 를 읽어도 프로브는 한 바퀴다."""
    from events import EventPublisher
    from telemetry import TelemetrySampler

    calls = []

    def probe(name):
        calls.append(name)
        return "OK"

    members = ("camera", "lidar", "gps", "battery", "motor")
    counted = RobotState(
        health_probes={name: (lambda n=name: probe(n)) for name in members},
        monotonic=clock.monotonic,
    )
    publisher = StatePublisher(client, counted, monotonic=clock.monotonic)
    sampler = TelemetrySampler(
        client, counted, monotonic=clock.monotonic, thermal_root="no-thermal"
    )
    events = EventPublisher(client, counted, monotonic=clock.monotonic)

    publisher.poll()
    sampler.poll()
    events.poll()
    assert len(calls) <= len(members)
    assert set(calls) == set(members)


def test_storage_probe_is_cached_between_ticks(tmp_path, clock):
    outbox = FakeOutbox()
    wired = RobotState(
        storage_dir=tmp_path, outbox=outbox, monotonic=clock.monotonic
    )
    assert wired.health()["storage"] == "OK"
    outbox.pending = 500
    assert wired.health()["storage"] == "OK"  # 아직 캐시
    clock.advance(10)
    assert wired.health()["storage"] == "DEGRADED"


def test_storage_keeps_its_own_longer_window(tmp_path, clock):
    """디스크 쓰기라 health 캐시(1초)가 만료돼도 5초까지는 다시 검사하지 않는다."""
    outbox = FakeOutbox()
    wired = RobotState(storage_dir=tmp_path, outbox=outbox, monotonic=clock.monotonic)
    assert wired.health()["storage"] == "OK"
    outbox.pending = 500

    clock.advance(HEALTH_PROBE_INTERVAL_SEC)
    assert wired.health()["storage"] == "OK"  # health 캐시는 만료됐지만 storage 는 아니다
    clock.advance(STORAGE_PROBE_INTERVAL_SEC)
    assert wired.health()["storage"] == "DEGRADED"


def test_health_change_publishes(client, clock):
    verdict = ["OK"]
    wired = RobotState(
        health_probes={"camera": lambda: verdict[0]}, monotonic=clock.monotonic
    )
    pub = StatePublisher(client, wired)
    pub.publish(force=True)
    verdict[0] = "FAULT"
    assert pub.poll() is False  # 아직 캐시 — 감지가 최대 1초 늦는다
    clock.advance(HEALTH_PROBE_INTERVAL_SEC)
    assert pub.poll() is True
    assert client.states()[-1]["health"]["camera"] == "FAULT"


# --------------------------------------------------------------------------
# 발행 타이밍 (계약 §5.1, DoD 1·3)
# --------------------------------------------------------------------------


def test_first_poll_publishes(client, publisher):
    assert publisher.poll() is True
    assert len(client.states()) == 1


def test_each_transition_publishes_once(client, publisher, state):
    publisher.poll()  # BOOTING
    state.boot_completed()
    publisher.poll()
    state.start_patrol()
    publisher.poll()
    assert [p["mode"] for p in client.states()] == ["BOOTING", "READY", "PATROLLING"]


def test_no_transition_no_publish(client, publisher, clock):
    publisher.poll()
    clock.advance(STATE_INTERVAL_SEC - 1)
    assert publisher.poll() is False
    assert len(client.states()) == 1


def test_publishes_every_30_seconds_without_transition(client, publisher, clock):
    publisher.poll()
    clock.advance(STATE_INTERVAL_SEC)
    assert publisher.poll() is True
    clock.advance(STATE_INTERVAL_SEC)
    assert publisher.poll() is True
    assert len(client.states()) == 3


def test_transition_resets_the_30_second_timer(client, publisher, state, clock):
    publisher.poll()
    clock.advance(STATE_INTERVAL_SEC - 5)
    state.boot_completed()
    assert publisher.poll() is True  # 전이로 발행
    clock.advance(5)  # 원래 주기였다면 여기서 걸렸을 시점
    assert publisher.poll() is False  # 타이머가 리셋됐다 — 같은 상태를 두 번 안 보낸다
    clock.advance(STATE_INTERVAL_SEC - 5)
    assert publisher.poll() is True


def test_forced_publish_also_resets_the_timer(client, publisher, clock):
    publisher.poll()
    clock.advance(STATE_INTERVAL_SEC - 1)
    publisher.publish(force=True)
    clock.advance(1)
    assert publisher.poll() is False
    assert len(client.states()) == 2


def test_battery_state_change_publishes_but_percent_drift_does_not(
    client, publisher, state, clock
):
    state.set_battery(valid=True, percent=67.4)
    publisher.poll()
    state.set_battery(valid=True, percent=67.1)
    assert publisher.poll() is False  # 퍼센트만 흔들린 것은 전이가 아니다
    state.set_battery(valid=True, percent=19.5)
    assert publisher.poll() is False  # 첫 표본은 아직 확정이 아니다 (confirm_samples)
    state.set_battery(valid=True, percent=19.2)
    assert publisher.poll() is True
    assert client.states()[-1]["battery"] == {
        "valid": True,
        "percent": 19.2,
        "state": "LOW",
    }


def test_invalid_battery_values_are_rejected_before_publish(state):
    with pytest.raises(ValueError):
        state.set_battery(valid=True, percent=None)
    with pytest.raises(ValueError):
        state.set_battery(valid=True, percent=140.0)
    with pytest.raises(ValueError):
        state.set_battery(valid=True, percent=50.0, source="GUESSED")


# --------------------------------------------------------------------------
# updated_at 단조 증가 (계약 §3.4, DoD 4)
# --------------------------------------------------------------------------


def test_updated_at_is_strictly_increasing_within_one_millisecond(
    client, publisher, state, clock
):
    # 시계를 멈춰 둔다 — 같은 밀리초에 여러 번 발행되는 최악의 경우다.
    publisher.publish(force=True)
    state.boot_completed()
    publisher.poll()
    state.start_patrol()
    publisher.poll()

    stamps = [p["updated_at"] for p in client.states()]
    assert stamps == sorted(stamps)
    assert len(set(stamps)) == len(stamps)
    assert stamps == [
        "2026-07-26T05:00:00.000Z",
        "2026-07-26T05:00:00.001Z",
        "2026-07-26T05:00:00.002Z",
    ]


def test_updated_at_keeps_increasing_when_the_clock_steps_back(
    client, publisher, clock
):
    publisher.publish(force=True)
    clock.wall -= timedelta(seconds=5)  # NTP 보정으로 시계가 뒤로 갔다
    publisher.publish(force=True)
    stamps = [p["updated_at"] for p in client.states()]
    assert stamps[1] > stamps[0]


def test_updated_at_follows_the_wall_clock_when_it_advances(client, publisher, clock):
    publisher.publish(force=True)
    clock.advance(1.5)
    publisher.publish(force=True)
    stamps = [p["updated_at"] for p in client.states()]
    assert stamps == ["2026-07-26T05:00:00.000Z", "2026-07-26T05:00:01.500Z"]


def test_seq_increases_with_every_state(client, publisher, state):
    publisher.publish(force=True)
    state.boot_completed()
    publisher.poll()
    seqs = [p["seq"] for p in client.states()]
    assert seqs == sorted(seqs) and len(set(seqs)) == len(seqs)


# --------------------------------------------------------------------------
# payload — factory 가 만든 그대로여야 한다
# --------------------------------------------------------------------------


def test_payload_matches_the_contract_shape(client, publisher, state):
    patrolling(state)
    publisher.publish(force=True)
    payload = client.states()[-1]
    assert list(payload) == [
        "schema_version",
        "robot_id",
        "boot_id",
        "seq",
        "updated_at",
        "mode",
        "reason_code",
        "mission",
        "battery",
        "health",
    ]
    assert payload["schema_version"] == "2.0"
    assert payload["robot_id"] == ROBOT_ID
    # 미션 보관함은 아직 비어 있다 — 채우는 것은 R-5.
    assert payload["mission"] is None
    assert payload["battery"] == {"valid": False, "percent": None, "state": "UNKNOWN"}
    assert payload["health"] == {name: "UNKNOWN" for name in HEALTH_MEMBERS}


def test_mission_slot_is_empty(state):
    assert (state.mission_id, state.mission_state, state.zone_id) == (None, None, None)


def test_state_mission_block_carries_only_id_and_state(client, publisher, state):
    """state 는 mission_id·state 만 싣는다 — 경과·Incident 집계는 telemetry 쪽이다."""
    state.start_mission("MIS-20260726-001", zone_id="ZONE-A")
    publisher.publish(force=True)
    assert client.states()[-1]["mission"] == {
        "mission_id": "MIS-20260726-001",
        "state": "RUNNING",
    }
    state.end_mission()
    publisher.publish(force=True)
    assert client.states()[-1]["mission"] is None


# --------------------------------------------------------------------------
# 접속 직후 발행 (계약 §5.1 (a), DoD 2)
# --------------------------------------------------------------------------


class FakePaho:
    """MqttClient 가 부르는 paho API 중 이 테스트가 쓰는 것만."""

    def __init__(self):
        self.on_connect = self.on_disconnect = None
        self.on_message = self.on_publish = None
        self.published = []  # (topic, payload)
        self.waits = []  # wait_for_publish 를 부른 (topic, timeout)

    def will_set(self, topic, payload=None, qos=0, retain=False, properties=None):
        pass

    def reconnect_delay_set(self, min_delay=1, max_delay=120):
        pass

    def connect_async(self, host, port=1883, keepalive=60, **kwargs):
        pass

    def loop_start(self):
        pass

    def loop_stop(self):
        pass

    def disconnect(self):
        pass

    def subscribe(self, topic, qos=0):
        self.published.append(("SUBSCRIBE", topic))

    def publish(self, topic, payload=None, qos=0, retain=False, properties=None):
        self.published.append((topic, payload))
        waits = self.waits

        class _Info:
            mid = 1
            rc = 0

            def wait_for_publish(self, timeout=None):
                waits.append((topic, timeout))

        return _Info()

    def simulate_connect(self):
        self.on_connect(self, None, {}, 0, None)

    def simulate_disconnect(self):
        self.on_disconnect(self, None, 0)

    def topics_in_order(self):
        return [topic for topic, _ in self.published]

    def states(self):
        return [
            json.loads(payload)
            for topic, payload in self.published
            if topic == STATE_TOPIC
        ]


@pytest.fixture
def wired(tmp_path):
    """진짜 MqttClient + RobotState + StatePublisher 조립."""
    paho = FakePaho()
    config = MqttConfig(
        robot_id=ROBOT_ID,
        host="broker.example.com",
        outbox_path=str(tmp_path / "outbox.sqlite3"),
    )
    client = MqttClient(config, client_factory=lambda _c: paho)
    state = RobotState()
    publisher = StatePublisher(client, state)
    client.on_connected = publisher.on_connected
    yield paho, client, state, publisher
    client.outbox.close()


def test_state_is_published_right_after_connect(wired):
    paho, _client, _state, _publisher = wired
    paho.simulate_connect()
    # presence(birth) → subscribe → state 순서다.
    assert paho.topics_in_order() == [
        f"tbs/v2/robots/{ROBOT_ID}/presence",
        "SUBSCRIBE",
        STATE_TOPIC,
    ]
    assert paho.states()[0]["mode"] == "BOOTING"


def test_every_reconnect_republishes_state(wired):
    paho, _client, state, publisher = wired
    paho.simulate_connect()
    state.boot_completed()
    publisher.poll()
    paho.simulate_disconnect()
    paho.simulate_connect()

    modes = [p["mode"] for p in paho.states()]
    assert modes == ["BOOTING", "READY", "READY"]
    stamps = [p["updated_at"] for p in paho.states()]
    assert stamps == sorted(stamps) and len(set(stamps)) == len(stamps)


def test_connect_hook_failure_does_not_break_the_client(tmp_path):
    paho = FakePaho()
    config = MqttConfig(
        robot_id=ROBOT_ID,
        host="broker.example.com",
        outbox_path=str(tmp_path / "outbox.sqlite3"),
    )

    def boom():
        raise RuntimeError("state publish failed")

    client = MqttClient(config, client_factory=lambda _c: paho, on_connected=boom)
    try:
        paho.simulate_connect()  # 예외가 여기까지 올라오면 paho 루프가 죽는다
        assert client.connected
    finally:
        client.outbox.close()


def test_client_without_hook_still_connects(tmp_path):
    paho = FakePaho()
    config = MqttConfig(
        robot_id=ROBOT_ID,
        host="broker.example.com",
        outbox_path=str(tmp_path / "outbox.sqlite3"),
    )
    client = MqttClient(config, client_factory=lambda _c: paho)
    try:
        paho.simulate_connect()
        assert client.connected
        assert STATE_TOPIC not in paho.topics_in_order()
    finally:
        client.outbox.close()


# --------------------------------------------------------------------------
# 종료 (main.shutdown)
# --------------------------------------------------------------------------


def test_shutdown_publishes_shutting_down_before_disconnect(wired):
    import main

    paho, client, state, publisher = wired
    paho.simulate_connect()
    state.boot_completed()
    publisher.poll()

    main.shutdown(client, state, publisher)

    assert paho.states()[-1]["mode"] == "SHUTTING_DOWN"
    # presence OFFLINE(GRACEFUL_SHUTDOWN) 은 client 가 그 뒤에 보낸다.
    last_topic, last_payload = paho.published[-1]
    assert last_topic == f"tbs/v2/robots/{ROBOT_ID}/presence"
    assert json.loads(last_payload)["reason"] == "GRACEFUL_SHUTDOWN"


def test_shutdown_waits_for_the_last_state_to_leave(wired):
    """disconnect 가 바로 뒤따르므로 기다리지 않으면 큐에 남은 채 버려진다."""
    import main

    paho, client, state, publisher = wired
    paho.simulate_connect()
    state.boot_completed()
    publisher.poll()
    assert paho.waits == []  # 평상시 발행은 기다리지 않는다

    main.shutdown(client, state, publisher)

    waited_topics = [topic for topic, _ in paho.waits]
    assert STATE_TOPIC in waited_topics
    timeout = next(t for topic, t in paho.waits if topic == STATE_TOPIC)
    assert timeout == main.SHUTDOWN_PUBLISH_WAIT_SEC
