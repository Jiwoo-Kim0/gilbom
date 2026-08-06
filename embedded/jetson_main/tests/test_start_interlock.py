"""출발 인터록 — 원격 신호만으로는 못 달리고, 로봇의 터치가 있어야 출발한다.

숫자(유효창)는 config.yaml 의 hardware.touch 절에서 읽는다. 이 파일이 지키는 것은
하나다: **웹·서버가 보낸 출발 신호가 터치 없이 바퀴를 돌리지 못한다.**
"""

import queue

import pytest

from comms import MessageFactory, TopicKind
from comms.contract import SCHEMA_VERSION
from communication.commands import DriveCommandRunner
from communication.events import EventPublisher
from communication.robot_state import RobotState
from driving.start_interlock import StartInterlock
from driving.states import DriveState


class Clock:
    def __init__(self, start: float = 0.0) -> None:
        self.now = float(start)

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@pytest.fixture(scope="session")
def touch_config(config) -> dict:
    return config["hardware"]["touch"]


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
def interlock(touch_config, clock) -> StartInterlock:
    """**켠 채로** 만든 인터록 — 게이트 자체의 규칙을 보는 픽스처다.

    출하 구성(config.yaml)은 이 게이트를 끄고 다닌다(아래
    test_shipped_config_leaves_the_gate_open). 그렇다고 규칙을 안 지켜도 되는 것은
    아니다: 켜는 순간 논리곱이 성립해야 하고, 그 확인은 여기서만 할 수 있다.
    """
    return StartInterlock.from_config(dict(touch_config, required_for_start=True),
                                      monotonic=clock)


# -- 게이트 그 자체 ---------------------------------------------------------


def test_shipped_config_leaves_the_gate_open(touch_config, clock):
    """출하 구성에서 터치는 **확인이 아니라 시작 버튼**이다 (0805 운용 결정).

    터치 한 번 = 웹 '시작' 한 번이고 그 반대도 성립한다 — 논리곱을 요구하면 현장에서
    화면과 로봇을 오가야 해서 시연이 끊긴다. 출발 직전 정지 자이로 영점(2초)은
    어느 경로로 출발하든 그대로 지나므로(main.StartupZeroing) '아무 신호나 즉시
    바퀴가 돈다'가 되지는 않는다.
    """
    interlock = StartInterlock.from_config(touch_config, monotonic=clock)

    assert interlock.required is False
    assert interlock.arm(source="웹 시작 버튼") is True


def test_config_turns_it_on(interlock):
    """config 가 요구하면 켜져 있어야 한다 — 켰다고 적어 두고 안 걸리면 최악이다."""
    assert interlock.required is True


def test_remote_signal_alone_does_not_start(interlock):
    assert interlock.arm(source="웹 시작 버튼") is False
    # 신호를 아무리 다시 보내도 터치가 없으면 그대로다.
    assert interlock.arm(source="웹 시작 버튼") is False
    assert interlock.poll() is False
    assert interlock.status()["waiting"] is True


def test_touch_after_the_signal_releases_it_once(interlock):
    interlock.arm(source="서버 START_MISSION")

    interlock.touched()

    assert interlock.poll() is True
    # 한 번뿐이다 — 소비한 신호로 다음 tick 에 또 출발하지 않는다.
    assert interlock.poll() is False


def test_touch_before_the_signal_also_works(interlock):
    """현장에서는 터치하고 화면으로 뛰어가는 순서도 나온다."""
    interlock.touched()

    assert interlock.arm(source="웹 시작 버튼") is True


def test_stale_touch_does_not_release(interlock, touch_config, clock):
    interlock.touched()
    clock.advance(float(touch_config["touch_valid_sec"]) + 1.0)

    assert interlock.arm(source="웹 시작 버튼") is False
    # 상한 터치는 버려진다 — 지금 신호를 보냈다고 살아나지 않는다.
    assert interlock.poll() is False


def test_stale_signal_does_not_wait_forever(interlock, touch_config, clock):
    """아침에 누른 시작이 저녁의 터치로 살아나면 안 된다."""
    interlock.arm(source="웹 시작 버튼")
    clock.advance(float(touch_config["arm_timeout_sec"]) + 1.0)

    interlock.touched()

    assert interlock.poll() is False
    assert interlock.status()["waiting"] is False


def test_disarm_drops_the_pending_signal(interlock):
    interlock.arm(source="서버 START_MISSION")
    interlock.disarm()

    interlock.touched()

    assert interlock.poll() is False


def test_disabled_interlock_passes_everything_through(touch_config, clock):
    """센서가 없으면 통과 + 경고 — 단선 하나로 출발 자체가 막히지 않게."""
    interlock = StartInterlock.from_config(
        dict(touch_config, required_for_start=True), monotonic=clock)
    interlock.disable("터치 센서 없음")

    assert interlock.required is False
    assert interlock.arm(source="웹 시작 버튼") is True
    assert interlock.status() == {"required": False, "waiting": False}


def test_no_pin_means_no_interlock(touch_config, clock):
    """핀을 안 적은 구성은 확인할 장치가 없다 — 요구를 켜 둔 채 통과시키지 않는다."""
    interlock = StartInterlock.from_config(
        dict(touch_config, pin=None, required_for_start=True), monotonic=clock)

    assert interlock.required is False
    assert interlock.arm(source="웹 시작 버튼") is True


# -- 서버 명령 경로 (계약 §12) ----------------------------------------------


class RecordingClient:
    def __init__(self) -> None:
        self.commands = queue.Queue()
        self.messages = MessageFactory("RBT-TEST")
        self.published = []

    def publish(self, kind, payload, **_kwargs):
        self.published.append((TopicKind(kind), payload))
        return True

    def results(self):
        return [payload for kind, payload in self.published
                if kind is TopicKind.COMMAND_RESULT]


class FakeMachine:
    """FSM 대역 — request_start 가 실제로 불렸는지만 본다."""

    def __init__(self) -> None:
        self.state = DriveState.IDLE
        self.starts = 0

    def request_start(self) -> None:
        self.starts += 1
        self.state = DriveState.CRUISE

    def request_pause(self):
        self.state = DriveState.PAUSED

    def request_resume(self):
        self.state = DriveState.CRUISE

    def request_cancel(self):
        self.state = DriveState.IDLE

    def request_estop(self):
        self.state = DriveState.ESTOP

    def request_estop_release(self):
        self.state = DriveState.IDLE


def start_mission(command_id: str = "CMD-1") -> dict:
    return {
        "schema_version": SCHEMA_VERSION,
        "command_id": command_id,
        "command_type": "START_MISSION",
        "parameters": {"mission_id": "M-1", "zone_id": "A-01"},
    }


@pytest.fixture
def remote(interlock):
    client = RecordingClient()
    state = RobotState()
    state.boot_completed()
    events = EventPublisher(client, state, sensor_fault_stops_driving=False)
    runner = DriveCommandRunner(client, state, events, start_interlock=interlock)
    return client, runner, FakeMachine(), interlock


def test_start_mission_waits_for_the_touch(remote):
    """관제가 누른 출발은 접수만 된다 — 바퀴는 사람이 로봇 앞에 서야 돈다."""
    client, runner, machine, interlock = remote
    client.commands.put(start_mission())

    runner.poll_before_step(machine)

    assert machine.starts == 0
    assert machine.state is DriveState.IDLE
    # 접수는 했으므로 ACCEPTED 는 나간다 (계약 §12 — 받았다는 사실은 알려야 한다).
    assert [r["status"] for r in client.results()] == ["ACCEPTED"]

    interlock.touched()          # 사람이 로봇을 만졌다
    assert interlock.poll() is True
    machine.request_start()      # 메인 루프가 하는 일

    runner.poll_after_step(machine)
    assert [r["status"] for r in client.results()] == ["ACCEPTED", "COMPLETED"]


def test_expired_start_does_not_arm_a_later_touch(remote, touch_config, clock):
    """만료된 명령이 남긴 무장으로 한참 뒤의 터치가 출발을 만들면 안 된다."""
    client, runner, machine, interlock = remote
    client.commands.put(dict(start_mission(), expires_at="2000-01-01T00:00:00.000Z"))

    runner.poll_before_step(machine)
    runner.poll_after_step(machine)     # TTL 이 지났다 → EXPIRED

    assert [r["status"] for r in client.results()][-1] == "EXPIRED"

    interlock.touched()
    assert interlock.poll() is False
    assert machine.starts == 0


def test_emergency_stop_drops_a_pending_start(remote):
    """비상 정지 뒤에 누가 로봇을 만졌다고 그 출발이 살아나면 안 된다."""
    client, runner, machine, interlock = remote
    client.commands.put(start_mission())
    runner.poll_before_step(machine)

    client.commands.put({
        "schema_version": SCHEMA_VERSION,
        "command_id": "CMD-2",
        "command_type": "EMERGENCY_STOP",
        "parameters": {"reason": "관리자 정지"},
    })
    runner.poll_before_step(machine)

    interlock.touched()
    assert interlock.poll() is False
    assert machine.state is DriveState.ESTOP


def test_resume_from_a_moving_stop_needs_no_touch(remote):
    """달리다 선 PAUSED 에서의 재개는 그대로 — 그때 로봇은 이미 멀리 있다."""
    client, runner, machine, interlock = remote
    machine.state = DriveState.PAUSED
    runner._state.start_mission("M-1", zone_id="A-01")
    client.commands.put({
        "schema_version": SCHEMA_VERSION,
        "command_id": "CMD-3",
        "command_type": "RESUME_MISSION",
        "parameters": {},
    })

    runner.poll_before_step(machine)

    assert machine.state is DriveState.CRUISE


class TestStartupZeroing:
    """출발 신호(무엇이든) → **정지 자이로 영점 2초** → 출발.

    터치만 이 대기를 지나던 종전 배선에서는 웹 버튼으로 출발한 주행이 창고에서 잰
    바이어스로 돌았다 — 같은 로봇이 출발 경로에 따라 다른 yaw 를 읽었다는 뜻이다.
    지금은 begin() 하나가 모든 경로의 입구다 (터치·웹·서버·조작자 A·자동 출발).

    바이어스는 기준점이 아니라 센서 오차이고 온도로 변한다 — 실측(2026-08-05) 이 칩은
    정지 상태에서 (-2.8, +2.1, -0.2) dps 를 읽었다. 보정 없이 30초를 돌면 yaw 적분이
    80° 넘게 흐르고, 회차·회전 완료 판정이 그 위에서 돈다.
    **pitch 기준면은 여기서 건드리지 않는다** (docs/IMU-ZERO-REFERENCE.md §5).
    """

    class FakeImu:
        def __init__(self, *, available=True, still=True):
            self.available = available
            self.still = still
            self.begun = 0
            self.finished = 0

        def begin_zeroing(self):
            self.begun += 1
            return self.available

        def finish_zeroing(self):
            self.finished += 1
            return self.still

    def build(self, imu, zero_sec=2.0):
        from main import StartupZeroing

        confirmed = []
        return StartupZeroing(imu, zero_sec, lambda: confirmed.append(True)), confirmed

    def test_start_does_not_move_the_wheels_immediately(self):
        imu = self.FakeImu()
        zeroing, confirmed = self.build(imu)
        zeroing.begin(1.0, "웹 시작 버튼")
        zeroing.poll(1.0)
        assert confirmed == [] and zeroing.busy      # 아직 표본을 모으는 중이다

    def test_start_is_confirmed_after_the_still_window(self):
        imu = self.FakeImu()
        zeroing, confirmed = self.build(imu, zero_sec=2.0)
        zeroing.begin(1.0, "터치")
        zeroing.poll(2.5)
        assert confirmed == []                       # 아직 2초가 안 찼다
        zeroing.poll(3.01)
        assert confirmed == [True] and not zeroing.busy
        assert imu.finished == 1

    def test_movement_during_zeroing_blocks_the_start(self):
        """흔들린 표본으로 바이어스를 박느니 출발을 미루는 편이 낫다."""
        imu = self.FakeImu(still=False)
        zeroing, confirmed = self.build(imu)
        zeroing.begin(1.0, "터치")
        zeroing.poll(3.5)
        assert confirmed == []                       # 출발 확인이 나가지 않는다
        assert not zeroing.busy                      # 다시 눌러야 한다

    def test_missing_imu_never_blocks_the_start(self):
        """IMU 없는 구성에서 출발이 막히면 안 된다 — 영점을 건너뛴다."""
        imu = self.FakeImu(available=False)
        zeroing, confirmed = self.build(imu)
        zeroing.begin(1.0, "터치")
        assert confirmed == [True] and not zeroing.busy

    def test_repeated_signal_does_not_restart_the_window(self):
        """출발 신호는 여러 경로가 매 틱 다시 보낸다 — 그때마다 리셋되면 안 찬다."""
        imu = self.FakeImu()
        zeroing, confirmed = self.build(imu, zero_sec=2.0)
        zeroing.begin(1.0, "터치")
        zeroing.begin(2.5, "웹 시작 버튼")            # 기다리는 동안 또 눌렀다
        assert imu.begun == 1
        zeroing.poll(3.01)
        assert confirmed == [True]                   # 첫 신호 기준으로 끝난다

    def test_second_signal_at_the_same_standstill_skips_the_wait(self):
        """서 있는 동안 바이어스는 변하지 않는다 — 재요청마다 2초를 다시 받지 않는다.

        FSM 이 출발을 못 받는 경우(인도 미확인)에 재요청이 매 틱 오는데, 그때마다
        2초를 새로 세면 영영 못 나간다.
        """
        imu = self.FakeImu()
        zeroing, confirmed = self.build(imu, zero_sec=2.0)
        zeroing.begin(1.0, "자동 출발")
        zeroing.poll(3.01)
        assert confirmed == [True] and imu.begun == 1

        zeroing.begin(3.02, "자동 출발")
        assert confirmed == [True, True]             # 즉시 재요청
        assert imu.begun == 1                        # 다시 재지는 않는다

    def test_moving_rearms_the_measurement(self):
        """실제로 움직이고 나면 다음 출발에는 다시 잰다 — 바이어스는 온도로 변한다."""
        imu = self.FakeImu()
        zeroing, confirmed = self.build(imu, zero_sec=2.0)
        zeroing.begin(1.0, "터치")
        zeroing.poll(3.01)

        zeroing.rearm()                              # 주행했다 (main 루프가 부른다)
        zeroing.begin(10.0, "터치")
        assert confirmed == [True] and zeroing.busy  # 즉시 출발이 아니다
        zeroing.poll(12.01)
        assert confirmed == [True, True] and imu.begun == 2
