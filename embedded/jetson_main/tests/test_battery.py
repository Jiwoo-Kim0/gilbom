"""배터리 잔량 역산과 그 조치 — 추정기 · telemetry · event · 주행 정지.

숫자는 전부 config.yaml 에서 읽는다(저장소 방침). 이 파일이 지키는 것은 하나다:
**3시간 실사용에 55%** 라는 실측이 잔량·등급·정지까지 그대로 이어지는가.
"""

import json

import pytest

from comms import MessageFactory, TopicKind
from communication.events import EventPublisher
from communication.robot_state import BatteryThresholds, RobotState
from communication.runtime import BatteryDriveGuard
from communication.telemetry import TelemetrySampler
from driving.states import DriveState
from hardware.battery import RuntimeBatteryEstimator

HOUR = 3600.0


class Clock:
    """monotonic 대역. 테스트가 시간을 직접 민다."""

    def __init__(self, start: float = 0.0) -> None:
        self.now = float(start)

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class RecordingClient:
    def __init__(self) -> None:
        self.messages = MessageFactory("RBT-TEST")
        self.published = []

    def publish(self, kind, payload, **_kwargs):
        self.published.append((TopicKind(kind), payload))
        return True

    def payloads(self, kind):
        return [payload for topic, payload in self.published if topic is kind]


@pytest.fixture(scope="session")
def battery_config(config) -> dict:
    return config["battery"]


@pytest.fixture(scope="session")
def estimate_config(battery_config) -> dict:
    return battery_config["estimate"]


@pytest.fixture
def clock() -> Clock:
    return Clock()


def build_estimator(estimate_config, clock, *, state_path=None, **overrides):
    """config.yaml 의 estimate 절로 추정기 하나. 기록 경로를 안 주면 남기지 않는다."""
    section = dict(estimate_config, **overrides)
    # 절대 경로면 그대로 쓰인다 — 테스트가 저장소 안에 파일을 남기지 않게 한다.
    section["state_file"] = None if state_path is None else str(state_path)
    return RuntimeBatteryEstimator.from_config(section, monotonic=clock)


# -- 역산 그 자체 -----------------------------------------------------------


def test_three_hours_of_use_burns_the_measured_share(estimate_config, clock):
    """실측 재현 — 3시간 가동 후 55% 소모(잔량 45%)여야 한다.

    이 한 줄이 config 의 drain_pct_per_hour 가 무엇을 뜻하는지 고정한다:
    2026-08-05 DXF 4셀 팩, 3시간 실사용 후 충전기 표시 55% 소모.
    """
    estimator = build_estimator(estimate_config, clock)

    clock.advance(3 * HOUR)

    assert estimator.percent() == pytest.approx(45.0, abs=0.1)


def test_estimate_falls_to_empty_in_about_five_and_a_half_hours(estimate_config, clock):
    estimator = build_estimator(estimate_config, clock)

    clock.advance(100.0 / float(estimate_config["drain_pct_per_hour"]) * HOUR)

    assert estimator.percent() == pytest.approx(0.0, abs=0.1)
    # 더 달려도 음수로 내려가지 않는다 — battery.percent 는 0~100 이 계약이다.
    clock.advance(HOUR)
    assert estimator.percent() == 0.0
    assert estimator.remaining_sec() == 0


def test_remaining_seconds_counts_down_to_empty(estimate_config, clock):
    estimator = build_estimator(estimate_config, clock)
    rate = float(estimate_config["drain_pct_per_hour"])

    clock.advance(HOUR)

    assert estimator.remaining_sec() == pytest.approx(
        (100.0 - rate) / rate * HOUR, rel=0.01)


# -- 기록·충전 --------------------------------------------------------------


def test_every_run_starts_full(estimate_config, clock, tmp_path):
    """기본 운용 — 매 실행 전에 완충하고 나간다. 기동 = 만충이다.

    지난 잔량을 이어받으면 충전을 하고도 낮은 값이 따라와 순찰이 막힌다.
    """
    path = tmp_path / "battery.json"
    first = build_estimator(estimate_config, clock, state_path=path)
    clock.advance(3 * HOUR)
    first.close()
    assert json.loads(path.read_text(encoding="utf-8"))["percent"] < 50.0

    restarted = build_estimator(estimate_config, Clock(), state_path=path)

    assert restarted.percent() == float(estimate_config["full_pct"])


def test_resume_on_restart_carries_the_stored_estimate(estimate_config, clock, tmp_path):
    """완충 없이 재시작하는 운용으로 바꾸면(스위치 하나) 기록을 이어받는다."""
    path = tmp_path / "battery.json"
    first = build_estimator(estimate_config, clock, state_path=path,
                            resume_on_restart=True)
    clock.advance(3 * HOUR)
    first.close()

    resumed = build_estimator(estimate_config, Clock(), state_path=path,
                              resume_on_restart=True)

    assert resumed.percent() == pytest.approx(45.0, abs=0.1)


def test_periodic_write_keeps_the_file_fresh_without_a_clean_shutdown(
        estimate_config, clock, tmp_path):
    """전원이 그냥 끊겨도(close 없음) 마지막 기록은 한 걸음 안쪽이다."""
    path = tmp_path / "battery.json"
    estimator = build_estimator(estimate_config, clock, state_path=path)

    clock.advance(HOUR)
    estimator()  # telemetry 표본 1회 — 이때 기록된다

    stored = json.loads(path.read_text(encoding="utf-8"))["percent"]
    assert stored == pytest.approx(estimator.percent(), abs=float(
        estimate_config["persist_step_pct"]))


def test_mark_full_is_the_only_way_back_up(estimate_config, clock, tmp_path):
    path = tmp_path / "battery.json"
    estimator = build_estimator(estimate_config, clock, state_path=path)
    clock.advance(4 * HOUR)
    assert estimator.percent() < float(estimate_config["full_pct"])

    estimator.mark_full()

    assert estimator.percent() == float(estimate_config["full_pct"])
    assert json.loads(path.read_text(encoding="utf-8"))["percent"] == pytest.approx(
        float(estimate_config["full_pct"]))


def test_corrupt_record_does_not_brick_the_robot(estimate_config, clock, tmp_path):
    path = tmp_path / "battery.json"
    path.write_text("{ not json", encoding="utf-8")

    estimator = build_estimator(estimate_config, clock, state_path=path,
                                resume_on_restart=True)

    assert estimator.percent() == float(estimate_config["full_pct"])


# -- 조립 -------------------------------------------------------------------


def test_disabled_section_yields_no_estimator(estimate_config):
    assert RuntimeBatteryEstimator.from_config(
        dict(estimate_config, enabled=False)) is None
    assert RuntimeBatteryEstimator.from_config(None) is None


def test_enabled_without_the_measured_rate_fails_loudly(estimate_config):
    section = dict(estimate_config)
    section.pop("drain_pct_per_hour")

    with pytest.raises(ValueError):
        RuntimeBatteryEstimator.from_config(section)


def test_build_from_config_reads_the_whole_document(config, tmp_path):
    """진입점(main·web_test)이 쓰는 조립 함수 — config.yaml 문서를 그대로 받는다."""
    from hardware.battery import build_from_config

    assert build_from_config(config, config_dir=tmp_path) is not None
    # battery 절이 없는 문서(옛 config)도 조용히 넘어간다 — 배터리 경로만 꺼진다.
    assert build_from_config({"hardware": {}}, config_dir=tmp_path) is None
    assert build_from_config(None) is None


def test_relative_state_file_lands_next_to_the_other_state(estimate_config, tmp_path):
    estimator = RuntimeBatteryEstimator.from_config(estimate_config, state_dir=tmp_path)
    estimator.close()

    assert (tmp_path / estimate_config["state_file"]).exists()


# -- telemetry (계약 §6.3) --------------------------------------------------


def test_telemetry_reports_the_estimate_as_estimated(estimate_config, battery_config,
                                                     clock, config):
    client = RecordingClient()
    state = RobotState(battery_thresholds=BatteryThresholds.from_document(config))
    state.boot_completed()
    estimator = build_estimator(estimate_config, clock)
    sampler = TelemetrySampler(client, state, battery=estimator)

    clock.advance(3 * HOUR)
    sampler.publish()

    battery = client.payloads(TopicKind.TELEMETRY)[0]["battery"]
    assert battery["valid"] is True
    assert battery["percent"] == pytest.approx(45.0, abs=0.1)
    assert battery["state"] == "NORMAL"
    # 측정이 아니라 추정이라는 사실이 payload 에 남아야 한다.
    assert battery["source"] == "ESTIMATED"
    assert battery["estimated_runtime_sec"] > 0


def test_state_publishes_the_same_percent_as_telemetry(estimate_config, clock, config):
    """읽기는 RobotState 한 곳으로만 들어간다 — state 와 telemetry 가 갈라지지 않는다."""
    client = RecordingClient()
    state = RobotState(battery_thresholds=BatteryThresholds.from_document(config))
    state.boot_completed()
    sampler = TelemetrySampler(client, state, battery=build_estimator(
        estimate_config, clock))

    clock.advance(2 * HOUR)
    sampler.publish()

    telemetry = client.payloads(TopicKind.TELEMETRY)[0]
    assert state.snapshot().battery_percent == telemetry["battery"]["percent"]


# -- events/battery (계약 §9) ----------------------------------------------


def graded(state: RobotState, percent: float, thresholds: BatteryThresholds) -> None:
    """등급이 확정될 때까지 같은 값을 표본으로 넣는다 (confirm_samples)."""
    for _ in range(thresholds.confirm_samples):
        state.set_battery(valid=True, percent=percent, source="ESTIMATED")


@pytest.fixture
def battery_session(config):
    client = RecordingClient()
    thresholds = BatteryThresholds.from_document(config)
    state = RobotState(battery_thresholds=thresholds)
    state.boot_completed()
    events = EventPublisher(client, state, sensor_fault_stops_driving=False)
    return client, state, events, thresholds


def test_low_estimate_pauses_the_patrol_and_the_mission(battery_session):
    client, state, events, thresholds = battery_session
    state.start_patrol()
    state.start_mission("M-1", zone_id="A-01")
    events.mission_started()

    graded(state, thresholds.low_pct - 1.0, thresholds)
    events.poll()

    battery_event = client.payloads(TopicKind.EVENT_BATTERY)[0]
    assert battery_event["event_type"] == "BATTERY_LOW"
    assert battery_event["threshold_pct"] == thresholds.low_pct
    # 조치는 event 보다 먼저 일어나고 그 결과가 payload 에 실린다 (§9.3).
    assert battery_event["action_taken"]["robot_mode"] == "PAUSED"
    assert battery_event["action_taken"]["mission_state"] == "PAUSED"
    assert (state.mode, state.reason_code) == ("PAUSED", "LOW_BATTERY")
    assert [payload["event_type"] for payload in
            client.payloads(TopicKind.EVENT_MISSION)] == [
        "MISSION_STARTED", "MISSION_PAUSED"]


def test_critical_estimate_safe_stops(battery_session):
    client, state, events, thresholds = battery_session
    state.start_patrol()
    state.start_mission("M-1", zone_id="A-01")
    events.mission_started()

    graded(state, thresholds.critical_pct - 1.0, thresholds)
    events.poll()

    assert [payload["event_type"] for payload in
            client.payloads(TopicKind.EVENT_BATTERY)] == ["BATTERY_CRITICAL"]
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "CRITICAL_BATTERY")
    assert state.snapshot().mission_state == "PAUSED"


def test_charging_between_runs_recovers_the_grade(battery_session):
    client, state, events, thresholds = battery_session
    state.start_patrol()
    graded(state, thresholds.low_pct - 1.0, thresholds)
    events.poll()

    # 충전 후 재기동 — mark_full 이 만든 값이 이런 식으로 들어온다.
    graded(state, thresholds.low_recover_pct + 1.0, thresholds)
    events.poll()

    assert [payload["event_type"] for payload in
            client.payloads(TopicKind.EVENT_BATTERY)] == [
        "BATTERY_LOW", "BATTERY_RECOVERED"]


# -- 주행 정지 (BatteryDriveGuard) ------------------------------------------


class FakeMachine:
    def __init__(self, state: DriveState) -> None:
        self.state = state
        self.requests = []

    def request_pause(self) -> None:
        self.requests.append("pause")
        self.state = DriveState.PAUSED

    def request_estop(self) -> None:
        self.requests.append("estop")
        self.state = DriveState.ESTOP


def test_guard_pauses_driving_once_on_low(config):
    thresholds = BatteryThresholds.from_document(config)
    state = RobotState(battery_thresholds=thresholds)
    state.boot_completed()
    guard = BatteryDriveGuard(state)
    machine = FakeMachine(DriveState.CRUISE)

    graded(state, thresholds.low_pct - 1.0, thresholds)
    guard.apply(machine)
    machine.state = DriveState.CRUISE   # 관제가 재개시켰다
    guard.apply(machine)

    # 두 번째는 요청하지 않는다 — 재개 판단은 관제 몫이다 (§9.3).
    assert machine.requests == ["pause"]


def test_guard_keeps_asserting_estop_while_critical(config):
    thresholds = BatteryThresholds.from_document(config)
    state = RobotState(battery_thresholds=thresholds)
    state.boot_completed()
    guard = BatteryDriveGuard(state)
    machine = FakeMachine(DriveState.CRUISE)

    graded(state, thresholds.critical_pct - 1.0, thresholds)
    guard.apply(machine)
    assert machine.requests == ["estop"]

    guard.apply(machine)                # 이미 ESTOP — 다시 밀지 않는다
    assert machine.requests == ["estop"]

    machine.state = DriveState.CRUISE   # 사람이 풀고 다시 달리려 한다
    guard.apply(machine)
    # 임계 잔량의 출구는 충전뿐이다 — 다시 세운다.
    assert machine.requests == ["estop", "estop"]


def test_guard_does_not_touch_driving_when_disabled(config):
    state = RobotState(battery_thresholds=BatteryThresholds.from_document(config))
    state.boot_completed()
    guard = BatteryDriveGuard(state, enabled=False)
    machine = FakeMachine(DriveState.CRUISE)

    graded(state, 1.0, BatteryThresholds.from_document(config))
    guard.apply(machine)

    assert machine.requests == []


def test_runtime_build_wires_the_whole_battery_path(config, tmp_path):
    """실제 조립 경로(main) — config.yaml 의 battery 절이 끝까지 닿는가.

    한 군데라도 빠지면 조용히 죽는 경로다: 추정기를 telemetry 에 안 물리면 잔량이
    영원히 UNKNOWN 이고, 임계값을 안 넘기면 코드 기본값으로 판정하며, 가드가 없으면
    보고만 하고 바퀴는 계속 돈다.
    """
    from communication.runtime import CommunicationRuntime

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
    runtime = CommunicationRuntime.build(str(comms_config), config, live=False)
    try:
        thresholds = runtime.state.battery_thresholds
        assert thresholds.low_pct == float(config["battery"]["low_pct"])
        assert thresholds.critical_pct == float(config["battery"]["critical_pct"])
        assert runtime._telemetry._battery is runtime._battery is not None
        assert runtime._battery_guard._enabled is True
        # 잔량을 읽는 장치가 없다는 사실은 health 에서 지우지 않는다.
        assert runtime.state.snapshot().health_block["battery"] == "DEGRADED"

        runtime.state.set_battery(valid=True, percent=42.0, source="ESTIMATED")
        assert runtime.state.snapshot().battery_source == "ESTIMATED"
    finally:
        runtime.client.outbox.close()

    # 지난 실행이 5% 를 남겼어도 다음 기동은 만충이다 — 매 실행 전 완충하고 나가는
    # 운용이라 그쪽이 사실에 가깝다 (config battery.estimate.resume_on_restart).
    full_pct = float(config["battery"]["estimate"]["full_pct"])
    state_file = tmp_path / config["battery"]["estimate"]["state_file"]
    state_file.parent.mkdir(parents=True, exist_ok=True)
    state_file.write_text(json.dumps({"percent": 5.0}), encoding="utf-8")
    restarted = CommunicationRuntime.build(str(comms_config), config, live=False)
    try:
        assert restarted._battery.percent() == pytest.approx(full_pct, abs=0.01)
    finally:
        restarted.client.outbox.close()


def test_guard_leaves_escape_alone(config):
    """차도 이탈 선회 중에는 세우지 않는다 — 브레이크가 곧 차도 위 정지다."""
    thresholds = BatteryThresholds.from_document(config)
    state = RobotState(battery_thresholds=thresholds)
    state.boot_completed()
    guard = BatteryDriveGuard(state)
    machine = FakeMachine(DriveState.ESCAPE)

    graded(state, thresholds.low_pct - 1.0, thresholds)
    guard.apply(machine)
    assert machine.requests == []

    machine.state = DriveState.CRUISE   # 선회가 끝났다 — 그 다음 tick 에 적용된다
    guard.apply(machine)
    assert machine.requests == ["pause"]
