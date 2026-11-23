"""events.py 단위 테스트 — 장애물 2단계 · 배터리 등급 전이 · incident · mission.

브로커도 paho 도 없이 돈다. 시계·LiDAR·GPS·업로드는 주입해서 3초를 실제로 기다리지
않고 검증한다. payload 는 진짜 factory 가 만든다 — 계약 위반은 factory 가 잡아야 한다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §7~§11
"""

import json

import pytest

from comms import MessageFactory, MqttClient, MqttConfig, TopicKind
from events import OBSTACLE_CONFIRM_SEC, EventPublisher
from robot_state import (
    HEALTH_PROBE_INTERVAL_SEC,
    BatteryThresholds,
    RobotState,
    StatePublisher,
)
from telemetry import TELEMETRY_INTERVAL_SEC, TelemetrySampler

ROBOT_ID = "RBT-001"
CONFIRM_SEC = OBSTACLE_CONFIRM_SEC
TICK = 0.5  # main.LOOP_INTERVAL_SEC


# --------------------------------------------------------------------------
# 테스트 도구
# --------------------------------------------------------------------------


class RecordingClient:
    """EventPublisher 가 쓰는 부분만. payload 는 진짜 factory 가 만든다."""

    def __init__(self, robot_id: str = ROBOT_ID) -> None:
        self.messages = MessageFactory(robot_id)
        self.published = []  # (kind, payload)

    def publish(self, kind, payload, *, wait_sec=None) -> bool:
        self.published.append((kind, payload))
        return True

    def of(self, kind) -> list:
        return [payload for sent, payload in self.published if sent is kind]

    def safety(self) -> list:
        return self.of(TopicKind.EVENT_SAFETY)

    def battery(self) -> list:
        return self.of(TopicKind.EVENT_BATTERY)

    def mission(self) -> list:
        return self.of(TopicKind.EVENT_MISSION)

    def incidents(self) -> list:
        return self.of(TopicKind.EVENT_INCIDENT)

    def states(self) -> list:
        return self.of(TopicKind.STATE)


class FakeClock:
    def __init__(self, start: float = 1000.0) -> None:
        self.value = start
        self.stamps = 0

    def monotonic(self) -> float:
        return self.value

    def now(self) -> str:
        """occurred_at. 부를 때마다 다른 값이어야 어느 시각이 실렸는지 볼 수 있다."""
        self.stamps += 1
        return f"2026-07-26T05:00:{self.stamps:02d}.000Z"

    def advance(self, seconds: float) -> None:
        self.value += seconds


class FakeLidar:
    """(obstacle, distance_m) 를 돌려주는 스텁."""

    def __init__(self, obstacle: bool = False, distance_m=None) -> None:
        self.obstacle = obstacle
        self.distance_m = distance_m

    def set(self, obstacle: bool, distance_m=None) -> None:
        self.obstacle = obstacle
        self.distance_m = distance_m

    def __call__(self) -> tuple:
        return self.obstacle, self.distance_m


class FakeGps:
    def __init__(self, **reading) -> None:
        self.reading = reading or {"valid": False, "lat": None, "lon": None}

    def set(self, **reading) -> None:
        self.reading = reading

    def __call__(self) -> dict:
        return dict(self.reading)


<<<<<<< HEAD
=======
#: 이미지 업로드 form 의 robot_id — DB 내부 robot.robot_id UUID 다. mqtt_id 가 아니다.
ROBOT_UUID = "6f1b6b1e-9c2a-4c1e-9f3a-2b6d5a4c7e10"


>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
class FakeUpload:
    """uploader.upload 대역. 호출 인자를 기억하고 정해진 값을 돌려준다."""

    def __init__(self, image_id=None) -> None:
        self.image_id = image_id
        self.calls = []

<<<<<<< HEAD
    def __call__(self, image_path, event_id, robot_id):
        self.calls.append((image_path, event_id, robot_id))
=======
    def __call__(self, image_path, event_id, robot_uuid, *, captured_at=None):
        self.calls.append((image_path, event_id, robot_uuid))
        self.captured_at = captured_at
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
        return self.image_id


DETECTION = {
    "block_type": "LINEAR",
    "defect_type": "DAMAGED",
    "confidence": 0.87,
    "bbox_norm": [0.16, 0.42, 0.81, 0.95],
    "damage_ratio": 0.32,
}


@pytest.fixture
def clock():
    return FakeClock()


@pytest.fixture
def client():
    return RecordingClient()


@pytest.fixture
def probes():
    """health probe 를 테스트가 바꿔 끼울 수 있게 한 칸 열어 둔다."""
    return {"lidar": "UNKNOWN", "motor": "UNKNOWN"}


@pytest.fixture
def state(clock, probes):
    return RobotState(
        health_probes={name: (lambda n=name: probes[n]) for name in probes},
        monotonic=clock.monotonic,
    )


@pytest.fixture
def lidar():
    return FakeLidar()


@pytest.fixture
def gps():
    return FakeGps()


@pytest.fixture
def upload():
    return FakeUpload()


@pytest.fixture
def events(client, state, lidar, gps, upload, clock):
    return EventPublisher(
        client,
        state,
        lidar=lidar,
        gps=gps,
        model_name="tbs-braille-yolo",
        model_version="1.0.0",
        upload=upload,
<<<<<<< HEAD
=======
        upload_robot_uuid=ROBOT_UUID,
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
        monotonic=clock.monotonic,
        now=clock.now,
    )


def patrolling(state: RobotState) -> RobotState:
    state.boot_completed()
    state.start_patrol()
    return state


def feed_battery(state, events, *percents, valid: bool = True) -> None:
    """표본을 하나씩 넣고 그때마다 event 를 판정한다 (메인 루프와 같은 순서)."""
    for percent in percents:
        state.set_battery(valid=valid, percent=percent)
        events.poll()


# --------------------------------------------------------------------------
# events/safety — 장애물 2단계 (계약 §10.2, DoD 1~5)
# --------------------------------------------------------------------------


def test_short_stop_publishes_state_only(client, events, state, clock, lidar):
    """3초를 못 넘긴 정지는 State 로만 알린다 — Event 는 하나도 안 나간다."""
    patrolling(state)
    publisher = StatePublisher(client, state)
    publisher.poll()  # PATROLLING

    lidar.set(True, 0.72)
    events.poll()
    publisher.poll()  # SAFE_STOP/OBSTACLE
    clock.advance(CONFIRM_SEC - TICK)
    lidar.set(False, 1.53)
    events.poll()
    publisher.poll()  # PATROLLING/NONE

    assert [p["mode"] for p in client.states()] == [
        "PATROLLING",
        "SAFE_STOP",
        "PATROLLING",
    ]
    assert client.safety() == []
    assert state.obstacle_confirmed is False


def test_sustained_obstacle_is_confirmed_exactly_once(client, events, state, clock, lidar):
    patrolling(state)
    lidar.set(True, 0.72)
    events.poll()
    assert client.safety() == []  # 최초 감지에는 Event 가 없다

    clock.advance(CONFIRM_SEC)
    events.poll()
    assert [p["event_type"] for p in client.safety()] == ["SAFETY_STOPPED"]

    for _ in range(10):  # 센서 프레임마다 반복 발행하지 않는다
        clock.advance(TICK)
        events.poll()
    assert len(client.safety()) == 1


def test_confirmed_obstacle_release_publishes_cleared_once(
    client, events, state, clock, lidar
):
    patrolling(state)
    lidar.set(True, 0.72)
    events.poll()
    clock.advance(CONFIRM_SEC)
    events.poll()

    lidar.set(False, 1.53)
    events.poll()
    for _ in range(5):
        clock.advance(TICK)
        events.poll()

    assert [p["event_type"] for p in client.safety()] == [
        "SAFETY_STOPPED",
        "SAFETY_CLEARED",
    ]
    cleared = client.safety()[-1]
    assert cleared["measurement"] == {"distance_m": 1.53}
    assert cleared["transition"] == {
        "mode_before": "SAFE_STOP",
        "mode_after": "PATROLLING",
    }
    assert cleared["auto_resume_allowed"] is True


def test_cleared_never_goes_out_without_stopped(client, events, state, clock, lidar):
    """짧은 정지를 여러 번 반복해도 CLEARED 가 나가는 경로가 없다."""
    patrolling(state)
    for _ in range(3):
        lidar.set(True, 0.72)
        events.poll()
        clock.advance(CONFIRM_SEC - TICK)
        lidar.set(False, 1.53)
        events.poll()
        clock.advance(TICK)
    assert client.safety() == []


def test_confirmation_re_reads_the_lidar_instead_of_just_waiting(
    client, events, state, clock, lidar
):
    """'3초가 지났다' 가 아니라 '3초 뒤에 다시 봤더니 있더라' 다."""
    patrolling(state)
    lidar.set(True, 0.72)
    events.poll()
    clock.advance(CONFIRM_SEC * 2)  # 시간은 넉넉히 지났다
    lidar.set(False, 2.10)  # 그런데 다시 보니 없다
    events.poll()

    assert client.safety() == []
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")


def test_stopped_carries_the_first_detection_time(client, events, state, clock, lidar):
    """occurred_at 은 로봇이 실제로 선 시각이고 sent_at 이 확정 후 발행 시각이다 (§10.3)."""
    patrolling(state)
    lidar.set(True, 0.72)
    events.poll()
    detected_at = f"2026-07-26T05:00:{clock.stamps:02d}.000Z"

    clock.advance(CONFIRM_SEC)
    events.poll()

    stopped = client.safety()[-1]
    assert stopped["occurred_at"] == detected_at
    assert stopped["occurred_at"] < stopped["sent_at"]
    assert stopped["reason_code"] == "OBSTACLE"
    assert stopped["measurement"] == {"distance_m": 0.72}
    assert stopped["transition"] == {
        "mode_before": "PATROLLING",
        "mode_after": "SAFE_STOP",
    }


def test_a_new_obstacle_after_a_clear_is_confirmed_again(
    client, events, state, clock, lidar
):
    patrolling(state)
    for _ in range(2):
        lidar.set(True, 0.72)
        events.poll()
        clock.advance(CONFIRM_SEC)
        events.poll()
        lidar.set(False, 1.53)
        events.poll()
        clock.advance(TICK)
    assert [p["event_type"] for p in client.safety()] == [
        "SAFETY_STOPPED",
        "SAFETY_CLEARED",
        "SAFETY_STOPPED",
        "SAFETY_CLEARED",
    ]


@pytest.mark.parametrize("reason", ["REMOTE_ESTOP", "CRITICAL_BATTERY"])
def test_an_obstacle_during_another_safe_stop_reports_nothing(
    client, events, state, clock, lidar, reason
):
    """이미 다른 사유로 서 있으면 사유도 안 바뀌고 Event 도 안 나간다."""
    patrolling(state)
    state.safe_stop(reason)

    lidar.set(True, 0.72)
    events.poll()
    clock.advance(CONFIRM_SEC)
    events.poll()
    assert (state.mode, state.reason_code) == ("SAFE_STOP", reason)
    assert client.safety() == []

    lidar.set(False, 1.53)
    events.poll()
    # 장애물은 치워졌지만 로봇은 여전히 서 있다 — 해제가 아니다.
    assert client.safety() == []
    assert (state.mode, state.reason_code) == ("SAFE_STOP", reason)


def test_estop_after_a_confirmed_obstacle_suppresses_the_clear(
    client, events, state, clock, lidar
):
    """확정까지 간 장애물이라도, 그 사이 다른 사유가 들어왔으면 해제를 알리지 않는다."""
    patrolling(state)
    lidar.set(True, 0.72)
    events.poll()
    clock.advance(CONFIRM_SEC)
    events.poll()
    assert [p["event_type"] for p in client.safety()] == ["SAFETY_STOPPED"]

    state.safe_stop("REMOTE_ESTOP")  # 운영자가 눌렀다
    lidar.set(False, 1.53)
    events.poll()

    assert [p["event_type"] for p in client.safety()] == ["SAFETY_STOPPED"]
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")


def test_a_new_obstacle_after_the_estop_is_released_is_reported(
    client, events, state, clock, lidar
):
    patrolling(state)
    state.safe_stop("REMOTE_ESTOP")
    lidar.set(True, 0.72)
    events.poll()
    clock.advance(CONFIRM_SEC)
    events.poll()
    lidar.set(False, 1.53)
    events.poll()
    assert client.safety() == []

    state.resume()  # 운영자가 정지를 풀었다
    lidar.set(True, 0.60)
    events.poll()
    clock.advance(CONFIRM_SEC)
    events.poll()
    lidar.set(False, 1.80)
    events.poll()

    assert [p["event_type"] for p in client.safety()] == [
        "SAFETY_STOPPED",
        "SAFETY_CLEARED",
    ]
    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")


def test_an_obstacle_that_outlives_an_estop_stops_the_robot_again(
    client, events, state, clock, lidar
):
    """e-stop 이 풀렸는데 장애물이 그대로면 다시 서야 한다.

    감지 시각을 붙잡아 둔 채 확정만 계속 실패하면 그 사이 정지 판정이 영구히 죽는다.
    """
    patrolling(state)
    state.safe_stop("REMOTE_ESTOP")
    lidar.set(True, 0.30)
    for _ in range(int(CONFIRM_SEC / TICK) + 2):  # e-stop 동안에는 아무 것도 안 나간다
        events.poll()
        clock.advance(TICK)
    assert client.safety() == []

    state.resume()  # 관리자가 e-stop 을 풀었다 — 장애물은 아직 있다
    events.poll()
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "OBSTACLE")
    clock.advance(CONFIRM_SEC)
    events.poll()
    assert [p["event_type"] for p in client.safety()] == ["SAFETY_STOPPED"]


def test_an_estop_before_confirmation_restarts_the_detection(
    client, events, state, clock, lidar
):
    patrolling(state)
    lidar.set(True, 0.30)
    events.poll()  # 최초 감지 — SAFE_STOP/OBSTACLE
    state.safe_stop("REMOTE_ESTOP")  # 확정 전에 관리자가 눌렀다
    clock.advance(CONFIRM_SEC)
    events.poll()
    assert client.safety() == []  # 이 사건은 없던 것이 된다

    state.resume()
    events.poll()
    clock.advance(CONFIRM_SEC)
    events.poll()
    assert [p["event_type"] for p in client.safety()] == ["SAFETY_STOPPED"]


def test_the_motor_stops_before_the_state_is_published(client, state, clock, lidar):
    """§10.1 — 물리 정지가 먼저고 MQTT 는 그 결과를 보고한다."""
    stops = []
    watcher = EventPublisher(
        client,
        state,
        lidar=lidar,
        stop_motor=lambda: stops.append(state.mode),
        monotonic=clock.monotonic,
        now=clock.now,
    )
    patrolling(state)
    lidar.set(True, 0.30)
    watcher.poll()
    # 정지 호출 시점의 mode 가 아직 PATROLLING 이다 = 전이·발행보다 먼저 불렸다.
    assert stops == ["PATROLLING"]
    assert state.mode == "SAFE_STOP"


def test_a_failing_motor_stop_does_not_break_the_loop(client, state, clock, lidar):
    def boom():
        raise OSError("i2c bus is gone")

    watcher = EventPublisher(
        client, state, lidar=lidar, stop_motor=boom, monotonic=clock.monotonic, now=clock.now
    )
    patrolling(state)
    lidar.set(True, 0.30)
    watcher.poll()
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "OBSTACLE")


def test_missing_lidar_never_reports_an_obstacle(client, state, clock):
    bare = EventPublisher(client, state, monotonic=clock.monotonic, now=clock.now)
    patrolling(state)
    for _ in range(10):
        clock.advance(CONFIRM_SEC)
        bare.poll()
    assert client.safety() == []
    assert state.mode == "PATROLLING"


# --------------------------------------------------------------------------
# events/safety — 센서 고장 (계약 §10.5)
# --------------------------------------------------------------------------


def test_sensor_fault_and_recovery_are_reported_once_each(client, events, probes, clock):
    probes["lidar"] = "FAULT"
    events.poll()
    clock.advance(HEALTH_PROBE_INTERVAL_SEC)  # health 캐시를 넘겨 다시 읽게 한다
    events.poll()  # 계속 FAULT 라고 다시 알리지 않는다
    assert [p["event_type"] for p in client.safety()] == ["SENSOR_FAULT"]
    fault = client.safety()[-1]
    assert fault["reason_code"] == "LIDAR_FAULT"
    assert fault["measurement"] == {"distance_m": None}
    assert fault["auto_resume_allowed"] is False

    probes["lidar"] = "OK"
    clock.advance(HEALTH_PROBE_INTERVAL_SEC)
    events.poll()
    clock.advance(HEALTH_PROBE_INTERVAL_SEC)
    events.poll()
    assert [p["event_type"] for p in client.safety()] == [
        "SENSOR_FAULT",
        "SENSOR_RECOVERED",
    ]
    assert client.safety()[-1]["auto_resume_allowed"] is True


def test_motor_fault_uses_its_own_reason_code(client, events, probes):
    probes["motor"] = "FAULT"
    events.poll()
    assert client.safety()[-1]["reason_code"] == "MOTOR_FAULT"


def test_unknown_health_is_not_a_fault(client, events, probes):
    # UNKNOWN 은 '검사 전' 이지 고장이 아니다 (계약 §5.3).
    events.poll()
    assert client.safety() == []


def watching(client, state, probes, clock, **options) -> EventPublisher:
    """센서 고장 판정만 보는 EventPublisher. 정지 호출을 기억한다."""
    stops = []
    watcher = EventPublisher(
        client,
        state,
        stop_motor=lambda: stops.append(state.mode),
        monotonic=clock.monotonic,
        now=clock.now,
        **options,
    )
    watcher.stops = stops
    return watcher


def test_a_lidar_fault_stops_the_robot_while_it_is_driving(client, state, probes, clock):
    """눈이 먼 채로 굴러가게 두지 않는다. 정지가 발행보다 먼저다 (§16-10)."""
    watcher = watching(client, state, probes, clock)
    patrolling(state)
    probes["lidar"] = "FAULT"
    watcher.poll()

    assert watcher.stops == ["PATROLLING"]  # 세울 때는 아직 주행 중이었다
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "LIDAR_FAULT")
    fault = client.safety()[-1]
    assert (fault["event_type"], fault["reason_code"]) == ("SENSOR_FAULT", "LIDAR_FAULT")
    assert fault["transition"] == {"mode_before": "PATROLLING", "mode_after": "SAFE_STOP"}


def test_a_standing_robot_is_not_pushed_into_a_safe_stop(client, state, probes, clock):
    """장치가 하나도 없는 개발 PC 가 부팅하자마자 안전 정지에 갇히면 안 된다."""
    watcher = watching(client, state, probes, clock)
    state.boot_completed()
    probes["lidar"] = "FAULT"
    watcher.poll()

    assert watcher.stops == []
    assert state.mode == "READY"  # START_MISSION 이 막히지 않는다
    assert client.safety()[-1]["transition"] == {"mode_before": "READY", "mode_after": "READY"}


def test_driving_off_with_a_known_fault_is_stopped_too(client, state, probes, clock):
    """고장을 이미 알린 뒤에 주행이 시작되는 경로(START_MISSION)도 막아야 한다."""
    watcher = watching(client, state, probes, clock)
    state.boot_completed()
    probes["lidar"] = "FAULT"
    watcher.poll()  # READY 라 전이 없음
    state.start_patrol()
    watcher.poll()

    assert (state.mode, state.reason_code) == ("SAFE_STOP", "LIDAR_FAULT")
    # 사건은 처음 한 번만 알린다 — 매 tick 다시 발행하지 않는다 (§10.2).
    assert [p["event_type"] for p in client.safety()] == ["SENSOR_FAULT"]


def test_a_recovered_sensor_returns_to_the_mission_state(client, state, probes, clock):
    watcher = watching(client, state, probes, clock)
    patrolling(state)
    state.start_mission("MIS-20260726-001", zone_id="A-01")
    probes["lidar"] = "FAULT"
    watcher.poll()
    assert state.mode == "SAFE_STOP"

    probes["lidar"] = "OK"
    clock.advance(HEALTH_PROBE_INTERVAL_SEC)
    watcher.poll()

    assert (state.mode, state.reason_code) == ("PATROLLING", "NONE")
    recovered = client.safety()[-1]
    assert recovered["event_type"] == "SENSOR_RECOVERED"
    assert recovered["transition"] == {"mode_before": "SAFE_STOP", "mode_after": "PATROLLING"}


def test_one_recovery_does_not_release_a_second_fault(client, state, probes, clock):
    watcher = watching(client, state, probes, clock)
    patrolling(state)
    probes["lidar"] = "FAULT"
    probes["motor"] = "FAULT"
    watcher.poll()
    assert state.mode == "SAFE_STOP"

    probes["lidar"] = "OK"
    clock.advance(HEALTH_PROBE_INTERVAL_SEC)
    watcher.poll()
    # 모터가 아직 고장이다 — 반쯤 고장인 채로 내보내지 않는다.
    assert state.mode == "SAFE_STOP"


def test_a_remote_estop_is_not_released_by_a_sensor_recovery(client, state, probes, clock):
    watcher = watching(client, state, probes, clock)
    patrolling(state)
    probes["lidar"] = "FAULT"
    watcher.poll()
    state.safe_stop("REMOTE_ESTOP")  # 관리자가 그 위에 정지를 걸었다

    probes["lidar"] = "OK"
    clock.advance(HEALTH_PROBE_INTERVAL_SEC)
    watcher.poll()

    assert (state.mode, state.reason_code) == ("SAFE_STOP", "REMOTE_ESTOP")


def test_the_fault_stop_can_be_turned_off_for_a_bench_run(client, state, probes, clock):
    """장치 없는 PC 에서 주행 흐름을 흉내 낼 때만 끈다 (config safety 절)."""
    watcher = watching(client, state, probes, clock, sensor_fault_stops_driving=False)
    patrolling(state)
    probes["lidar"] = "FAULT"
    watcher.poll()

    assert watcher.stops == []
    assert state.mode == "PATROLLING"
    assert client.safety()[-1]["event_type"] == "SENSOR_FAULT"  # 알리기는 한다


# --------------------------------------------------------------------------
# events/battery — 등급 전이 (계약 §9, DoD 6~8)
# --------------------------------------------------------------------------


def test_a_single_dip_is_not_a_transition(client, events, state):
    feed_battery(state, events, 67.4, 19.8)
    assert client.battery() == []
    assert state.snapshot().battery_state == "NORMAL"


def test_flapping_across_the_threshold_publishes_once(client, events, state):
    """19.8↔20.2 로 출렁여도 BATTERY_LOW 는 한 번뿐이다 (히스테리시스 + 연속 확인)."""
    feed_battery(state, events, 67.4, 19.8, 19.5)
    assert [p["event_type"] for p in client.battery()] == ["BATTERY_LOW"]

    feed_battery(state, events, 20.2, 19.8, 20.2, 19.9, 20.4)
    assert len(client.battery()) == 1  # 20% 를 다시 넘겨도 복귀가 아니다
    assert state.snapshot().battery_state == "LOW"


def test_low_event_carries_the_threshold_and_measurement(client, events, state):
    patrolling(state)
    feed_battery(state, events, 67.4, 19.8, 19.5)
    low = client.battery()[-1]
    assert low["previous_state"] == "NORMAL"
    assert low["battery"] == {"valid": True, "percent": 19.5, "state": "LOW"}
    assert low["threshold_pct"] == 20.0
    # 조치를 먼저 하고 그 결과를 담는다 (§9.3).
    assert low["action_taken"]["robot_mode"] == "PAUSED"
    assert (state.mode, state.reason_code) == ("PAUSED", "LOW_BATTERY")


def test_recovery_needs_the_higher_threshold(client, events, state):
    feed_battery(state, events, 67.4, 19.8, 19.5)
    feed_battery(state, events, 24.9, 24.9)  # 25% 아래는 아직 복귀가 아니다
    assert len(client.battery()) == 1

    feed_battery(state, events, 25.1, 25.4)
    assert [p["event_type"] for p in client.battery()] == [
        "BATTERY_LOW",
        "BATTERY_RECOVERED",
    ]
    recovered = client.battery()[-1]
    assert recovered["previous_state"] == "LOW"
    assert recovered["battery"]["state"] == "NORMAL"
    assert recovered["threshold_pct"] == 25.0


def test_critical_stops_the_robot(client, events, state):
    patrolling(state)
    feed_battery(state, events, 67.4, 9.5, 9.2)
    critical = client.battery()[-1]
    assert critical["event_type"] == "BATTERY_CRITICAL"
    assert critical["threshold_pct"] == 10.0
    assert critical["action_taken"]["robot_mode"] == "SAFE_STOP"
    assert (state.mode, state.reason_code) == ("SAFE_STOP", "CRITICAL_BATTERY")


def test_climbing_out_of_critical_is_a_recovery(client, events, state):
    feed_battery(state, events, 67.4, 9.5, 9.2)
    feed_battery(state, events, 15.5, 15.8)
    recovered = client.battery()[-1]
    assert recovered["event_type"] == "BATTERY_RECOVERED"
    assert recovered["previous_state"] == "CRITICAL"
    assert recovered["battery"]["state"] == "LOW"
    assert recovered["threshold_pct"] == 15.0


def test_sensor_fault_and_recovery(client, events, state):
    feed_battery(state, events, 67.4)
    feed_battery(state, events, None, valid=False)
    fault = client.battery()[-1]
    assert fault["event_type"] == "BATTERY_SENSOR_FAULT"
    assert fault["previous_state"] == "NORMAL"
    assert fault["battery"] == {"valid": False, "percent": None, "state": "UNKNOWN"}
    assert fault["threshold_pct"] is None

    feed_battery(state, events, 66.0)
    assert client.battery()[-1]["event_type"] == "BATTERY_SENSOR_RECOVERED"


def test_a_healthy_first_reading_is_not_an_event(client, events, state):
    """기동 후 첫 판정이 NORMAL 인 것은 알릴 사건이 아니다."""
    feed_battery(state, events, 67.4)
    assert client.battery() == []


def test_a_low_first_reading_is_reported(client, events, state):
    feed_battery(state, events, 18.0)
    assert [p["event_type"] for p in client.battery()] == ["BATTERY_LOW"]
    assert client.battery()[-1]["previous_state"] == "UNKNOWN"


def test_state_telemetry_and_event_agree_on_the_battery(client, state, events, clock):
    """같은 시점의 세 경로가 같은 등급을 말한다 (DoD 8)."""
    reading = {"valid": True, "percent": 19.5}
    publisher = StatePublisher(client, state)
    sampler = TelemetrySampler(
        client,
        state,
        battery=lambda: dict(reading),
        monotonic=clock.monotonic,
        now=clock.now,
        thermal_root="no-thermal",
    )
    feed_battery(state, events, 67.4, 19.8, 19.5)
    sampler.poll()
    publisher.publish(force=True)

    event_battery = client.battery()[-1]["battery"]
    telemetry_battery = client.of(TopicKind.TELEMETRY)[-1]["battery"]
    state_battery = client.states()[-1]["battery"]
    assert event_battery["state"] == telemetry_battery["state"] == "LOW"
    assert state_battery["state"] == "LOW"
    assert event_battery["percent"] == telemetry_battery["percent"] == 19.5
    assert state_battery["valid"] is telemetry_battery["valid"] is True


def test_thresholds_come_from_the_config(client, clock):
    tuned = RobotState(
        battery_thresholds=BatteryThresholds(
            low_pct=30.0, low_recover_pct=35.0, confirm_samples=1
        ),
        monotonic=clock.monotonic,
    )
    watcher = EventPublisher(client, tuned, monotonic=clock.monotonic, now=clock.now)
    feed_battery(tuned, watcher, 50.0, 29.0)
    assert [p["event_type"] for p in client.battery()] == ["BATTERY_LOW"]
    assert client.battery()[-1]["threshold_pct"] == 30.0


def test_config_document_fills_the_thresholds():
    limits = BatteryThresholds.from_document(
        {
            "battery": {
                "low_pct": 20.0,
                "low_recover_pct": 25.0,
                "critical_pct": 10.0,
                "critical_recover_pct": 15.0,
                "confirm_samples": 2,
            }
        }
    )
    assert limits == BatteryThresholds()
    # 절이 없으면 계약 기본값 그대로다.
    assert BatteryThresholds.from_document({}) == BatteryThresholds()


def build_from(config_path, monkeypatch, tmp_path):
    """main.build() 를 저장소 밖에서 돌린다 — outbox 가 tmp 에 생기게."""
    import main

    for name in ("MQTT_HOST", "MQTT_PASSWORD", "UPLOAD_URL", "UPLOAD_API_KEY"):
        monkeypatch.setenv(name, "x")
<<<<<<< HEAD
=======
    monkeypatch.setenv("MQTT_ID", ROBOT_ID)
    monkeypatch.setenv("ROBOT_UUID", ROBOT_UUID)
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    monkeypatch.chdir(tmp_path)
    return main.build(config_path=config_path)


def test_shipped_config_reaches_the_assembled_modules(monkeypatch, tmp_path):
    import main

    client, state, _publisher, _sampler, events, _commands = build_from(
        main.CONFIG_PATH, monkeypatch, tmp_path
    )
    try:
        assert state.battery_thresholds == BatteryThresholds(
            low_pct=20.0,
            low_recover_pct=25.0,
            critical_pct=10.0,
            critical_recover_pct=15.0,
            confirm_samples=2,
        )
        assert events._obstacle_confirm_sec == 3.0
    finally:
        client.outbox.close()


def test_a_config_without_a_safety_section_keeps_the_default(monkeypatch, tmp_path):
    config = tmp_path / "config.yaml"
    config.write_text(
        "robot:\n  id: RBT-001\nmqtt:\n  host: broker.example.com\n",
        encoding="utf-8",
    )
    client, _state, _publisher, _sampler, events, _commands = build_from(
        config, monkeypatch, tmp_path
    )
    try:
        assert events._obstacle_confirm_sec == OBSTACLE_CONFIRM_SEC == 3.0
    finally:
        client.outbox.close()


def test_the_configured_wait_drives_the_confirmation(client, state, clock, lidar):
    """재확인 시점은 주입한 값 그대로 움직인다."""
    tuned = EventPublisher(
        client,
        state,
        lidar=lidar,
        obstacle_confirm_sec=5.0,
        monotonic=clock.monotonic,
        now=clock.now,
    )
    patrolling(state)
    lidar.set(True, 0.72)
    tuned.poll()
    clock.advance(CONFIRM_SEC)  # 기본값이었다면 여기서 확정됐다
    tuned.poll()
    assert client.safety() == []
    clock.advance(5.0 - CONFIRM_SEC)
    tuned.poll()
    assert [p["event_type"] for p in client.safety()] == ["SAFETY_STOPPED"]


# --------------------------------------------------------------------------
# events/mission (계약 §11, DoD 9)
# --------------------------------------------------------------------------


def test_start_pause_resume_carry_no_result(client, events, state):
    state.start_mission("MIS-20260726-001", zone_id="A-01")
    events.mission_started()
    events.mission_paused()
    events.mission_resumed()

    assert [p["event_type"] for p in client.mission()] == [
        "MISSION_STARTED",
        "MISSION_PAUSED",
        "MISSION_RESUMED",
    ]
    for payload in client.mission():
        assert "result" not in payload
        assert payload["mission_id"] == "MIS-20260726-001"
        assert payload["source_component"] == "mission"


def test_pause_and_resume_move_the_mission_state(client, events, state):
    state.start_mission("MIS-20260726-001")
    events.mission_paused()
    assert state.mission_state == "PAUSED"
    events.mission_resumed()
    assert state.mission_state == "RUNNING"


def test_completion_carries_the_full_result(client, events, state, clock):
    state.start_mission("MIS-20260726-001")
    state.record_incident()
    state.record_normal_detection()
    state.record_normal_detection()
    clock.advance(4182)

    events.mission_completed()

    completed = client.mission()[-1]
    assert completed["event_type"] == "MISSION_COMPLETED"
    assert completed["result"] == {
        "duration_sec": 4182,
        "incident_count": 1,
        "normal_detection_count": 2,
        "completion_reason": "ROUTE_COMPLETED",
    }
    # 발행이 끝나면 보관함도 비워진다 — 종료 상태로 elapsed_sec 이 자라지 않는다.
    assert (state.mission_id, state.mission_state) == (None, None)
    assert state.mission_elapsed_sec is None


@pytest.mark.parametrize(
    "finish, event_type",
    [
        (lambda e: e.mission_cancelled("OPERATOR_CANCELLED"), "MISSION_CANCELLED"),
        (lambda e: e.mission_failed("LIDAR_FAULT"), "MISSION_FAILED"),
    ],
)
def test_cancel_and_failure_also_carry_a_result(client, events, state, finish, event_type):
    state.start_mission("MIS-20260726-001")
    finish(events)
    payload = client.mission()[-1]
    assert payload["event_type"] == event_type
    assert set(payload["result"]) == {
        "duration_sec",
        "incident_count",
        "normal_detection_count",
        "completion_reason",
    }
    assert state.mission_id is None


def test_the_result_is_read_before_the_slot_is_cleared(client, events, state):
    """집계를 payload 로 복사한 뒤에 보관함을 비운다 — 순서를 틀릴 방법이 없다."""
    state.start_mission("MIS-20260726-001")
    state.record_incident()
    events.mission_completed()
    assert client.mission()[-1]["result"]["incident_count"] == 1
    assert state.mission_id is None
    state.end_mission()  # 한 번 더 불러도 안전하다
    assert state.mission_incident_count is None


def test_telemetry_stops_carrying_the_mission_after_it_ends(
    client, events, state, clock, tmp_path
):
    """종료 event 직후의 표본에는 mission 이 남아 있으면 안 된다."""
    sampler = TelemetrySampler(
        client,
        state,
        monotonic=clock.monotonic,
        now=clock.now,
        thermal_root=tmp_path / "no-thermal",
    )
    state.start_mission("MIS-20260726-001", zone_id="A-01")
    sampler.poll()
    assert client.of(TopicKind.TELEMETRY)[-1]["mission_id"] == "MIS-20260726-001"

    events.mission_cancelled("OPERATOR_CANCELLED")
    clock.advance(TELEMETRY_INTERVAL_SEC)
    sampler.poll()

    sample = client.of(TopicKind.TELEMETRY)[-1]
    assert sample["mission_id"] is None
    assert sample["mission"] is None


@pytest.mark.parametrize(
    "call",
    [
        lambda e: e.mission_started(),
        lambda e: e.mission_paused(),
        lambda e: e.mission_resumed(),
        lambda e: e.mission_completed(),
    ],
)
def test_mission_events_need_an_active_mission(client, events, call):
    with pytest.raises(ValueError):
        call(events)
    assert client.mission() == []


# --------------------------------------------------------------------------
# events/incident (계약 §8, DoD 10·11)
# --------------------------------------------------------------------------


def report(events, **overrides) -> dict:
    kwargs = dict(image_width=1280, image_height=720)
    kwargs.update(overrides)
    return events.report_incident(DETECTION, **kwargs)


def test_incident_carries_the_detection_and_the_model(client, events, state, gps):
    state.start_mission("MIS-20260726-001", zone_id="A-01")
    gps.set(valid=True, lat=37.4979, lon=127.0276)
    report(events)

    incident = client.incidents()[-1]
    assert incident["event_type"] == "BRAILLE_BLOCK_INCIDENT_DETECTED"
    assert incident["source_component"] == "ai"
    assert incident["location"] == {
        "valid": True,
        "lat": 37.4979,
        "lon": 127.0276,
        # START_MISSION 으로 받은 값을 그대로 echo 한다.
        "zone_id": "A-01",
    }
    assert incident["incident"] == {
        "asset_type": "BRAILLE_BLOCK",
        "block_type": "LINEAR",
        "defect_type": "DAMAGED",
        "confidence": 0.87,
        "attributes": {"damage_ratio": 0.32},
    }
    assert incident["evidence"]["bbox_norm"] == [0.16, 0.42, 0.81, 0.95]
    assert incident["model"] == {"name": "tbs-braille-yolo", "version": "1.0.0"}
    assert incident["mission_id"] == "MIS-20260726-001"


def test_failed_upload_still_publishes_with_a_null_image_id(
    client, events, upload, tmp_path
):
<<<<<<< HEAD
    """서버 images 엔드포인트가 아직 없어 지금은 항상 이 경로다 (§8.3)."""
=======
    """업로드가 실패해도 image_id: null 로 incident event 는 그대로 발행한다 (§8.3).

    evidence.image_id 는 UUID 또는 null 이다 — 이미지 때문에 결함 사건을 삼키지 않는다.
    """
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
    image = tmp_path / "frame.jpg"
    image.write_bytes(b"jpeg")
    upload.image_id = None

    report(events, image_path=image)

    incident = client.incidents()[-1]
    assert incident["evidence"]["image_id"] is None
    # 업로드는 event_id 로 이미지를 묶는다 (계약 §7.2).
<<<<<<< HEAD
    assert upload.calls == [(image, incident["event_id"], ROBOT_ID)]
=======
    # form 의 robot_id 는 DB UUID 다 — mqtt_id 를 보내면 서버가 400 을 준다.
    assert upload.calls == [(image, incident["event_id"], ROBOT_UUID)]
    assert upload.captured_at == incident["evidence"]["captured_at"]
>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84


def test_successful_upload_fills_the_image_id(client, events, upload, tmp_path):
    image = tmp_path / "frame.jpg"
    image.write_bytes(b"jpeg")
    upload.image_id = "550e8400-e29b-41d4-a716-446655440000"
    report(events, image_path=image)
    assert (
        client.incidents()[-1]["evidence"]["image_id"]
        == "550e8400-e29b-41d4-a716-446655440000"
    )


<<<<<<< HEAD
=======
def test_the_upload_uuid_can_be_filled_in_at_runtime(client, events, upload, tmp_path):
    """웹이 로봇 등록 뒤에 내려주는 값이라 기동 시점에는 없을 수 있다."""
    image = tmp_path / "frame.jpg"
    image.write_bytes(b"jpeg")
    events.set_upload_robot_uuid(None)
    report(events, image_path=image)
    assert upload.calls[-1][2] is None

    events.set_upload_robot_uuid(ROBOT_UUID)
    report(events, image_path=image)
    assert upload.calls[-1][2] == ROBOT_UUID


>>>>>>> f869ccfb8170f77fa8cc6aafeaf699ec33e46b84
def test_no_image_means_no_upload_attempt(client, events, upload):
    report(events)
    assert upload.calls == []
    assert client.incidents()[-1]["evidence"]["image_id"] is None


def test_incident_is_published_without_a_gps_fix(client, events, state, gps):
    state.start_mission("MIS-20260726-001", zone_id="A-01")
    gps.set(valid=False, lat=None, lon=None)
    report(events)
    assert client.incidents()[-1]["location"] == {
        "valid": False,
        "lat": None,
        "lon": None,
        "zone_id": "A-01",
    }


def test_only_published_incidents_are_counted(client, events, state):
    state.start_mission("MIS-20260726-001")
    report(events)
    report(events)
    assert state.mission_incident_count == 2
    assert len(client.incidents()) == 2


def test_an_incident_outside_a_mission_is_still_published(client, events, state):
    report(events)
    incident = client.incidents()[-1]
    assert incident["mission_id"] is None
    assert incident["location"]["zone_id"] is None
    assert state.mission_incident_count is None


def test_detection_stub_reports_nothing_yet():
    from detect import inference

    assert inference.infer() is None
    assert inference.MODEL_NAME and inference.MODEL_VERSION


def test_lidar_stub_reports_no_obstacle():
    from drive import safety

    assert safety.check() == (False, None)


def test_main_wires_every_event_source():
    import main
    from detect import inference
    from drive import safety
    from sensors import gps as gps_module

    assert main.event_sources() == {
        "lidar": safety.check,
        "gps": gps_module.read_position,
        "model_name": inference.MODEL_NAME,
        "model_version": inference.MODEL_VERSION,
    }


# --------------------------------------------------------------------------
# Outbox 경유·재전송 (계약 §7, §16-5, DoD 12)
# --------------------------------------------------------------------------


class FakePaho:
    """MqttClient 가 부르는 paho API 중 이 테스트가 쓰는 것만. PUBACK 은 오지 않는다."""

    def __init__(self):
        self.on_connect = self.on_disconnect = None
        self.on_message = self.on_publish = None
        self.published = []  # (topic, payload)

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
        pass

    def publish(self, topic, payload=None, qos=0, retain=False, properties=None):
        self.published.append((topic, payload))

        class _Info:
            mid = 1
            rc = 0

            def wait_for_publish(self, timeout=None):
                pass

        return _Info()

    def simulate_connect(self):
        self.on_connect(self, None, {}, 0, None)

    def events_on(self, suffix: str) -> list:
        topic = f"tbs/v2/robots/{ROBOT_ID}/events/{suffix}"
        return [json.loads(p) for sent, p in self.published if sent == topic]


@pytest.fixture
def wired(tmp_path, clock, lidar, gps, upload):
    """진짜 MqttClient + RobotState + EventPublisher 조립."""
    paho = FakePaho()
    config = MqttConfig(
        robot_id=ROBOT_ID,
        host="broker.example.com",
        outbox_path=str(tmp_path / "outbox.sqlite3"),
    )
    client = MqttClient(config, client_factory=lambda _c: paho)
    state = RobotState(monotonic=clock.monotonic)
    publisher = EventPublisher(
        client,
        state,
        lidar=lidar,
        gps=gps,
        model_name="tbs-braille-yolo",
        model_version="1.0.0",
        upload=upload,
        monotonic=clock.monotonic,
        now=clock.now,
    )
    yield paho, client, state, publisher, clock, lidar
    client.outbox.close()


def emit_all_four(state, publisher, clock, lidar) -> None:
    """4종을 한 번씩 만든다."""
    patrolling(state)
    state.start_mission("MIS-20260726-001", zone_id="A-01")
    publisher.mission_started()
    report(publisher)
    lidar.set(True, 0.72)
    publisher.poll()
    clock.advance(CONFIRM_SEC)
    publisher.poll()
    state.set_battery(valid=True, percent=18.0)
    publisher.poll()


def test_all_four_events_go_through_the_outbox(wired):
    paho, client, state, publisher, clock, lidar = wired
    emit_all_four(state, publisher, clock, lidar)
    # 접속 전이라 아무 것도 나가지 않았지만 유실되지도 않았다.
    assert paho.published == []
    assert client.outbox.pending_count() == 4


def test_resend_keeps_event_id_and_occurred_at(wired):
    paho, client, state, publisher, clock, lidar = wired
    emit_all_four(state, publisher, clock, lidar)

    paho.simulate_connect()
    assert client.flush_outbox() == 4

    for suffix in ("incident", "battery", "safety", "mission"):
        sent = paho.events_on(suffix)
        assert len(sent) == 1, suffix
    resent = {
        payload["event_id"]: payload
        for suffix in ("incident", "battery", "safety", "mission")
        for payload in paho.events_on(suffix)
    }
    stored = {
        record.event_id: record.payload for record in client.outbox.pending(limit=10)
    }
    assert set(resent) == set(stored)
    for event_id, payload in resent.items():
        original = stored[event_id]
        assert payload["occurred_at"] == original["occurred_at"]
        assert payload["seq"] == original["seq"]
        assert payload["ttl_sec"] == 300
        # 재전송에서 갱신되는 것은 sent_at 하나뿐이다 (§7).
        assert {k: v for k, v in payload.items() if k != "sent_at"} == {
            k: v for k, v in original.items() if k != "sent_at"
        }


def test_events_do_not_share_the_seq_with_nothing_else(wired):
    paho, client, state, publisher, clock, lidar = wired
    emit_all_four(state, publisher, clock, lidar)
    seqs = [record.payload["seq"] for record in client.outbox.pending(limit=10)]
    assert sorted(seqs) == [1, 2, 3, 4]
