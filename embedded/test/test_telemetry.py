"""telemetry.py 단위 테스트 — 표본 값 생성 경계 · 5초 발행 타이밍.

브로커도 paho 도 없이 돈다. 시계·센서 읽기는 주입해서 5초를 실제로 기다리지 않고
검증한다. payload 는 진짜 factory 가 만든다 — 계약 위반은 factory 가 잡아야 한다.
기준 문서: web/docs/final-mqtt-topic-schema-v2.md §6
"""

import json
import logging

import pytest

from comms import MessageFactory, MqttClient, MqttConfig, TopicKind
from robot_state import RobotState, StatePublisher
from telemetry import (
    FAILURE_ALERT_THRESHOLD,
    NOT_MOVING_MODES,
    TELEMETRY_INTERVAL_SEC,
    TelemetrySampler,
    jetson_temp_c,
)

ROBOT_ID = "RBT-001"
TELEMETRY_TOPIC = f"tbs/v2/robots/{ROBOT_ID}/telemetry"
STATE_TOPIC = f"tbs/v2/robots/{ROBOT_ID}/state"
TICK = 0.5  # main.LOOP_INTERVAL_SEC


# --------------------------------------------------------------------------
# 테스트 도구
# --------------------------------------------------------------------------


class RecordingClient:
    """TelemetrySampler 가 쓰는 부분만. payload 는 진짜 factory 가 만든다."""

    def __init__(self, robot_id: str = ROBOT_ID) -> None:
        self.messages = MessageFactory(robot_id)
        self.published = []  # (kind, payload)

    def publish(self, kind, payload, *, wait_sec=None) -> bool:
        self.published.append((kind, payload))
        return True

    def samples(self) -> list:
        return [p for kind, p in self.published if kind is TopicKind.TELEMETRY]

    def states(self) -> list:
        return [p for kind, p in self.published if kind is TopicKind.STATE]


class FakeClock:
    def __init__(self, start: float = 1000.0) -> None:
        self.value = start
        self.stamps = 0

    def monotonic(self) -> float:
        return self.value

    def now(self) -> str:
        """sampled_at. 표본마다 다른 값이어야 순서를 볼 수 있다."""
        self.stamps += 1
        return f"2026-07-26T05:00:{self.stamps:02d}.000Z"

    def advance(self, seconds: float) -> None:
        self.value += seconds


class FakeGps:
    """읽을 때마다 지금 설정된 값을 돌려준다."""

    def __init__(self, **reading) -> None:
        self.reading = reading or {
            "valid": False,
            "lat": None,
            "lon": None,
            "heading_deg": None,
        }

    def set(self, **reading) -> None:
        self.reading = reading

    def __call__(self) -> dict:
        return dict(self.reading)


class FakeBattery:
    def __init__(self, **reading) -> None:
        self.reading = reading or {"valid": False, "percent": None}

    def set(self, **reading) -> None:
        self.reading = reading

    def __call__(self) -> dict:
        return dict(self.reading)


class FakeMotor:
    """주행 PWM 명령 중인지 — 실제 회전이 아니라 제어 명령 기준이다 (§6.4)."""

    def __init__(self, driving: bool = False) -> None:
        self.driving = driving

    def __call__(self) -> bool:
        return self.driving


@pytest.fixture
def clock():
    return FakeClock()


@pytest.fixture
def client():
    return RecordingClient()


@pytest.fixture
def state(clock):
    # mission_elapsed_sec 은 저장값이 아니라 계산값이라 시계를 함께 넣는다.
    return RobotState(monotonic=clock.monotonic)


@pytest.fixture
def gps():
    return FakeGps()


@pytest.fixture
def battery():
    return FakeBattery()


@pytest.fixture
def motor():
    return FakeMotor()


@pytest.fixture
def sampler(client, state, clock, gps, battery, motor, tmp_path):
    return TelemetrySampler(
        client,
        state,
        gps=gps,
        battery=battery,
        motor=motor,
        monotonic=clock.monotonic,
        now=clock.now,
        # 개발 PC 처럼 thermal zone 이 없는 경로.
        thermal_root=tmp_path / "no-thermal",
    )


def patrolling(state: RobotState) -> RobotState:
    state.boot_completed()
    state.start_patrol()
    return state


# --------------------------------------------------------------------------
# 발행 타이밍 (계약 §6.1, DoD 1)
# --------------------------------------------------------------------------


def test_first_poll_publishes(client, sampler):
    assert sampler.poll() is True
    assert len(client.samples()) == 1


def test_ticks_between_intervals_do_not_publish(client, sampler, clock):
    sampler.poll()
    for _ in range(int(TELEMETRY_INTERVAL_SEC / TICK) - 1):
        clock.advance(TICK)
        assert sampler.poll() is False
    assert len(client.samples()) == 1


def test_publishes_once_every_five_seconds(client, sampler, clock):
    sampler.poll()
    for _ in range(3):
        clock.advance(TELEMETRY_INTERVAL_SEC)
        assert sampler.poll() is True
    assert len(client.samples()) == 4


def test_no_catch_up_burst_after_a_long_stall(client, sampler, clock):
    """루프가 한참 멈췄다 돌아와도 밀린 만큼 몰아 보내지 않는다 — 최신 1개뿐이다."""
    sampler.poll()
    clock.advance(TELEMETRY_INTERVAL_SEC * 10)
    assert sampler.poll() is True
    assert sampler.poll() is False
    assert len(client.samples()) == 2


def test_state_transition_does_not_trigger_telemetry(client, sampler, state, clock):
    """telemetry 에는 '전이 즉시' 가 없다. 주기 하나뿐이다."""
    sampler.poll()
    clock.advance(TICK)
    state.boot_completed()
    assert sampler.poll() is False
    assert len(client.samples()) == 1


# --------------------------------------------------------------------------
# 유실 허용 — 발행 실패가 루프를 멈추지 않는다 (계약 §6.1)
# --------------------------------------------------------------------------


class FlakyClient(RecordingClient):
    """발행 실패를 켜고 끌 수 있는 클라이언트."""

    def __init__(self, broken: bool = True) -> None:
        super().__init__()
        self.broken = broken

    def publish(self, kind, payload, *, wait_sec=None) -> bool:
        if self.broken:
            raise OSError("socket is gone")
        return super().publish(kind, payload, wait_sec=wait_sec)


@pytest.fixture
def flaky(state, clock, tmp_path):
    """항상 실패하는 발행. thermal zone 이 없어 온도 로그도 끼어들지 않는다."""
    client = FlakyClient()
    sampler = TelemetrySampler(
        client,
        state,
        monotonic=clock.monotonic,
        now=clock.now,
        thermal_root=tmp_path,
    )
    return client, sampler


def levels_per_poll(sampler, clock, caplog, times: int) -> list:
    """poll 한 번마다 그때 찍힌 로그 등급 목록."""
    seen = []
    for _ in range(times):
        caplog.clear()
        sampler.poll()
        seen.append([record.levelno for record in caplog.records])
        clock.advance(TELEMETRY_INTERVAL_SEC)
    return seen


def test_publish_failure_does_not_stop_the_loop(flaky, clock):
    _client, broken = flaky
    assert broken.poll() is False  # 예외가 여기까지 올라오면 메인 루프가 죽는다
    clock.advance(TELEMETRY_INTERVAL_SEC)
    assert broken.poll() is False


def test_repeated_failures_are_escalated_exactly_once(flaky, clock, caplog):
    """헤드리스 로봇에서 5초마다 찍히는 warning 은 아무도 안 본다."""
    caplog.set_level(logging.DEBUG, logger="telemetry")
    _client, broken = flaky

    seen = levels_per_poll(broken, clock, caplog, times=5)

    raised = [logging.ERROR in levels for levels in seen]
    assert raised == [False, False, True, False, False]
    assert FAILURE_ALERT_THRESHOLD == 3
    # 임계치 전후로는 warning 만 남는다.
    assert all(levels == [logging.WARNING] for levels in seen[:2] + seen[3:])


def test_a_successful_sample_resets_the_failure_counter(flaky, clock, caplog):
    caplog.set_level(logging.DEBUG, logger="telemetry")
    client, sampler = flaky
    levels_per_poll(sampler, clock, caplog, times=2)

    client.broken = False
    caplog.clear()
    assert sampler.poll() is True
    assert [record.levelno for record in caplog.records] == [logging.INFO]

    # 카운터가 0 이 됐으므로 다음 연속 실패도 3회째에야 error 다.
    client.broken = True
    clock.advance(TELEMETRY_INTERVAL_SEC)
    seen = levels_per_poll(sampler, clock, caplog, times=3)
    assert [logging.ERROR in levels for levels in seen] == [False, False, True]


def test_a_failed_sample_is_not_retried_next_tick(client, sampler, clock, gps):
    """실패한 표본을 다음 tick 에 다시 보내면 그게 계약이 금지한 재전송이다."""
    gps.set(valid=True, lat=37.4979, lon=127.0276, heading_deg=999.0)
    assert sampler.poll() is False  # factory 가 범위 밖 heading 을 거부한다
    clock.advance(TICK)
    assert sampler.poll() is False  # 기한 전이다 — 재시도하지 않는다
    assert client.samples() == []


# --------------------------------------------------------------------------
# position (계약 §6.4, §16-7·8, DoD 4·5)
# --------------------------------------------------------------------------


def test_valid_fix_is_published(client, sampler, gps):
    gps.set(valid=True, lat=37.4979, lon=127.0276, heading_deg=92.3)
    sampler.poll()
    assert client.samples()[-1]["position"] == {
        "valid": True,
        "lat": 37.4979,
        "lon": 127.0276,
        "heading_deg": 92.3,
    }


def test_lost_fix_nulls_everything_and_never_copies_the_last_position(
    client, sampler, gps, clock
):
    gps.set(valid=True, lat=37.4979, lon=127.0276, heading_deg=92.3)
    sampler.poll()
    gps.set(valid=False, lat=None, lon=None, heading_deg=None)
    clock.advance(TELEMETRY_INTERVAL_SEC)
    sampler.poll()

    assert client.samples()[-1]["position"] == {
        "valid": False,
        "lat": None,
        "lon": None,
        "heading_deg": None,
    }


def test_valid_without_coordinates_is_not_a_fix(client, sampler, gps):
    # valid 를 자칭해도 좌표가 없으면 Fix 가 아니다 — 표본을 잃지 않고 null 로 낸다.
    gps.set(valid=True, lat=None, lon=None, heading_deg=92.3)
    assert sampler.poll() is True
    assert client.samples()[-1]["position"]["valid"] is False
    assert client.samples()[-1]["position"]["heading_deg"] is None


def test_missing_gps_reader_reports_no_fix(client, state, clock, tmp_path):
    bare = TelemetrySampler(
        client, state, monotonic=clock.monotonic, now=clock.now, thermal_root=tmp_path
    )
    bare.poll()
    assert client.samples()[-1]["position"]["valid"] is False


def test_heading_is_null_when_course_is_not_provided(client, sampler, gps):
    """정지·저속이면 RMC 가 COG 를 주지 않는다 (§16-8)."""
    gps.set(valid=True, lat=37.4979, lon=127.0276, heading_deg=None)
    sampler.poll()
    payload = client.samples()[-1]["position"]
    assert payload["valid"] is True
    assert payload["heading_deg"] is None


@pytest.mark.parametrize("heading", [-0.1, 360.0, 720.0])
def test_heading_outside_the_range_is_rejected(sampler, gps, heading):
    gps.set(valid=True, lat=37.4979, lon=127.0276, heading_deg=heading)
    with pytest.raises(ValueError):
        sampler.publish()


@pytest.mark.parametrize("heading", [0.0, 359.9])
def test_heading_range_boundaries_are_accepted(client, sampler, gps, heading):
    gps.set(valid=True, lat=37.4979, lon=127.0276, heading_deg=heading)
    sampler.poll()
    assert client.samples()[-1]["position"]["heading_deg"] == heading


# --------------------------------------------------------------------------
# motion (계약 §6.4, DoD 6·7)
# --------------------------------------------------------------------------


def test_speed_is_always_null(client, sampler, motor, state, clock):
    """엔코더가 없다. 주행 중이든 아니든 null 이다."""
    for driving in (False, True):
        motor.driving = driving
        clock.advance(TELEMETRY_INTERVAL_SEC)
        sampler.poll()
    assert [p["motion"]["speed_mps"] for p in client.samples()] == [None, None]


def test_moving_is_true_while_driving(client, sampler, motor, state):
    patrolling(state)
    motor.driving = True
    sampler.poll()
    assert client.samples()[-1]["motion"]["moving"] is True


def test_not_moving_without_a_drive_command(client, sampler, motor, state):
    patrolling(state)
    motor.driving = False
    sampler.poll()
    assert client.samples()[-1]["motion"]["moving"] is False


@pytest.mark.parametrize(
    "stop",
    [
        lambda s: s.pause(),
        lambda s: s.safe_stop("OBSTACLE"),
        lambda s: s.fault("INTERNAL_ERROR"),
    ],
)
def test_stopped_modes_are_not_moving_even_with_a_drive_command(
    client, sampler, motor, state, stop
):
    patrolling(state)
    motor.driving = True  # 명령이 남아 있어도 이 모드에서는 움직이는 게 아니다
    stop(state)
    sampler.poll()
    assert state.mode in NOT_MOVING_MODES
    assert client.samples()[-1]["motion"]["moving"] is False


def test_missing_motor_reader_is_not_moving(client, state, clock, tmp_path):
    bare = TelemetrySampler(
        client, state, monotonic=clock.monotonic, now=clock.now, thermal_root=tmp_path
    )
    bare.poll()
    assert client.samples()[-1]["motion"]["moving"] is False


# --------------------------------------------------------------------------
# system.jetson_temp_c (계약 §6.4, DoD 8)
# --------------------------------------------------------------------------


def write_zones(root, *millidegrees, types=()) -> None:
    """thermal_zone{n}/temp 를 만든다. types 에 준 만큼만 type 파일이 생긴다."""
    for index, milli in enumerate(millidegrees):
        zone = root / f"thermal_zone{index}"
        zone.mkdir(parents=True)
        (zone / "temp").write_text(str(milli))
        if index < len(types) and types[index] is not None:
            (zone / "type").write_text(types[index])


def test_highest_zone_wins(tmp_path):
    write_zones(tmp_path, 41500, 47250, 39000)
    assert jetson_temp_c(tmp_path) == 47.25


def test_a_hotter_zone_wins_over_the_junction_zone(tmp_path):
    # junction(tj-thermal)도 zone 목록에 있어 최고값 계산에 이미 들어간다.
    # 그것만 골라 돌려주면 더 뜨거운 zone 을 가려 과소보고가 된다.
    write_zones(tmp_path, 41500, 47250, types=("tj-thermal", "cpu-thermal"))
    assert jetson_temp_c(tmp_path) == 47.25


def test_junction_zone_wins_when_it_is_the_hottest(tmp_path):
    write_zones(tmp_path, 47250, 41500, types=("tj-thermal", "cpu-thermal"))
    assert jetson_temp_c(tmp_path) == 47.25


def test_unreadable_zone_is_skipped_not_fatal(tmp_path):
    write_zones(tmp_path, 41500)
    broken = tmp_path / "thermal_zone9"
    broken.mkdir()
    (broken / "temp").write_text("n/a")
    assert jetson_temp_c(tmp_path) == 41.5


def test_missing_thermal_path_is_null_not_an_error(tmp_path):
    # 개발 PC 에는 /sys/class/thermal 이 없다. 예외 대신 null 이다.
    assert jetson_temp_c(tmp_path / "no-such-dir") is None
    assert jetson_temp_c(tmp_path) is None  # 디렉터리는 있지만 zone 이 없다


def test_sample_without_thermal_zones_has_null_temp(client, sampler):
    sampler.poll()
    assert client.samples()[-1]["system"] == {"jetson_temp_c": None}


def test_the_real_default_path_never_raises():
    """기본 경로(THERMAL_ROOT)를 그대로 읽어도 터지지 않는다."""
    value = jetson_temp_c()
    assert value is None or isinstance(value, float)


# --------------------------------------------------------------------------
# battery — RobotState 가 단일 출처 (계약 §6.3, DoD 9·10)
# --------------------------------------------------------------------------


def test_sensor_reading_goes_through_robot_state(sampler, state, battery):
    battery.set(valid=True, percent=67.4, source="MEASURED")
    sampler.poll()
    snapshot = state.snapshot()
    assert (snapshot.battery_valid, snapshot.battery_percent) == (True, 67.4)
    assert (snapshot.battery_state, snapshot.battery_source) == ("NORMAL", "MEASURED")


def test_state_and_telemetry_report_the_same_battery(client, state, sampler, battery):
    battery.set(valid=True, percent=67.4, source="MEASURED")
    sampler.poll()
    StatePublisher(client, state).publish(force=True)

    telemetry_battery = client.samples()[-1]["battery"]
    state_battery = client.states()[-1]["battery"]
    for member in ("valid", "percent", "state"):
        assert telemetry_battery[member] == state_battery[member]
    # source 는 telemetry 에만 있다 (§6.3).
    assert telemetry_battery["source"] == "MEASURED"
    assert "source" not in state_battery


def test_estimated_source_is_carried_through(client, sampler, battery):
    battery.set(valid=True, percent=52.0, source="ESTIMATED")
    sampler.poll()
    assert client.samples()[-1]["battery"]["source"] == "ESTIMATED"


def test_invalid_battery_nulls_percent_and_source(client, sampler, battery):
    battery.set(valid=False, percent=None)
    sampler.poll()
    assert client.samples()[-1]["battery"] == {
        "valid": False,
        "percent": None,
        "state": "UNKNOWN",
        "source": None,
    }


def test_unknown_battery_source_is_rejected(state):
    with pytest.raises(ValueError):
        state.set_battery(valid=True, percent=50.0, source="GUESSED")


def test_missing_battery_reader_leaves_robot_state_alone(client, state, clock, tmp_path):
    state.set_battery(valid=True, percent=80.0)
    bare = TelemetrySampler(
        client, state, monotonic=clock.monotonic, now=clock.now, thermal_root=tmp_path
    )
    bare.poll()
    assert client.samples()[-1]["battery"]["percent"] == 80.0


# --------------------------------------------------------------------------
# mission (계약 §6.3, DoD 12)
# --------------------------------------------------------------------------


def test_mission_is_null_without_an_active_mission(client, sampler, state):
    sampler.poll()
    payload = client.samples()[-1]
    assert payload["mission_id"] is None
    assert payload["mission"] is None
    assert (state.mission_elapsed_sec, state.mission_incident_count) == (None, None)


def test_start_mission_fills_the_whole_block(client, sampler, state):
    """mission_id 가 생기면 state·elapsed_sec·incident_count 셋 다 필수다."""
    state.start_mission("MIS-20260726-001", zone_id="ZONE-A")
    assert sampler.poll() is True
    payload = client.samples()[-1]
    assert payload["mission_id"] == "MIS-20260726-001"
    assert payload["mission"] == {
        "state": "RUNNING",
        "elapsed_sec": 0,
        "incident_count": 0,
    }
    assert state.zone_id == "ZONE-A"


def test_incident_count_follows_record_incident(client, sampler, state):
    state.start_mission("MIS-20260726-001")
    state.record_incident()
    assert state.record_incident() == 2
    sampler.poll()
    assert client.samples()[-1]["mission"]["incident_count"] == 2


def test_elapsed_sec_grows_with_the_clock(client, sampler, state, clock):
    state.start_mission("MIS-20260726-001")
    sampler.poll()
    clock.advance(583)
    sampler.poll()
    # 저장값이 아니라 계산값이다 — 아무도 주기적으로 갱신하지 않는다.
    assert [p["mission"]["elapsed_sec"] for p in client.samples()] == [0, 583]


def test_elapsed_sec_keeps_counting_while_paused(client, sampler, state, clock):
    """일시정지 중에도 벽시계 경과 그대로 센다 — 멈춤 여부는 R-5 결정이다."""
    state.start_mission("MIS-20260726-001")
    state.set_mission_state("PAUSED")
    clock.advance(30)
    sampler.poll()
    assert client.samples()[-1]["mission"] == {
        "state": "PAUSED",
        "elapsed_sec": 30,
        "incident_count": 0,
    }


def test_end_mission_returns_both_slots_to_null(client, sampler, state, clock):
    state.start_mission("MIS-20260726-001")
    state.record_incident()
    sampler.poll()
    state.end_mission()
    clock.advance(TELEMETRY_INTERVAL_SEC)
    sampler.poll()

    payload = client.samples()[-1]
    assert payload["mission_id"] is None
    assert payload["mission"] is None
    assert (state.mission_elapsed_sec, state.mission_incident_count) == (None, None)


def test_mission_updates_need_an_active_mission(state):
    with pytest.raises(ValueError):
        state.set_mission_state("PAUSED")
    with pytest.raises(ValueError):
        state.record_incident()


@pytest.mark.parametrize(
    "call",
    [
        lambda s: s.start_mission(""),  # id 없는 미션은 없다
        lambda s: s.start_mission(None),
        lambda s: s.start_mission("MIS-1", state="STARTED"),  # enum 이 아닌 값
    ],
)
def test_start_mission_rejects_bad_arguments(state, call):
    with pytest.raises(ValueError):
        call(state)
    assert state.mission_id is None


def test_no_public_mission_path_can_break_telemetry(client, sampler, state, clock):
    """공개 API 로는 보관함이 반쯤 채워진 상태를 만들 수 없다.

    mission_id 만 있고 elapsed_sec·incident_count 가 비면 factory 가 표본을 거부하고
    telemetry 가 조용히 멈춘다. 미션 수명 전체를 훑어 그 구간이 없음을 확인한다.
    """
    steps = [
        lambda: state.start_mission("MIS-20260726-001", zone_id="ZONE-A"),
        state.record_incident,
        lambda: state.set_mission_state("PAUSED"),
        lambda: state.set_mission_state("RUNNING"),
        state.record_incident,
        lambda: state.set_mission_state("COMPLETED"),
        state.end_mission,
    ]
    for step in steps:
        step()
        clock.advance(TELEMETRY_INTERVAL_SEC)
        assert sampler.poll() is True
        mission = client.samples()[-1]["mission"]
        assert mission is None or set(mission) == {
            "state",
            "elapsed_sec",
            "incident_count",
        }
    assert len(client.samples()) == len(steps)


def test_a_half_filled_slot_still_costs_the_sample(client, sampler, state):
    """보관함을 쪼개 대입하면 어떻게 깨지는지 — 그래서 공개 메서드로만 바꾼다.

    공개 API 로는 이 상태를 만들 수 없어서 private 필드를 직접 건드린다.
    R-5 가 start_mission() 을 우회해 대입하는 방식으로 돌아가면 이렇게 된다.
    """
    state._mission_id = "MIS-20260726-001"
    state._mission_state = "RUNNING"
    with pytest.raises(ValueError):
        sampler.publish()  # elapsed_sec·incident_count 가 비어 있다
    assert sampler.poll() is False  # poll 은 이걸 삼킨다 — 발행이 조용히 멈춘다
    assert client.samples() == []


# --------------------------------------------------------------------------
# payload 모양 · 식별자 (계약 §6.2·§6.3, DoD 11)
# --------------------------------------------------------------------------


def test_payload_matches_the_contract_shape(client, sampler):
    sampler.poll()
    payload = client.samples()[-1]
    assert list(payload) == [
        "schema_version",
        "message_id",
        "robot_id",
        "boot_id",
        "seq",
        "sampled_at",
        "mission_id",
        "position",
        "motion",
        "battery",
        "system",
        "mission",
    ]
    assert payload["schema_version"] == "2.0"
    assert payload["robot_id"] == ROBOT_ID


def test_message_id_is_new_for_every_sample(client, sampler, clock):
    for _ in range(3):
        clock.advance(TELEMETRY_INTERVAL_SEC)
        sampler.poll()
    ids = [p["message_id"] for p in client.samples()]
    assert len(set(ids)) == len(ids)


def test_sampled_at_advances_with_every_sample(client, sampler, clock):
    for _ in range(3):
        clock.advance(TELEMETRY_INTERVAL_SEC)
        sampler.poll()
    stamps = [p["sampled_at"] for p in client.samples()]
    assert stamps == sorted(stamps) and len(set(stamps)) == len(stamps)


def test_seq_is_shared_and_increasing_across_state_and_telemetry(
    client, sampler, state, clock
):
    """seq 는 부팅 세션 하나에서 state·telemetry·event 가 함께 쓰는 순번이다."""
    publisher = StatePublisher(client, state)
    publisher.publish(force=True)
    sampler.poll()
    state.boot_completed()
    publisher.poll()
    clock.advance(TELEMETRY_INTERVAL_SEC)
    sampler.poll()

    seqs = [payload["seq"] for _kind, payload in client.published]
    assert seqs == [1, 2, 3, 4]
    assert len({p["boot_id"] for _k, p in client.published}) == 1


# --------------------------------------------------------------------------
# 발행 정책 · 재전송 금지 (계약 §6.1, §16-3·4, DoD 2·3)
# --------------------------------------------------------------------------


class FakePaho:
    """MqttClient 가 부르는 paho API 중 이 테스트가 쓰는 것만."""

    def __init__(self):
        self.on_connect = self.on_disconnect = None
        self.on_message = self.on_publish = None
        self.published = []  # (topic, payload, qos, retain)

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
        self.published.append((topic, payload, qos, retain))

        class _Info:
            mid = 1
            rc = 0

            def wait_for_publish(self, timeout=None):
                pass

        return _Info()

    def simulate_connect(self):
        self.on_connect(self, None, {}, 0, None)

    def simulate_disconnect(self):
        self.on_disconnect(self, None, 0)

    def telemetry(self) -> list:
        return [
            json.loads(payload)
            for topic, payload, _qos, _retain in self.published
            if topic == TELEMETRY_TOPIC
        ]


@pytest.fixture
def wired(tmp_path, clock):
    """진짜 MqttClient + RobotState + TelemetrySampler 조립."""
    paho = FakePaho()
    config = MqttConfig(
        robot_id=ROBOT_ID,
        host="broker.example.com",
        outbox_path=str(tmp_path / "outbox.sqlite3"),
    )
    client = MqttClient(config, client_factory=lambda _c: paho)
    state = RobotState()
    sampler = TelemetrySampler(
        client,
        state,
        monotonic=clock.monotonic,
        now=clock.now,
        thermal_root=tmp_path / "no-thermal",
    )
    yield paho, client, state, sampler, clock
    client.outbox.close()


def test_telemetry_goes_out_at_qos_0_without_retain(wired):
    paho, _client, _state, sampler, _clock = wired
    paho.simulate_connect()
    sampler.poll()
    sent = [entry for entry in paho.published if entry[0] == TELEMETRY_TOPIC]
    assert len(sent) == 1
    _topic, _payload, qos, retain = sent[0]
    assert (qos, retain) == (0, False)


def test_telemetry_never_enters_the_outbox(wired):
    paho, client, _state, sampler, clock = wired
    paho.simulate_connect()
    for _ in range(3):
        clock.advance(TELEMETRY_INTERVAL_SEC)
        sampler.poll()
    assert len(paho.telemetry()) == 3
    # Outbox 는 유실되면 안 되는 event 4종 전용이다. telemetry 는 QoS 0 유실 허용.
    assert client.outbox.pending_count() == 0


def test_samples_taken_while_offline_are_not_resent_on_reconnect(wired):
    paho, client, _state, sampler, clock = wired
    paho.simulate_connect()
    sampler.poll()
    paho.simulate_disconnect()
    for _ in range(3):
        clock.advance(TELEMETRY_INTERVAL_SEC)
        sampler.poll()

    before = len(paho.telemetry())
    paho.simulate_connect()
    assert client.flush_outbox() == 0
    # 재접속·재전송 어느 쪽도 과거 표본을 다시 만들어내지 않는다 (§6.1, §16-4).
    assert len(paho.telemetry()) == before
    assert client.outbox.pending_count() == 0


def test_reconnect_does_not_publish_telemetry_by_itself(wired):
    paho, _client, _state, _sampler, _clock = wired
    paho.simulate_connect()
    paho.simulate_disconnect()
    paho.simulate_connect()
    # state·presence 는 재접속 훅이 다시 보내지만 telemetry 는 주기로만 나간다.
    assert paho.telemetry() == []


# --------------------------------------------------------------------------
# main 조립 (main.py 는 판정하지 않는다)
# --------------------------------------------------------------------------


def test_main_wires_every_telemetry_source():
    import main
    from drive import motor
    from sensors import battery, gps

    assert main.sensor_reads() == {
        "gps": gps.read_position,
        "battery": battery.read,
        "motor": motor.is_driving,
    }


def test_hardware_read_stubs_report_no_data():
    """하드웨어가 붙기 전이다 — 가짜 좌표·가짜 잔량을 만들지 않는다."""
    from drive import motor
    from sensors import battery, gps

    assert gps.read_position() == {
        "valid": False,
        "lat": None,
        "lon": None,
        "heading_deg": None,
    }
    assert battery.read() == {"valid": False, "percent": None}
    assert motor.is_driving() is False


def test_stub_wiring_produces_a_publishable_sample(client, state, clock, tmp_path):
    """스텁만 물린 상태에서도 계약을 통과하는 표본이 나온다."""
    import main

    wired_sampler = TelemetrySampler(
        client,
        state,
        monotonic=clock.monotonic,
        now=clock.now,
        thermal_root=tmp_path / "no-thermal",
        **main.sensor_reads(),
    )
    assert wired_sampler.poll() is True
    payload = client.samples()[-1]
    assert payload["position"] == {
        "valid": False,
        "lat": None,
        "lon": None,
        "heading_deg": None,
    }
    assert payload["motion"] == {"moving": False, "speed_mps": None}
    assert payload["battery"]["valid"] is False
    assert payload["system"]["jetson_temp_c"] is None
    assert payload["mission"] is None
