"""FSM 전이 테스트 — 장애물 재확인, 경계 소실·인지 유예, ESTOP 래치, 조향 상한·슬루율,
브레이크 의미론, 점자블록 발견→접근→INSPECT→쿨다운, IMU 게이트.

실행: 저장소 루트에서  python3 -m pytest tests/
숫자(대기 시간·임계값)는 전부 config.yaml에서 읽어 온다 — 테스트에도 하드코딩 금지.
config 픽스처는 tests/conftest.py에 있다.
"""

from dataclasses import replace

import pytest

from driving.state_machine import StateMachineParams, StateMachine
from driving.states import DeadEndPhase, DriveState
from hardware.jetson.imu import JetsonImu
from hardware.mocks import MockImu
from perception.interfaces import ObstacleRegion, SegmentationFrame


def clear_frame(t: float) -> SegmentationFrame:
    return SegmentationFrame(timestamp=t, sidewalk_detected=True)


def obstacle_frame(t: float) -> SegmentationFrame:
    blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)
    return SegmentationFrame(timestamp=t, sidewalk_detected=True, obstacles=(blocking,))


def boundary_lost_frame(t: float) -> SegmentationFrame:
    return SegmentationFrame(timestamp=t, sidewalk_detected=False)


def centerline_frame(t: float, offset: float) -> SegmentationFrame:
    return SegmentationFrame(timestamp=t, sidewalk_detected=True,
                             sidewalk_center_offset_ratio=offset)


def biased_obstacle_frame(t: float, bias: float,
                          cost_profile=None) -> SegmentationFrame:
    blocking = ObstacleRegion(lateral_bias=bias, proximity=0.9, blocking=True)
    return SegmentationFrame(timestamp=t, sidewalk_detected=True,
                             obstacles=(blocking,), cost_profile=cost_profile)


def all_blocked_frame(t: float) -> SegmentationFrame:
    """전 방향 통행 불가 — 코스트맵 전 슬롯 ∞ (막다른 길 상황)."""
    return SegmentationFrame(timestamp=t, sidewalk_detected=True,
                             cost_profile=(float("inf"),) * 5)


def roadway_risk_frame(t: float, cost_profile=None) -> SegmentationFrame:
    """차도가 코리도에 들어옴 → 선회 이탈(ESCAPE). 정지가 아니다."""
    return SegmentationFrame(timestamp=t, sidewalk_detected=True,
                             roadway_encroachment_risk=True,
                             cost_profile=cost_profile)


def roadway_critical_frame(t: float) -> SegmentationFrame:
    """코리도가 차도로 덮임 → 즉시 ESTOP. 선회로 되돌릴 단계를 지났다는 판정이다."""
    return SegmentationFrame(timestamp=t, sidewalk_detected=True,
                             roadway_encroachment_risk=True, roadway_critical=True)


def braille_frame(t: float, offset: float, proximity: float) -> SegmentationFrame:
    return SegmentationFrame(timestamp=t, sidewalk_detected=True,
                             braille_detected=True,
                             braille_center_offset_ratio=offset,
                             braille_proximity=proximity)


def settle_to_cruise(machine, frame_fn, t, imu=None, tick=0.05, limit=60):
    """탈출 후 STRAIGHTEN(바퀴 정렬)을 지나 CRUISE에 닿을 때까지 밟는다.

    재정향은 이제 곧장 CRUISE로 가지 않는다 — 조향이 중앙으로 돌아온 뒤에 추종을
    시작한다(꺾인 채 전진하면 방금 피한 쪽으로 다시 들어간다).
    """
    for _ in range(limit):
        if machine.state is DriveState.CRUISE:
            return t
        t += tick
        machine.step(frame_fn(t), None if imu is None else imu.read(), t)
    raise AssertionError(f"CRUISE에 닿지 못했다 (상태={machine.state}, "
                         f"국면={machine.context.dead_end_phase})")


def started_machine(params: StateMachineParams, t: float = 0.0) -> StateMachine:
    """IDLE에서 출발해 CRUISE에 올려놓은 상태머신."""
    machine = StateMachine(params=params, now=t)
    machine.request_start()
    machine.step(clear_frame(t), None, t)
    assert machine.state is DriveState.CRUISE
    return machine


class TestObstacleFlow:
    """장애물 등장 → 즉시 좌/우 회피 선회. 정지 대기는 '비껴갈 방향이 없을 때'만이다.

    회피 기동 자체는 TestAvoidBypass가 다룬다. 이 클래스는 진입 시점의 계약
    (정지하지 않는다 / IMU를 요구하지 않는다)과 정지 폴백의 조건만 본다.
    """

    def test_obstacle_without_imu_still_evades(self, params):
        """IMU가 없어도 회피한다 — 0803 실외 '정지만 함'의 직접 원인이었던 회귀.

        종전에는 회피 종료 판정이 전부 yaw 기반이라 IMU 게이트 뒤였고, IMU 배선이
        해제된 상태에서 **모든** 장애물이 정지 대기로 떨어졌다. 지금은 종료 판정이
        시각(장애물 소멸·중심 재정렬) + 시간이라 게이트가 없다.
        """
        machine = started_machine(params)
        command = machine.step(obstacle_frame(1.0), None, 1.0)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)
        assert command.steering_offset_deg != 0.0

    def test_obstacle_with_imu_never_stops(self, params):
        # 요구사항: blocking을 보면 정지하지 않고 즉시 좌/우로 비껴간다
        machine = started_machine(params)
        command = machine.step(obstacle_frame(1.0), MockImu().read(), 1.0)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)
        assert command.steering_offset_deg != 0.0

    def test_slope_beats_obstacle_so_evasion_never_starts_on_a_grade(self, params):
        # 경사에서는 회피가 아니라 브레이크 정지가 맞다 — 경사 판정이 장애물보다
        # 앞이어야 저속 횡방향 회피가 경사에서 시작되지 않는다
        imu = MockImu(pitch_deg=params.slope_enter_pitch_deg + 1.0)
        machine = started_machine(params)
        command = machine.step(obstacle_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE
        assert command.throttle_pct == 0 and command.brake

        command = machine.step(obstacle_frame(1.05), imu.read(), 1.05)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == 0 and command.brake   # 경사발 → 브레이크 유지

    def test_no_direction_holds_then_resumes_when_cleared(self, params):
        # 정지 대기(HOLD)의 유일한 시각적 조건: 전 방향 통행 불가 = 비껴갈 방향이 없다
        machine = started_machine(params)
        machine.step(all_blocked_frame(1.0), None, 1.0)
        assert machine.state is DriveState.AVOID

        before = 1.0 + params.avoid_recheck_sec / 2
        command = machine.step(all_blocked_frame(before), None, before)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == 0

        recheck = 1.0 + params.avoid_recheck_sec
        machine.step(clear_frame(recheck), None, recheck)
        assert machine.state is DriveState.CRUISE

    def test_no_direction_persists_escalates_to_reorient(self, params):
        # 막힘이 유지되면 재정향으로 승격된다 — **IMU가 없어도** 간다(0804 개편).
        # 완료 판정이 시각이라 yaw가 필요 없고, 종전의 IMU 게이트는 센서가 빠졌을 때
        # 막다른 길에서 아무것도 못 하게 만들었다.
        machine = started_machine(params)
        machine.step(all_blocked_frame(1.0), None, 1.0)
        after = 1.0 + params.avoid_recheck_sec * 2
        command = machine.step(all_blocked_frame(after), None, after)
        assert machine.state is DriveState.DEAD_END
        assert command.throttle_pct == pytest.approx(-params.dead_end_throttle_pct)


class TestBoundaryLost:
    """인도 경계 소실 → 유예창 동안 복구 주행 → (초과 시) 정지 + 수동 폴백 대기."""

    def test_boundary_lost_recovers_then_waits(self, params):
        machine = started_machine(params)
        command = machine.step(boundary_lost_frame(1.0), None, 1.0)
        # 유예 중에는 **정지하지 않는다** — 명세 '인도를 벗어날 것 같을 때 좌우로 회피'.
        # 소실 시점의 조향을 유지하며 저속 전진해 되돌아올 기회를 만든다
        assert command.throttle_pct == pytest.approx(params.recovery_throttle_pct)

        expired = 1.0 + params.perception_grace_sec * 2
        command = machine.step(boundary_lost_frame(expired), None, expired)
        assert machine.state is DriveState.IDLE
        assert command.throttle_pct == 0

        # 경계가 돌아와도 수동 재시작 요청 없이는 출발하지 않는다
        machine.step(clear_frame(2.0), None, 2.0)
        assert machine.state is DriveState.IDLE

        # 수동 재시작 요청이 있어야 CRUISE 복귀
        machine.request_start()
        machine.step(clear_frame(3.0), None, 3.0)
        assert machine.state is DriveState.CRUISE

    def test_perception_dropout_also_stops(self, params):
        machine = started_machine(params)
        command = machine.step(None, None, 1.0)
        assert command.throttle_pct == 0

        expired = 1.0 + params.perception_grace_sec * 2
        command = machine.step(None, None, expired)
        assert machine.state is DriveState.IDLE
        assert command.throttle_pct == 0


class TestPerceptionDebounce:
    """세그 깜빡임 유예 — 한 프레임 오탐이 '수동 재시작 필요'가 되지 않는다."""

    def test_flicker_keeps_state_and_recovers(self, params):
        machine = started_machine(params)
        command = machine.step(boundary_lost_frame(0.1), None, 0.1)
        assert machine.state is DriveState.CRUISE     # 상태는 유지하고
        assert command.throttle_pct == pytest.approx(params.recovery_throttle_pct)
        assert not command.brake

    def test_perception_dropout_brakes_instead_of_recovering(self, params):
        # frame 자체가 없으면(카메라 장애) 복구 주행은 진짜 눈먼 주행이다 → 브레이크.
        # 경계 소실(프레임은 있고 인도만 안 보인다)과 구분하는 것이 이 테스트의 요점
        machine = started_machine(params)
        command = machine.step(None, None, 0.1)
        assert machine.state is DriveState.CRUISE
        assert command.throttle_pct == 0 and command.brake

    def test_recovery_reverses_when_something_blocks_the_path(self, params):
        # 0804 개편: 앞이 막힌 채로 인도가 안 보이면 **후진 복구**다.
        # 벽에 코가 닿으면 화각을 벽이 채워 인도가 안 보인다 — 길을 잃은 것이 아니라
        # 너무 가까이 붙은 것이므로 대응이 반대여야 한다(뒤로 빼면 보인다).
        # 종전에는 브레이크였고, 그래서 벽 앞에서 1.5초 정지 후 IDLE이 전부였다.
        machine = started_machine(params)
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)
        frame = SegmentationFrame(timestamp=0.1, sidewalk_detected=False,
                                  obstacles=(blocking,))
        machine.step(frame, None, 0.1)                # 디바운스 시작
        armed = 0.1 + params.recovery_reverse_debounce_sec + 0.05
        command = machine.step(replace(frame, timestamp=armed), None, armed)
        assert machine.state is DriveState.CRUISE     # 아직 유예 중
        assert command.throttle_pct == pytest.approx(-params.recovery_reverse_throttle_pct)
        # 조향은 중립이다 — 후방은 카메라 사각이라 곡선 후진은 눈먼 횡방향 기동이다
        assert command.steering_offset_deg == pytest.approx(0.0)
        assert not command.brake

    def test_single_bad_frame_does_not_trigger_reverse(self, params):
        """깜빡임 한 프레임으로 후진을 걸지 않는다 (0804 실기).

        세그 마스크는 프레임 단위로 흔들린다. 후진은 되돌리기 어려운 기동이라
        진입에 디바운스가 없으면 깜빡임 하나가 곧바로 1.2초 강제 후진이 되고,
        인계 규칙에 따라 재정향·U턴까지 간다 — 코스트맵이 거의 전부 0인
        (완전히 트인) 장면에서도 후진이 걸렸다.
        """
        machine = started_machine(params)
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)
        flicker = SegmentationFrame(timestamp=0.1, sidewalk_detected=False,
                                    obstacles=(blocking,))
        command = machine.step(flicker, None, 0.1)
        assert command.throttle_pct >= 0            # 아직 후진하지 않는다

        # 다음 틱에 인지가 돌아오면 후진 없이 그대로 간다
        good = SegmentationFrame(timestamp=0.2, sidewalk_detected=True)
        command = machine.step(good, None, 0.2)
        assert command.throttle_pct >= 0

    def test_reverse_commits_to_minimum_even_if_floor_reappears(self, params):
        """후진은 최소 시간을 채운다 — 0804 실기 '후진 찔끔하고 벽에 계속 쳐박음'.

        벽에서 한 틱 물러나면 바닥이 곧 보이는데, 그 순간 가드가 풀려 CRUISE가 다시
        전진해 벽에 박았다. 상한만 있고 하한이 없어 실제 후진이 한두 틱이었다.
        """
        machine = started_machine(params)
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)
        lost = SegmentationFrame(timestamp=0.1, sidewalk_detected=False,
                                 obstacles=(blocking,))
        machine.step(lost, None, 0.1)                     # 디바운스 시작
        armed = 0.1 + params.recovery_reverse_debounce_sec + 0.05
        machine.step(replace(lost, timestamp=armed), None, armed)   # 후진 시작

        # 바닥이 다시 보여도 최소 시간 전에는 계속 뺀다.
        # (종료 조건은 '경로 인지'다 — 인도가 보이고 정면을 막는 것이 없어야 한다.
        #  그래서 이 프레임에는 blocking을 넣지 않는다)
        seen = SegmentationFrame(timestamp=0.2, sidewalk_detected=True)
        command = machine.step(replace(seen, timestamp=armed + 0.05), None, armed + 0.05)
        assert command.throttle_pct == pytest.approx(-params.recovery_reverse_throttle_pct)

        mid = armed + params.recovery_reverse_min_sec - 0.05
        command = machine.step(replace(seen, timestamp=mid), None, mid)
        assert command.throttle_pct == pytest.approx(-params.recovery_reverse_throttle_pct)

        # 최소 시간을 채우고 인도도 보이면 후진을 끝낸다
        done = armed + params.recovery_reverse_min_sec + 0.05
        command = machine.step(replace(seen, timestamp=done), None, done)
        assert command.throttle_pct >= 0

    def test_reverse_minimum_never_exceeds_the_cap(self, params):
        # 최소·최대를 같은 기준점에서 재야 한다 — 틱마다 갱신되는 래치로 만들면
        # 재무장되며 상한이 무의미해진다
        assert params.recovery_reverse_min_sec < params.recovery_reverse_sec

    def test_reverse_recovery_has_its_own_longer_window(self, params):
        # 후진 복구는 전진 복구보다 긴 창을 쓴다 — 벽에서 빠져나오는 데 필요한 거리가
        # '중심으로 되돌아오는' 것보다 크고, 이 창이 짧으면 재정향 승격(AVOID 재확인
        # 3초)에 도달하기 전에 IDLE로 떨어진다
        assert params.recovery_reverse_sec > params.perception_grace_sec
        machine = started_machine(params)
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)

        def frame(t):
            return SegmentationFrame(timestamp=t, sidewalk_detected=False,
                                     obstacles=(blocking,))

        machine.step(frame(0.1), None, 0.1)
        armed = 0.1 + params.recovery_reverse_debounce_sec + 0.05
        machine.step(frame(armed), None, armed)          # 후진 시작
        # 전진 복구였다면 이미 IDLE로 떨어졌을 시점에도 아직 후진 중이다
        mid = armed + params.perception_grace_sec + 0.05
        command = machine.step(frame(mid), None, mid)
        assert machine.state is DriveState.CRUISE
        assert command.throttle_pct == pytest.approx(-params.recovery_reverse_throttle_pct)

        # 상한을 넘겨도 **갈 방향이 남아 있으면** 사람을 부르지 않고 꺾어서 시도한다.
        # 이 프레임은 all_directions_blocked=False(기본값)라 그 경로로 간다.
        expired = armed + params.recovery_reverse_sec + 0.05
        command = machine.step(frame(expired), None, expired)
        assert machine.state is DriveState.DEAD_END
        assert command.throttle_pct > 0 and command.steering_offset_deg != 0

    def test_reverse_cap_waits_manually_only_when_fully_blocked(self, params):
        # 정말 전 방향이 막혔을 때만 수동 대기로 간다 — 좁은 실내에서 벽·가구는
        # 미분류라 1m를 물러나도 blocking으로 남는다. 그것만으로 미션을 끝내면 안 된다.
        machine = started_machine(params)
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)

        def walled(t):
            return SegmentationFrame(timestamp=t, sidewalk_detected=False,
                                     obstacles=(blocking,),
                                     all_directions_blocked=True)

        machine.step(walled(0.1), None, 0.1)
        armed = 0.1 + params.recovery_reverse_debounce_sec + 0.05
        machine.step(walled(armed), None, armed)
        expired = armed + params.recovery_reverse_sec + 0.05
        command = machine.step(walled(expired), None, expired)
        assert machine.state is DriveState.IDLE
        assert command.brake

    def test_recovery_holds_last_steering(self, params):
        # 복구 조향은 '소실 직전에 내던 조향'이다 — 인도를 벗어나는 중이라면 그 조향이
        # 이미 되돌리는 방향이므로, 유지하는 것이 가진 정보 중 최선이다
        machine = started_machine(params)
        t = 0.0
        for _ in range(6):        # 왼쪽으로 조향이 붙은 상태를 만든다
            t += 0.05
            command = machine.step(centerline_frame(t, -0.6), None, t)
        steering = command.steering_offset_deg
        assert steering < 0

        t += 0.05
        command = machine.step(boundary_lost_frame(t), None, t)
        assert command.steering_offset_deg == pytest.approx(steering)

    def test_recovery_within_grace_resumes_driving(self, params):
        machine = started_machine(params)
        machine.step(boundary_lost_frame(0.1), None, 0.1)
        command = machine.step(clear_frame(0.2), None, 0.2)
        assert machine.state is DriveState.CRUISE
        assert command.throttle_pct > 0

    def test_grace_timer_resets_on_good_frame(self, params):
        # 소실 → 복귀 → 소실. 타이머를 리셋하지 않으면 두 번째 소실 틱에서 누적
        # 시간이 유예를 넘겨 잘못 IDLE로 떨어진다
        grace = params.perception_grace_sec
        machine = started_machine(params)
        machine.step(boundary_lost_frame(0.1), None, 0.1)
        recovered = 0.1 + grace * 0.8
        machine.step(clear_frame(recovered), None, recovered)
        assert machine.state is DriveState.CRUISE

        again = 0.1 + grace * 1.2   # 첫 소실 기준이면 유예 초과, 리셋됐다면 아니다
        machine.step(boundary_lost_frame(again), None, again)
        assert machine.state is DriveState.CRUISE


class TestSteerLimits:
    """CRUISE 비례 조향 상한 — 하드웨어 조향이 비대칭이라 좌우를 따로 든다."""

    TICK = 0.02

    def saturated(self, params, offset_ratio: float) -> float:
        """한쪽으로 최대 조향을 요구했을 때 실제로 나가는 값(슬루율 통과 후).

        **TURN 확정 전** 구간에서 읽는다: 조향 수요가 포화된 채 turn.confirm_sec을
        넘기면 그것은 설계상 코너이고 상태가 TURN으로 승격된다(그쪽 상한은
        turn_steer_*_deg다). 여기서 보려는 것은 그 전 구간의 CRUISE 클램프다.
        슬루율 100°/s면 상한(≤15°)까지 0.15초라 확정창(0.35초) 안에 충분히 도달한다.
        """
        machine = started_machine(params)
        t = 0.0
        while t + self.TICK < params.turn_confirm_sec:
            t += self.TICK
            command = machine.step(centerline_frame(t, offset_ratio), None, t)
        assert machine.state is DriveState.CRUISE
        return command.steering_offset_deg

    def test_slew_reaches_clamp_inside_turn_confirm_window(self, params):
        # 위 saturated()의 전제 — 이 부등식이 깨지면 클램프에 닿기 전에 TURN으로 간다
        reach_sec = max(params.cruise_steer_left_max_deg,
                        params.cruise_steer_right_max_deg) / params.steer_rate_deg_per_s
        assert reach_sec < params.turn_confirm_sec

    def test_left_saturates_at_left_limit(self, params):
        assert self.saturated(params, -1.0) == pytest.approx(
            -params.cruise_steer_left_max_deg)

    def test_right_saturates_at_right_limit(self, params):
        assert self.saturated(params, 1.0) == pytest.approx(
            params.cruise_steer_right_max_deg)

    def test_limits_are_asymmetric(self, params):
        # 좌우가 같아지면 위 두 테스트가 한쪽 상한을 양쪽에 쓰는 회귀를 못 잡는다
        assert params.cruise_steer_left_max_deg != params.cruise_steer_right_max_deg

    def test_limits_stay_inside_hardware(self, config, params):
        # 하드웨어 클램프는 안전망이지 제어가 아니다 — 주행 상한이 그 밖으로 나가면
        # FSM은 자기 명령이 잘린 걸 모른 채 같은 값을 계속 낸다
        servo = config["hardware"]["steering_servo"]
        assert params.cruise_steer_left_max_deg <= servo["left_offset_limit_deg"]
        assert params.cruise_steer_right_max_deg <= servo["right_offset_limit_deg"]


class TestSteerRateLimit:
    """조향 슬루율 제한 — 세그 중심 목표의 노이즈가 서보로 그대로 새지 않게 한다."""

    TICK = 0.05   # 20Hz 한 틱

    def test_single_tick_step_is_bounded(self, params):
        # 한 틱 조향 변화량은 rate × dt를 넘지 못한다 (목표는 조향 상한이었다)
        machine = started_machine(params)
        command = machine.step(centerline_frame(self.TICK, -1.0), None, self.TICK)
        assert command.steering_offset_deg == pytest.approx(
            -params.steer_rate_deg_per_s * self.TICK)

    def test_reaches_target_over_ticks(self, params):
        # 제한은 지연일 뿐 — 목표가 유지되면 몇 틱 뒤 상한까지 간다.
        # TURN 확정 전 구간에서 읽는다 (근거는 TestSteerLimits.saturated)
        machine = started_machine(params)
        t = 0.0
        while t + 0.02 < params.turn_confirm_sec:
            t += 0.02
            command = machine.step(centerline_frame(t, -1.0), None, t)
        assert machine.state is DriveState.CRUISE
        assert command.steering_offset_deg == pytest.approx(
            -params.cruise_steer_left_max_deg)

    def test_alternating_noise_stays_near_center(self, params):
        # 프레임마다 좌↔우 풀스케일로 튀어도 조향은 한 틱 변화량 안에 머문다
        machine = started_machine(params)
        t = 0.0
        for offset in (-1.0, 1.0, -1.0, 1.0):
            t += self.TICK
            command = machine.step(centerline_frame(t, offset), None, t)
            assert abs(command.steering_offset_deg) <= (
                params.steer_rate_deg_per_s * self.TICK + 1e-9)


class TestRoadwayEscape:
    """차도를 만나면 **좌 or 우로 선회해 인도로 돌아온다** (명세 하드 제약).

    회귀 방어의 핵심 클래스다. 종전에는 차도 침범 위험이 곧 ESTOP(수동 해제만)이었고,
    인도 옆이 차도인 것은 정상 상황이므로 판정 오탐 한 번에 순찰이 끝났다 —
    0803 실외에서 가장 무거운 실패였다. ESTOP은 코리도가 차도로 **덮인** 경우
    (roadway_critical)와 선회 타임아웃에만 남는다.
    """

    def test_roadway_risk_steers_away_instead_of_stopping(self, params):
        machine = started_machine(params)
        command = machine.step(roadway_risk_frame(1.0), None, 1.0)
        assert machine.state is DriveState.ESCAPE
        assert command.throttle_pct == pytest.approx(params.escape_throttle_pct)
        assert command.steering_offset_deg != 0.0
        assert not command.brake

    def test_escape_direction_is_the_open_side(self, params):
        # 코스트맵의 열린 절반 = 인도가 남아 있는 쪽. 오른쪽이 차도(∞)면 왼쪽으로 간다
        inf = float("inf")
        machine = started_machine(params)
        right_is_road = (0.0, 0.0, 0.0, 0.2, inf, inf, inf)
        machine.step(roadway_risk_frame(1.0, right_is_road), None, 1.0)
        assert machine.state is DriveState.ESCAPE
        t = 1.0
        for _ in range(20):        # 슬루율 통과 뒤 정상 상태
            t += 0.05
            command = machine.step(roadway_risk_frame(t, right_is_road), None, t)
        assert command.steering_offset_deg == pytest.approx(
            -params.escape_steer_left_deg)

    def test_escape_returns_to_cruise_after_clear_window(self, params):
        machine = started_machine(params)
        machine.step(roadway_risk_frame(1.0), None, 1.0)
        assert machine.state is DriveState.ESCAPE

        # 위험이 풀린 직후에는 아직 복귀하지 않는다 — 경계에서 켜졌다 꺼지며 왕복하는
        # 것을 막는 유지창(clear_sec)이고, 창은 **풀린 시점부터** 센다
        cleared_at = 1.5
        machine.step(clear_frame(cleared_at), None, cleared_at)
        assert machine.state is DriveState.ESCAPE

        t = cleared_at + params.escape_clear_sec + 0.05
        machine.step(clear_frame(t), None, t)
        assert machine.state is DriveState.CRUISE

    def test_clear_timer_resets_when_risk_returns(self, params):
        machine = started_machine(params)
        machine.step(roadway_risk_frame(1.0), None, 1.0)
        half = 1.0 + params.escape_clear_sec * 0.8
        machine.step(clear_frame(half), None, half)
        machine.step(roadway_risk_frame(half + 0.05), None, half + 0.05)
        assert machine.state is DriveState.ESCAPE

        # 타이머가 리셋되지 않았다면 이 틱에 잘못 복귀한다
        resumed = half + 0.10
        machine.step(clear_frame(resumed), None, resumed)
        assert machine.state is DriveState.ESCAPE

    def test_escape_does_not_restart_itself(self, params):
        # ESCAPE가 자기 자신의 소스 상태에 들어 있으면 매 틱 재진입해 entered_at이
        # 리셋되고 timeout_sec(ESTOP 안전판)이 영원히 걸리지 않는다
        machine = started_machine(params)
        machine.step(roadway_risk_frame(1.0), None, 1.0)
        entered = machine.context.entered_at
        machine.step(roadway_risk_frame(1.5), None, 1.5)
        assert machine.context.entered_at == pytest.approx(entered)

    def test_escape_timeout_falls_back_to_estop(self, params):
        # 선회로 벗어나지 못하면 '차도 절대 접근 금지'를 지킬 방법은 정지뿐이다
        machine = started_machine(params)
        machine.step(roadway_risk_frame(1.0), None, 1.0)
        expired = 1.0 + params.escape_timeout_sec
        command = machine.step(roadway_risk_frame(expired), None, expired)
        assert machine.state is DriveState.ESTOP
        assert command.brake

    def test_critical_roadway_estops_immediately(self, params):
        # 코리도가 차도로 덮였다 = 선회로는 이미 늦다 → 즉시 정지 (최후 방어선)
        machine = started_machine(params)
        command = machine.step(roadway_critical_frame(1.0), None, 1.0)
        assert machine.state is DriveState.ESTOP
        assert command.throttle_pct == 0 and command.brake

    def test_critical_beats_escape_even_during_escape(self, params):
        machine = started_machine(params)
        machine.step(roadway_risk_frame(1.0), None, 1.0)
        assert machine.state is DriveState.ESCAPE
        machine.step(roadway_critical_frame(1.1), None, 1.1)
        assert machine.state is DriveState.ESTOP

    def test_block_debounce_keeps_running_during_escape(self, params):
        """선회 중에도 블록 발견 판정은 갱신된다 — 얼어붙으면 없는 블록을 추종하러 간다.

        회귀 방어: 디바운스 갱신이 가드보다 뒤에 있으면 ESCAPE(최대 4초) 체류 동안
        판정이 멈춘다. 그러면 선회하며 지나친 블록의 `braille_stable=True`가 그대로
        남아, 복귀하는 틱에 확정 시간 없이 곧바로 TRACE로 들어간다.
        """
        machine = started_machine(params)
        t = 0.0
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 2):
            t += 0.05
            machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.TRACE
        assert machine.context.braille_stable

        # 차도 진입 → 선회. 그 사이 블록은 화면에서 사라진다(지나쳤다)
        t += 0.05
        machine.step(roadway_risk_frame(t), None, t)
        assert machine.state is DriveState.ESCAPE
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 4):
            t += 0.05
            machine.step(roadway_risk_frame(t), None, t)
        assert not machine.context.braille_stable      # 선회 중에도 소실이 확정됐다

        # 선회 종료 → 순찰로 나간다 (없는 블록을 추종하지 않는다)
        cleared = t + 0.05
        machine.step(clear_frame(cleared), None, cleared)
        after = cleared + params.escape_clear_sec + 0.05
        machine.step(clear_frame(after), None, after)
        assert machine.state is DriveState.CRUISE

    def test_perception_dropout_during_escape_brakes(self, params):
        # ESCAPE는 GUARDED_STATES가 아니다(차도를 벗어나는 중에는 인도가 안 보이는 것이
        # 정상이다). 그래도 프레임 자체가 없으면 눈먼 선회를 계속하지 않는다
        machine = started_machine(params)
        machine.step(roadway_risk_frame(1.0), None, 1.0)
        command = machine.step(None, None, 1.1)
        assert command.throttle_pct == 0 and command.brake
        assert machine.state is DriveState.ESCAPE   # 한 틱 두절로 상태를 버리지 않는다

    def test_perception_dropout_during_escape_still_times_out_to_estop(self, params):
        """인지가 끊긴 채로도 이탈 타임아웃은 계속 간다 — 안 가면 영구 브레이크다.

        회귀 방어: frame=None 폴백은 상태 핸들러보다 앞이라, 이 승격이 없으면
        카메라가 죽은 ESCAPE는 핸들러의 timeout→ESTOP에 영영 닿지 못하고 차도
        위(이거나 근처)에서 상태 전이 없이 무한 브레이크가 된다 — 그동안 서버
        투영은 PATROLLING(주행 중)이라 관제도 이상을 모른다.
        """
        machine = started_machine(params)
        machine.step(roadway_risk_frame(1.0), None, 1.0)
        # 타임아웃 전: 브레이크 유지 + 상태 보존 (눈먼 선회 금지)
        before = 1.0 + params.escape_timeout_sec - 0.1
        command = machine.step(None, None, before)
        assert machine.state is DriveState.ESCAPE
        assert command.brake
        # 타임아웃 도달: ESTOP 승격 — 수동 해제만이 출구 (핸들러 타임아웃과 같은 결말)
        expired = 1.0 + params.escape_timeout_sec
        command = machine.step(None, None, expired)
        assert machine.state is DriveState.ESTOP
        assert command.brake


class TestEstop:
    """ESTOP 래치 — 수동 해제만 허용."""

    def test_estop_latches_until_manual_release(self, params):
        machine = started_machine(params)
        machine.step(roadway_critical_frame(1.0), None, 1.0)

        # 위험이 사라져도, 시작 요청을 넣어도 ESTOP 유지 (래치)
        machine.request_start()
        machine.step(clear_frame(2.0), None, 2.0)
        assert machine.state is DriveState.ESTOP

        # 수동 해제 → IDLE (곧바로 주행 복귀가 아니라 대기로)
        machine.request_estop_release()
        machine.step(clear_frame(3.0), None, 3.0)
        assert machine.state is DriveState.IDLE

    def test_manual_estop_request(self, params):
        machine = started_machine(params)
        machine.request_estop()
        machine.step(clear_frame(1.0), None, 1.0)
        assert machine.state is DriveState.ESTOP


class TestTraceFlow:
    """미션 본체: 블록 발견 확정 → **최우선 접근**(+상시 탐지) → 소실·타임아웃 복귀.

    정차해서 1장 판정하는 흐름이 아니다 — 유도선은 연속이라 '도착'이 관측되지 않는다
    (근거: state_machine._inspect_cooldown_over). 따라가며 탐지하고, 탐지가 파손
    후보를 본 순간에만 INSPECT로 정차한다 — 그쪽은 TestInspectFlow가 본다.

    진입 조건은 **발견 디바운스뿐**이다: 블록이 확정되면 멀든 치우쳐 있든 간다.
    근접도 하한·중앙 정렬 게이트는 '탐지되면 최우선으로 이동'과 어긋나서 없앴고,
    그것들이 막아 주던 것(원거리 블록에 속도가 죽는 것 / 인도를 벗어나 달려드는 것)은
    TRACE 안의 스로틀 곡선과 근접도 비례 혼합이 대신 막는다 — 아래 테스트가 그 둘을
    각각 고정한다.
    """

    def enter_trace(self, params, t: float = 1.0, offset: float = 0.0,
                    proximity: float = None, check_throttle: bool = True) -> StateMachine:
        """CRUISE에서 확정된 블록 프레임으로 TRACE에 올려놓는다 (진입 시각 = t).

        발견은 confirm_sec 디바운스를 통과해야 하므로 t 이전에 한 번 더 넣는다 —
        진입 시각을 t로 맞춰야 이후 타임아웃·쿨다운 계산이 t 기준으로 유지된다.
        기본 근접도는 enter_proximity(추종 비중 1 = 탐지 저속 구간)다.
        """
        if proximity is None:
            proximity = params.inspect_enter_proximity
        machine = started_machine(params)
        seen_at = t - params.inspect_confirm_sec * 2
        machine.step(braille_frame(seen_at, offset, proximity), None, seen_at)
        assert machine.state is DriveState.CRUISE   # 아직 미확정 — 진입하지 않는다
        command = machine.step(braille_frame(t, offset, proximity), None, t)
        assert machine.state is DriveState.TRACE
        if check_throttle:
            assert command.throttle_pct == pytest.approx(params.trace_throttle_pct)
        return machine

    def test_confirmed_block_traces_at_patrol_speed(self, params):
        """추종은 **감속하지 않는다** — 0803 실외 '속도가 너무 느림'의 지배적 원인이었다.

        한국 인도는 유도선이 길게 깔려 순찰 대부분이 TRACE 체류이고, 종전 35%
        (0.112 m/s)면 20m 구역에 3분이 걸렸다. 감속 근거였던 모션 블러는 0.32 m/s·
        노출 1/60초에서 이동량 5mm이고, 추론 주기도 타일(0.30m)당 2회 이상 확보된다.
        정밀 판정용 정지 화면은 INSPECT가 따로 잡는다.
        """
        machine = self.enter_trace(params)
        command = machine.step(
            braille_frame(1.05, 0.0, params.inspect_enter_proximity), None, 1.05)
        assert machine.state is DriveState.TRACE
        assert command.throttle_pct == pytest.approx(params.cruise_throttle_pct)

    def test_single_frame_block_is_ignored(self, params):
        # 근접·정렬된 블록이라도 한 프레임짜리 검출로는 추종에 들지도, 조향하지도 않는다
        machine = started_machine(params)
        command = machine.step(
            braille_frame(1.0, offset=0.0, proximity=params.inspect_enter_proximity),
            None, 1.0)
        assert machine.state is DriveState.CRUISE
        assert command.steering_offset_deg == 0.0

    def test_far_block_enters_trace_at_patrol_speed(self, params):
        # 멀어도(그리고 치우쳐 있어도) 확정되면 간다 — 치우침·거리는 '가지 말 이유'가
        # 아니라 '아직 도착하지 않았다'는 뜻이다. 속도는 순찰 그대로다
        far = params.inspect_enter_proximity * 0.1
        machine = started_machine(params)
        seen_at = 1.0 - params.inspect_confirm_sec * 2
        machine.step(braille_frame(seen_at, 0.9, far), None, seen_at)
        command = machine.step(braille_frame(1.0, 0.9, far), None, 1.0)
        assert machine.state is DriveState.TRACE
        assert command.throttle_pct == pytest.approx(params.cruise_throttle_pct)

    def test_off_center_block_still_enters_trace(self, params):
        # 좌/우로 치우친 블록도 진입한다 — 치우침은 '가지 말 이유'가 아니라 '아직
        # 도착하지 않았다'는 뜻이다. 인도 이탈은 게이트가 아니라 혼합 조향 +
        # 코스트맵 + 좌우 클램프가 막는다. 좌/우 양쪽 (부호 누락 회귀 방어)
        for offset in (1.0, -1.0):
            machine = started_machine(params)
            seen_at = 1.0 - params.inspect_confirm_sec * 2
            proximity = params.inspect_enter_proximity
            machine.step(braille_frame(seen_at, offset, proximity), None, seen_at)
            machine.step(braille_frame(1.0, offset, proximity), None, 1.0)
            assert machine.state is DriveState.TRACE

    def test_throttle_does_not_fall_continuously_as_block_nears(self, params):
        # 근접도에 **비례해** 느려지지는 않는다 — 근접도 곡선은 조향 혼합 비중에만 쓰인다.
        # 회귀 방어: 종전에는 같은 곡선이 스로틀에도 걸려 블록에 붙을수록 느려졌고,
        # 유도선이 연속인 한국 인도에서 그것은 곧 상시 저속 순찰이었다.
        # 0804에 접근 구간만 한 단(approach_throttle_pct) 낮췄다 — 계단이지 곡선이 아니다.
        speeds = []
        for proximity in (0.0, params.inspect_enter_proximity / 2):
            machine = self.enter_trace(params, proximity=proximity, check_throttle=False)
            command = machine.step(braille_frame(1.05, 0.0, proximity), None, 1.05)
            speeds.append(command.throttle_pct)
        assert speeds[0] == pytest.approx(speeds[1])   # 접근 구간 안에서는 평탄하다
        assert speeds[0] == pytest.approx(params.trace_approach_throttle_pct)

        machine = self.enter_trace(params, proximity=params.inspect_enter_proximity,
                                   check_throttle=False)
        arrived = machine.step(braille_frame(1.05, 0.0, params.inspect_enter_proximity),
                               None, 1.05)
        assert arrived.throttle_pct == pytest.approx(params.cruise_throttle_pct)

    def test_approach_throttle_is_a_nudge_not_a_crawl(self, params):
        # '살짝 줄인다'의 하한을 config 수준에서 못 박는다 — 이 값이 다시 35%대로
        # 내려가면 0803 '자율주행 속도가 너무 느림'이 접근 구간으로 되돌아온다
        assert params.trace_approach_throttle_pct >= 0.7 * params.cruise_throttle_pct
        assert params.trace_approach_throttle_pct <= params.trace_throttle_pct

    def test_trace_speed_equals_patrol_speed_in_config(self, params):
        # 위 테스트들의 전제를 config 수준에서 못 박는다 — 이 값이 다시 내려가면
        # '주행하며 탐지'(명세)가 '기어가며 탐지'가 된다
        assert params.trace_throttle_pct == pytest.approx(params.cruise_throttle_pct)

    def test_trace_blends_instead_of_switching(self, params):
        # 추종 중 목표는 인도 중심과 블록 중심의 혼합이다 — 블록 중심 전량 추종보다 약하게
        midway = params.inspect_enter_proximity / 2
        machine = self.enter_trace(params, proximity=midway, check_throttle=False)
        command = machine.step(braille_frame(1.05, 0.5, midway), None, 1.05)
        assert machine.state is DriveState.TRACE
        full_tracking = 0.5 * params.cruise_steer_gain_deg
        assert 0.0 < command.steering_offset_deg < full_tracking

    def test_trace_steers_left_for_left_block(self, params):
        midway = params.inspect_enter_proximity / 2
        machine = self.enter_trace(params, proximity=midway, check_throttle=False)
        command = machine.step(braille_frame(1.05, -0.5, midway), None, 1.05)
        assert machine.state is DriveState.TRACE
        assert command.steering_offset_deg < 0

    def test_flicker_does_not_break_trace(self, params):
        # 한 프레임 놓친 틱은 인도 중심을 따른다(그 프레임에 블록 측정이 없으니
        # 당연하다 — 디바운스는 발견 '상태'를 유지할 뿐 위치를 지어내지 않는다).
        # 중요한 건 발견이 풀리지 않아 다음 프레임에 추종이 곧바로 이어진다는 것.
        midway = params.inspect_enter_proximity / 2
        machine = self.enter_trace(params, proximity=midway, check_throttle=False)
        command = machine.step(braille_frame(1.05, 0.5, midway), None, 1.05)
        traced = command.steering_offset_deg
        assert traced > 0

        command = machine.step(clear_frame(1.10), None, 1.10)
        assert machine.state is DriveState.TRACE       # 한 프레임에 소실 확정 안 됨
        assert 0.0 <= command.steering_offset_deg < traced

        command = machine.step(braille_frame(1.20, 0.5, midway), None, 1.20)
        assert command.steering_offset_deg == pytest.approx(traced)

    def test_lost_block_returns_to_cruise_without_cooldown(self, params):
        """소실로 끝난 추종은 쿨다운을 걸지 않는다 — 다시 보이면 다시 가야 한다.

        '최우선'의 핵심 회귀 방어다. 예전에는 소실에도 20초 쿨다운을 걸어, 세그가
        블록을 잠깐 놓치면 그 뒤 20초간 블록을 통째로 무시했다.
        """
        machine = self.enter_trace(params, t=1.0)
        t = 1.0
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 4):
            t += 0.05
            machine.step(clear_frame(t), None, t)
        assert machine.state is DriveState.CRUISE

        # 블록이 다시 보이면 디바운스만 채우고 곧바로 재추종한다 (쿨다운 없음)
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 2):
            t += 0.05
            machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.TRACE
        assert t < 1.0 + params.inspect_cooldown_sec   # 쿨다운 창 '안'에서 재추종했다

    def test_trace_timeout_returns_to_cruise_with_cooldown(self, params):
        # 유도선은 끝없이 이어질 수 있다 — 상한이 없으면 순찰이 저속 추종에 갇힌다.
        # 타임아웃은 재추종 쿨다운을 거는 **유일한** 경우다.
        machine = self.enter_trace(params, t=1.0)
        holding = 1.0 + params.trace_timeout_sec / 2
        machine.step(braille_frame(holding, 0.0, params.inspect_enter_proximity),
                     None, holding)
        assert machine.state is DriveState.TRACE

        expired = 1.0 + params.trace_timeout_sec
        machine.step(braille_frame(expired, 0.0, params.inspect_enter_proximity),
                     None, expired)
        assert machine.state is DriveState.CRUISE

        t = expired
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 4):
            t += 0.05
            machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.CRUISE   # 쿨다운 안 — 재추종 금지

        after = expired + params.inspect_cooldown_sec
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 2):
            after += 0.05
            machine.step(braille_frame(after, 0.0, params.inspect_enter_proximity),
                         None, after)
        assert machine.state is DriveState.TRACE    # 쿨다운 종료 → 재추종 허용

    def test_obstacle_beats_braille_gate(self, params):
        # 같은 프레임에 blocking 장애물 + 근접 블록 → 장애물 우선(AVOID)
        machine = started_machine(params)
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)
        frame = SegmentationFrame(
            timestamp=1.0, sidewalk_detected=True, obstacles=(blocking,),
            braille_detected=True, braille_center_offset_ratio=0.0,
            braille_proximity=params.inspect_enter_proximity)
        machine.step(frame, None, 1.0)
        assert machine.state is DriveState.AVOID

    def test_obstacle_during_trace_evades(self, params):
        # 추종 중에도 장애물 처리는 CRUISE와 같다 (IMU 있으면 정지 없이 회피)
        machine = self.enter_trace(params, t=1.0)
        command = machine.step(obstacle_frame(1.05), MockImu().read(), 1.05)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)

    def test_roadway_risk_during_trace_escapes(self, params):
        # 공통 가드는 TRACE 중에도 산다 — 유도선이 횡단보도(차도 위 도색면)로 이어지는
        # 구간의 방어선이고, 대응은 정지가 아니라 선회 이탈이다
        machine = self.enter_trace(params, t=1.0)
        command = machine.step(roadway_risk_frame(1.05), None, 1.05)
        assert machine.state is DriveState.ESCAPE
        assert command.throttle_pct == pytest.approx(params.escape_throttle_pct)

    def test_boundary_lost_during_trace_goes_idle_then_resumes_tracing(self, params):
        """가드로 추종을 떠나도 재출발 후에는 다시 추종한다 (정차 쿨다운만 기록된다).

        가드 이탈은 경계 소실(IDLE)·차도 위험(ESTOP)이고 둘 다 수동 개입으로만 풀린다.
        사람이 다시 출발시켰다면 중단된 검사를 이어가는 것이 맞다 — 여기서 추종을
        막으면 '최우선으로 이동'이 재시작 뒤 조용히 꺼진다.
        """
        machine = self.enter_trace(params, t=1.0)
        machine.step(boundary_lost_frame(2.0), None, 2.0)
        expired = 2.0 + params.perception_grace_sec + 0.05
        machine.step(boundary_lost_frame(expired), None, expired)
        assert machine.state is DriveState.IDLE

        machine.request_start()
        t = expired + 0.05
        machine.step(clear_frame(t), None, t)
        assert machine.state is DriveState.CRUISE

        for _ in range(int(params.inspect_confirm_sec / 0.05) + 2):
            t += 0.05
            machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.TRACE
        # 정차 쿨다운 창(가드 이탈 시각 기준) '안'에서 재추종했다 = 추종은 안 막힌다
        assert t < expired + params.inspect_cooldown_sec

    def test_guard_exit_still_blocks_restop(self, params):
        # 다만 **정차** 쿨다운은 기록된다 — 재출발 직후 같은 파손 위에 서 있을 것이므로
        # 그 자리에서 다시 정차해 같은 판정을 올리는 것은 의미가 없다
        machine = self.enter_trace(params, t=1.0)
        machine.step(roadway_critical_frame(1.5), None, 1.5)
        assert machine.state is DriveState.ESTOP
        machine.request_estop_release()
        machine.step(clear_frame(1.55), None, 1.55)
        machine.request_start()
        t = 1.6
        machine.step(clear_frame(t), None, t)
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 2):
            t += 0.05
            machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.TRACE

        machine.notify_defect_found()
        t += 0.05
        machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.TRACE   # 정차 쿨다운 안 — 보고를 무시한다


class TestInspectFlow:
    """탐지가 파손 후보를 본 순간의 정차 정밀 판정 — TRACE → INSPECT → 쿨다운 재개.

    정차 비용을 내는 이유는 모션 블러다(defect_partial ≈55px). 그래서 상시 정차가
    아니라 **후보를 본 순간에만** 정차한다.
    """

    def enter_inspect(self, params, t: float = 1.0) -> StateMachine:
        """TRACE에서 탐지 통지로 INSPECT에 올려놓는다 (진입 시각 = t)."""
        machine = TestTraceFlow().enter_trace(params, t=t - 0.05)
        machine.notify_defect_found()
        command = machine.step(
            braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.INSPECT
        assert command.throttle_pct == 0
        return machine

    def test_defect_notice_stops_for_inspection(self, params):
        self.enter_inspect(params)

    def test_no_notice_keeps_tracing(self, params):
        # 통지가 없으면 정차하지 않는다 — 상시 탐지의 요점이다
        machine = TestTraceFlow().enter_trace(params, t=1.0)
        t = 1.0
        for _ in range(10):
            t += 0.05
            command = machine.step(
                braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.TRACE
        assert command.throttle_pct > 0

    def test_inspection_done_resumes_tracing(self, params):
        """판정을 마치면 순찰이 아니라 **추종**으로 돌아간다 — 유도선 하나에 파손이 여럿.

        예전에는 CRUISE로 나가고 20초 쿨다운이 걸려, 판정 직후 같은 유도선의 나머지
        구간을 통째로 검사하지 않고 지나갔다.
        """
        machine = self.enter_inspect(params, t=1.0)
        machine.notify_inspection_done()
        command = machine.step(braille_frame(2.0, 0.0, params.inspect_enter_proximity),
                               None, 2.0)
        assert machine.state is DriveState.TRACE
        assert command.throttle_pct == 0  # 재개 틱은 정지 명령 — 다음 틱부터 주행

    def test_inspection_done_returns_to_cruise_if_block_gone(self, params):
        # 판정 중 블록을 지나쳐 소실이 확정됐으면 순찰로 나간다
        machine = self.enter_inspect(params, t=1.0)
        t = 1.0
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 4):
            t += 0.05
            machine.step(clear_frame(t), None, t)
        machine.notify_inspection_done()
        machine.step(clear_frame(t + 0.05), None, t + 0.05)
        assert machine.state is DriveState.CRUISE

    def test_stop_cooldown_blocks_restop_but_not_tracing(self, params):
        """정차 쿨다운은 **정차**만 막는다 — 추종·탐지는 그 안에서도 계속 돈다.

        복귀 직후 로봇은 아직 같은 파손 위에 있어 다음 탐지 주기에 같은 후보가 또
        나온다. 그때 정차하면 그 자리에서 정차가 무한 반복되므로 보고를 무시한다.
        """
        machine = self.enter_inspect(params, t=1.0)
        machine.notify_inspection_done()
        t = 2.0
        machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.TRACE

        # 쿨다운 안: 같은 파손이 또 보고돼도 정차하지 않고 추종을 계속한다
        t = 2.0 + params.inspect_cooldown_sec / 2
        machine.notify_defect_found()
        command = machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity),
                               None, t)
        assert machine.state is DriveState.TRACE
        assert command.throttle_pct > 0      # 정지가 아니다 — 추종이 이어진다

        # 쿨다운 종료 후: 같은 보고에 정차한다
        t = 2.0 + params.inspect_cooldown_sec
        machine.notify_defect_found()
        machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.INSPECT

    def test_timeout_resumes_tracing(self, params):
        # 판정 통지가 유실돼도 타임아웃이 무한 정지를 막는다 (정차 쿨다운도 시작)
        machine = self.enter_inspect(params, t=1.0)
        holding = 1.0 + params.inspect_timeout_sec / 2
        machine.step(braille_frame(holding, 0.0, 1.0), None, holding)
        assert machine.state is DriveState.INSPECT

        expired = 1.0 + params.inspect_timeout_sec
        machine.step(braille_frame(expired, 0.0, 1.0), None, expired)
        assert machine.state is DriveState.TRACE

        machine.notify_defect_found()
        within = expired + params.inspect_cooldown_sec / 2
        machine.step(braille_frame(within, 0.0, 1.0), None, within)
        assert machine.state is DriveState.TRACE  # 정차 쿨다운 안 — 재정차 금지

    def test_trace_timeout_not_reset_by_inspect(self, params):
        """추종 세션 타임아웃은 INSPECT를 다녀와도 리셋되지 않는다.

        정차마다 리셋되면 파손이 잦은 구간에서 trace.timeout_sec이 사실상 사라지고
        (INSPECT→TRACE 복귀가 상한을 무한정 미룬다) 순찰이 그 유도선에 갇힌다.
        """
        machine = TestTraceFlow().enter_trace(params, t=1.0)
        # 세션 중반에 정차 판정을 한 번 끼워 넣는다
        mid = 1.0 + params.trace_timeout_sec / 2
        machine.notify_defect_found()
        machine.step(braille_frame(mid, 0.0, params.inspect_enter_proximity), None, mid)
        assert machine.state is DriveState.INSPECT
        machine.notify_inspection_done()
        machine.step(braille_frame(mid + 0.05, 0.0, params.inspect_enter_proximity),
                     None, mid + 0.05)
        assert machine.state is DriveState.TRACE

        # 세션 시작(1.0) 기준으로 상한을 넘기면 나가야 한다 — 정차 시각 기준이 아니다
        expired = 1.0 + params.trace_timeout_sec + 0.05
        machine.step(braille_frame(expired, 0.0, params.inspect_enter_proximity),
                     None, expired)
        assert machine.state is DriveState.CRUISE

    def test_inspect_holds_without_notice(self, params):
        # 통지도 타임아웃도 없으면 INSPECT 정지 유지 (블록이 안 보여도)
        machine = self.enter_inspect(params, t=1.0)
        holding = 1.0 + params.inspect_timeout_sec / 2
        command = machine.step(clear_frame(holding), None, holding)
        assert machine.state is DriveState.INSPECT
        assert command.throttle_pct == 0

    def test_roadway_risk_during_inspect_escapes(self, params):
        # 공통 가드는 INSPECT 중에도 산다 — 차도가 코리도에 들어오면 판정을 접고 선회한다
        machine = self.enter_inspect(params, t=1.0)
        command = machine.step(roadway_risk_frame(2.0), None, 2.0)
        assert machine.state is DriveState.ESCAPE
        assert command.throttle_pct == pytest.approx(params.escape_throttle_pct)

    def test_critical_roadway_during_inspect_estops(self, params):
        machine = self.enter_inspect(params, t=1.0)
        command = machine.step(roadway_critical_frame(2.0), None, 2.0)
        assert machine.state is DriveState.ESTOP
        assert command.brake

    def test_boundary_lost_during_inspect_goes_idle(self, params):
        # INSPECT는 GUARDED_STATES — 경계 소실이 유예를 넘기면 IDLE 폴백
        machine = self.enter_inspect(params, t=1.0)
        machine.step(boundary_lost_frame(2.0), None, 2.0)
        expired = 2.0 + params.perception_grace_sec * 2
        machine.step(boundary_lost_frame(expired), None, expired)
        assert machine.state is DriveState.IDLE

    def test_guard_exit_records_stop_cooldown(self, params):
        # 가드(경계 소실)로 INSPECT를 떠나도 **정차** 쿨다운은 기록돼야 한다 — 아니면
        # 재시작 직후 같은 파손을 다시 정차 판정해 중복 보고가 난다.
        # (추종 재진입은 막지 않는다 — TestTraceFlow가 그쪽을 고정한다)
        machine = self.enter_inspect(params, t=1.0)
        machine.step(boundary_lost_frame(2.0), None, 2.0)
        expired = 2.0 + params.perception_grace_sec * 2
        machine.step(boundary_lost_frame(expired), None, expired)
        assert machine.state is DriveState.IDLE

        machine.request_start()
        t = 3.0
        machine.step(clear_frame(t), None, t)
        assert machine.state is DriveState.CRUISE
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 2):
            t += 0.05
            machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.TRACE   # 추종은 재개된다

        machine.notify_defect_found()
        t += 0.05
        machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.TRACE   # 정차만 막힌다

    def test_debounce_does_not_freeze_outside_cruise(self, params):
        """발견 판정은 CRUISE 밖에서도 갱신된다 — 얼어붙으면 소실을 지나쳐도 추종이 남는다.

        회귀 방어: 디바운스를 _handle_cruise 안에서만 돌리면 TRACE·INSPECT 체류 중
        판정이 멈춘다. 그러면 블록을 이미 지나쳤어도 braille_stable=True가 남아,
        INSPECT를 나오는 틱에 없는 블록을 추종하러 간다.
        """
        machine = self.enter_inspect(params, t=1.0)

        # INSPECT 체류 중 블록이 화면에서 사라진다(지나쳤다) → 여기서 소실이 확정돼야
        t = 1.0
        for _ in range(int(params.inspect_confirm_sec / 0.05) + 4):
            t += 0.05
            machine.step(clear_frame(t), None, t)
        assert not machine.context.braille_stable

        machine.notify_inspection_done()
        machine.step(clear_frame(t + 0.05), None, t + 0.05)
        assert machine.state is DriveState.CRUISE   # 추종이 아니라 순찰로 나간다

        # 블록이 다시 보여도 확정 시간을 새로 채워야 한다 (한 프레임으로는 안 된다)
        t += 0.10
        machine.step(braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert machine.state is DriveState.CRUISE


class TestInspectCamHoldsTracking:
    """탐지캠(하향) 관측이 주행캠 세그를 **보완**해 추종을 유지한다 (0805 실기 근거).

    주행 카메라는 하향각이 낮아 블록에 올라탄 뒤에도 세그가 자주 놓친다. 그때 추종이
    '블록 소실 확정'으로 끊기면 '블록을 따라가며 순차 탐지'라는 미션 자체가 성립하지
    않는다. 탐지캠은 로봇 바로 앞 노면을 보므로 그 구간에서는 오히려 더 확실한 증거다.

    **발견 쪽으로는 새지 않는다**: 이 신호는 도착 후에만 참이 될 수 있고(하향캠 화각 +
    main.detection_due), TRACE·INSPECT를 떠날 때 지워진다.
    """

    TICK = 0.05

    def lost_ticks(self, machine, params, t: float, count: int) -> float:
        """블록이 세그에서 안 보이는 프레임을 count번 먹인다 → 마지막 시각."""
        for _ in range(count):
            t += self.TICK
            machine.step(clear_frame(t), None, t)
        return t

    def test_cam_observation_keeps_trace_through_a_seg_miss(self, params):
        machine = TestTraceFlow().enter_trace(params, t=1.0)
        t = 1.0
        # 탐지캠이 블록을 봤다 — 그 뒤로 세그는 계속 놓친다.
        machine.notify_braille_seen()
        t += self.TICK
        machine.step(clear_frame(t), None, t)
        assert machine.context.braille_cam_seen_at == pytest.approx(t)

        # 소실 확정 시간을 넘겨도 추종이 유지된다 (유지창 안이므로 raw가 계속 True)
        t = self.lost_ticks(machine, params, t,
                            int(params.inspect_confirm_sec / self.TICK) + 4)
        assert t - machine.context.braille_cam_seen_at < params.trace_cam_hold_sec
        assert machine.context.braille_stable
        assert machine.state is DriveState.TRACE

    def test_stale_observation_lets_the_loss_confirm(self, params):
        """유지창을 넘긴 관측은 죽는다 — 블록을 지나친 뒤 추종이 남으면 안 된다."""
        machine = TestTraceFlow().enter_trace(params, t=1.0)
        t = 1.0
        machine.notify_braille_seen()
        t += self.TICK
        machine.step(clear_frame(t), None, t)

        # 유지창 + 확정창을 모두 넘길 만큼 세그 미검출을 이어간다
        span = params.trace_cam_hold_sec + params.inspect_confirm_sec + 0.2
        t = self.lost_ticks(machine, params, t, int(span / self.TICK))
        assert not machine.context.braille_stable
        assert machine.state is DriveState.CRUISE

    def test_observation_is_cleared_on_trace_exit(self, params):
        """ESCAPE 등으로 이탈하면 관측을 버린다 — 복귀 틱에 없는 블록을 추종하지 않게.

        braille_stable을 가드보다 먼저 갱신하는 것과 같은 이유다(그 회귀는
        test_debounce_does_not_freeze_outside_cruise가 지킨다).
        """
        machine = TestTraceFlow().enter_trace(params, t=1.0)
        machine.notify_braille_seen()
        t = 1.0 + self.TICK
        machine.step(clear_frame(t), None, t)
        assert machine.context.braille_cam_seen_at is not None

        # 차도가 코리도에 들어와 ESCAPE로 이탈 → TRACE 이탈 정리가 관측을 지운다
        t += self.TICK
        machine.step(roadway_risk_frame(t), None, t)
        assert machine.state is DriveState.ESCAPE
        assert machine.context.braille_cam_seen_at is None

    def test_observation_alone_does_not_start_tracking(self, params):
        """탐지캠 관측만으로 CRUISE→TRACE에 들어가지는 않는다 — 그건 발견이 아니다.

        실주행에서는 도착 전에 이 신호가 오지 않지만(하향캠 화각 + detection_due),
        FSM은 그 보장에 기대지 않는다. 발견은 세그의 일이다.
        """
        machine = started_machine(params)
        t = 1.0
        machine.notify_braille_seen()
        machine.step(clear_frame(t), None, t)          # 세그는 블록을 못 봤다
        # 추종 밖의 통지는 **래치조차 하지 않는다** — 래치하면 유지창 동안 raw가 참이
        # 되어 confirm_sec을 채우고, 세그가 블록을 본 적 없는데 TRACE로 진입한다.
        assert machine.context.braille_cam_seen_at is None
        assert machine.state is DriveState.CRUISE
        t = self.lost_ticks(machine, params, t,
                            int(params.inspect_confirm_sec / self.TICK) + 4)
        assert machine.state is DriveState.CRUISE


class TestBrakeSafety:
    """정지 명령의 브레이크 의미론 — 경사 위 무동력(stop)은 후진 활강이 된다."""

    def test_idle_holds_parking_brake(self, params):
        # 대기 중 파킹 브레이크: IMU 없이는 평지를 확신할 수 없다
        machine = StateMachine(params=params, now=0.0)
        command = machine.step(clear_frame(0.0), None, 0.0)
        assert machine.state is DriveState.IDLE
        assert command.brake

    def test_slope_dropout_falls_back_with_brake(self, params):
        # SLOPE 중 인지 두절 → IDLE 폴백 명령도 브레이크여야 한다
        imu = MockImu(pitch_deg=params.slope_enter_pitch_deg)
        machine = started_machine(params)
        machine.step(clear_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE

        command = machine.step(None, imu.read(), 2.0)
        assert command.brake      # 유예 중에도 경사에서 밀리면 안 된다

        expired = 2.0 + params.perception_grace_sec * 2
        command = machine.step(None, imu.read(), expired)
        assert machine.state is DriveState.IDLE
        assert command.brake

    def test_avoid_from_slope_holds_brake(self, params):
        # SLOPE→AVOID 전이 틱 1회 브레이크로는 부족하다 — 홀드 내내 유지돼야 한다
        imu = MockImu(pitch_deg=params.slope_enter_pitch_deg)
        machine = started_machine(params)
        machine.step(clear_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE
        machine.step(obstacle_frame(2.0), imu.read(), 2.0)
        assert machine.state is DriveState.AVOID

        command = machine.step(obstacle_frame(2.1), imu.read(), 2.1)
        assert machine.state is DriveState.AVOID
        assert command.brake  # 홀드 틱에도 브레이크 유지 (경사 밀림 방지)

    def test_avoid_from_cruise_never_brakes(self, params):
        # 평지(CRUISE) 경유 AVOID는 정지하지 않는다 — 선회로 비껴간다
        machine = started_machine(params)
        machine.step(obstacle_frame(1.0), None, 1.0)
        assert machine.state is DriveState.AVOID

        command = machine.step(obstacle_frame(1.1), None, 1.1)
        assert machine.state is DriveState.AVOID
        assert not command.brake
        assert command.throttle_pct > 0

    def test_avoid_hold_from_blocked_costmap_is_powerless_stop(self, params):
        # 비껴갈 방향이 없어 정지 대기할 때는 무동력 정지(평지 경유) — 현행 의미 고정
        machine = started_machine(params)
        machine.step(all_blocked_frame(1.0), None, 1.0)
        command = machine.step(all_blocked_frame(1.1), None, 1.1)
        assert machine.state is DriveState.AVOID
        assert not command.brake
        assert command.throttle_pct == 0


class TestSlopeDrive:
    """SLOPE 경사 보정 주행 — 조향은 인도 중심 추종 유지, 스로틀만 pitch 보정."""

    def slope_machine(self, params, pitch_deg: float):
        """CRUISE에서 지정 pitch로 SLOPE에 올려놓은 (machine, imu)."""
        imu = MockImu(pitch_deg=pitch_deg)
        machine = started_machine(params)
        machine.step(clear_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE
        return machine, imu

    def test_uphill_follows_boost_profile(self, params):
        # 오르막 스로틀 = min(상한, cruise + 이득×pitch). cruise가 상한과 같아지면
        # (100%) 증속 여유가 물리적으로 없으므로 상한에 붙는다 — 감속하지 않는 것이
        # 이 상태의 계약이고, 증속은 여유가 있을 때만의 보너스다
        pitch = params.slope_enter_pitch_deg + 2.0
        machine, imu = self.slope_machine(params, pitch)
        command = machine.step(clear_frame(1.05), imu.read(), 1.05)
        assert machine.state is DriveState.SLOPE
        expected = (params.cruise_throttle_pct
                    + params.slope_uphill_throttle_gain_pct_per_deg * pitch)
        assert command.throttle_pct == pytest.approx(
            min(params.slope_uphill_throttle_max_pct, expected))
        assert command.throttle_pct >= params.cruise_throttle_pct

    def test_uphill_limit_leaves_no_headroom_below_cruise(self, params):
        # 상한을 cruise 아래로 내리면 '오르막에서 감속'이 되어 등판이 실패한다
        assert params.slope_uphill_throttle_max_pct >= params.cruise_throttle_pct

    def test_uphill_throttle_is_capped(self, params):
        machine, imu = self.slope_machine(params, 30.0)   # 비현실적 급경사 → 상한 검증
        command = machine.step(clear_frame(1.05), imu.read(), 1.05)
        assert command.throttle_pct == pytest.approx(params.slope_uphill_throttle_max_pct)

    def test_downhill_reduces_throttle(self, params):
        pitch = -(params.slope_enter_pitch_deg + 2.0)
        assert pitch > -params.slope_downhill_brake_pitch_deg   # 감속 주행 구간이어야 한다
        machine, imu = self.slope_machine(params, pitch)
        command = machine.step(clear_frame(1.05), imu.read(), 1.05)
        assert machine.state is DriveState.SLOPE
        assert (params.slope_downhill_throttle_min_pct
                <= command.throttle_pct < params.cruise_throttle_pct)
        assert not command.brake

    def test_steep_downhill_holds_brake(self, params):
        machine, imu = self.slope_machine(params, -params.slope_downhill_brake_pitch_deg)
        command = machine.step(clear_frame(1.05), imu.read(), 1.05)
        assert machine.state is DriveState.SLOPE   # 전이가 아니라 정지 유지
        assert command.throttle_pct == 0 and command.brake

    def test_slope_keeps_steering_toward_center(self, params):
        machine, imu = self.slope_machine(params, params.slope_enter_pitch_deg + 2.0)
        t = 1.0
        for _ in range(20):   # 슬루율 통과 뒤 정상 상태 조향 확인
            t += 0.05
            command = machine.step(centerline_frame(t, -1.0), imu.read(), t)
        assert machine.state is DriveState.SLOPE
        assert command.steering_offset_deg == pytest.approx(
            -params.cruise_steer_left_max_deg)

    def test_slope_throttle_limits_inside_joystick_floor(self, config, params):
        # 내리막 하한이 실측 구동 하한(그 미만은 모터가 소리만 낸다) 아래면
        # '감속 주행'이 사실상 무동력 활강이 된다
        assert (params.slope_downhill_throttle_min_pct
                >= config["hardware"]["joystick"]["throttle_min_pct"])


class TestTurnFlow:
    """TURN — 조향 수요 포화로 진입, 감속 + 깊은 조향, yaw 진행 실측으로 종료."""

    TICK = 0.05

    def saturate(self, machine, imu, t: float, duration: float, offset: float = -1.0):
        """offset 방향 풀스케일 목표를 duration 동안 유지하며 틱을 돌린다."""
        end = t + duration
        while t < end:
            t += self.TICK
            command = machine.step(centerline_frame(t, offset), imu.read(), t)
        return command, t

    def turning_machine(self, params, offset: float = -1.0):
        """CRUISE에서 조향 수요 포화로 TURN에 올려놓은 (machine, imu, t)."""
        imu = MockImu(pitch_deg=0.0, yaw_deg=0.0)
        machine = started_machine(params)
        _, t = self.saturate(machine, imu, 0.0,
                             params.turn_confirm_sec + self.TICK * 2, offset)
        assert machine.state is DriveState.TURN
        return machine, imu, t

    def test_saturated_demand_enters_turn(self, params):
        machine, _, _ = self.turning_machine(params, offset=-1.0)

    def test_brief_saturation_does_not_enter(self, params):
        # 확정 시간 전에 포화가 풀리면 회전으로 승격되지 않는다 (노이즈 디바운스)
        imu = MockImu()
        machine = started_machine(params)
        _, t = self.saturate(machine, imu, 0.0, params.turn_confirm_sec / 2)
        assert machine.state is DriveState.CRUISE
        machine.step(centerline_frame(t + self.TICK, 0.0), imu.read(), t + self.TICK)
        _, _ = self.saturate(machine, imu, t + self.TICK, params.turn_confirm_sec / 2)
        assert machine.state is DriveState.CRUISE

    def test_no_imu_still_turns(self, params):
        """IMU가 없어도 코너를 돈다 — 0803 실외 '정지만 함'의 또 다른 경로였던 회귀.

        종전에는 TURN이 IMU 게이트 뒤여서, IMU 배선이 해제된 상태에서는 90° 코너에서
        CRUISE 상한(좌 15/우 12)으로 버티다 인도가 화면을 벗어나 경계 소실 IDLE로
        끝났다. 회전 완료의 본 판정은 시각 재정렬이고 yaw는 교차 검증이었으므로,
        yaw가 없으면 그 교차 검증만 시간(min_progress_sec)으로 바꾼다.
        """
        machine = started_machine(params)
        t = 0.0
        for _ in range(int(params.turn_confirm_sec / self.TICK) + 4):
            t += self.TICK
            command = machine.step(centerline_frame(t, -1.0), None, t)
        assert machine.state is DriveState.TURN
        assert command.throttle_pct == pytest.approx(params.turn_throttle_pct)

    def test_no_imu_completes_on_time_plus_realign(self, params):
        machine = started_machine(params)
        t = 0.0
        for _ in range(int(params.turn_confirm_sec / self.TICK) + 4):
            t += self.TICK
            machine.step(centerline_frame(t, -1.0), None, t)
        assert machine.state is DriveState.TURN
        entered = machine.context.entered_at

        # 최소 진행 시간 전의 재정렬은 완료로 치지 않는다 (세그 노이즈 방어)
        early = entered + params.turn_min_progress_sec / 2
        machine.step(centerline_frame(early, 0.0), None, early)
        assert machine.state is DriveState.TURN

        late = entered + params.turn_min_progress_sec + self.TICK
        machine.step(centerline_frame(late, 0.0), None, late)
        assert machine.state is DriveState.CRUISE

    def test_turn_slows_down_and_steers_deep(self, params):
        machine, imu, t = self.turning_machine(params, offset=-1.0)
        for _ in range(20):   # 슬루율 통과 뒤 정상 상태
            t += self.TICK
            command = machine.step(centerline_frame(t, -1.0), imu.read(), t)
        assert machine.state is DriveState.TURN
        assert command.throttle_pct == pytest.approx(params.turn_throttle_pct)
        assert command.throttle_pct < params.cruise_throttle_pct
        assert command.steering_offset_deg == pytest.approx(-params.turn_steer_left_deg)
        assert abs(command.steering_offset_deg) > params.cruise_steer_left_max_deg

    def test_right_turn_uses_right_steer(self, params):
        machine, imu, t = self.turning_machine(params, offset=1.0)
        for _ in range(20):
            t += self.TICK
            command = machine.step(centerline_frame(t, 1.0), imu.read(), t)
        assert command.steering_offset_deg == pytest.approx(params.turn_steer_right_deg)

    def test_completes_on_yaw_progress_and_realign(self, params):
        machine, imu, t = self.turning_machine(params, offset=-1.0)
        imu.yaw_deg = params.turn_min_progress_yaw_deg + 1.0   # 좌회전 진행 = yaw+
        machine.step(centerline_frame(t + self.TICK, 0.0), imu.read(), t + self.TICK)
        assert machine.state is DriveState.CRUISE

    def test_realign_before_min_yaw_keeps_turning(self, params):
        # 진입 직후 세그 노이즈가 '재정렬'로 보여도 실제로 돌기 전에는 완료가 아니다
        machine, imu, t = self.turning_machine(params, offset=-1.0)
        machine.step(centerline_frame(t + self.TICK, 0.0), imu.read(), t + self.TICK)
        assert machine.state is DriveState.TURN

    def test_yaw_progress_without_realign_keeps_turning(self, params):
        machine, imu, t = self.turning_machine(params, offset=-1.0)
        imu.yaw_deg = params.turn_min_progress_yaw_deg + 1.0
        machine.step(centerline_frame(t + self.TICK, -1.0), imu.read(), t + self.TICK)
        assert machine.state is DriveState.TURN

    def test_yaw_overshoot_forces_exit(self, params):
        machine, imu, t = self.turning_machine(params, offset=-1.0)
        imu.yaw_deg = params.turn_max_yaw_deg
        command = machine.step(centerline_frame(t + self.TICK, -1.0),
                               imu.read(), t + self.TICK)
        assert machine.state is DriveState.CRUISE
        assert command.throttle_pct == 0

    def test_timeout_forces_exit(self, params):
        machine, imu, t = self.turning_machine(params, offset=-1.0)
        expired = t + params.turn_timeout_sec
        machine.step(centerline_frame(expired, -1.0), imu.read(), expired)
        assert machine.state is DriveState.CRUISE

    def test_imu_appearing_mid_turn_does_not_abort(self, params):
        """IMU 없이 진입한 회전은 도중에 IMU가 살아나도 시간 기준을 유지한다.

        회귀 방어: 진입 시 yaw 기준을 0.0으로 채워 두면, 도중에 IMU가 응답하기
        시작할 때 '절대 yaw − 0.0'이 진행량으로 계산된다. 실외 절대 yaw는 쉽게
        수십~수백 도이므로 `max_yaw_deg` 상한에 걸려 코너 중간에 회전이 끊긴다.
        기준이 없으면(None) 없는 것으로 두는 것이 맞다.
        """
        machine = started_machine(params)
        t = 0.0
        for _ in range(int(params.turn_confirm_sec / self.TICK) + 4):
            t += self.TICK
            machine.step(centerline_frame(t, -1.0), None, t)
        assert machine.state is DriveState.TURN
        assert machine.context.turn_entry_yaw_deg is None

        # IMU가 살아나며 상한을 훌쩍 넘는 절대 yaw를 낸다
        late_imu = MockImu(pitch_deg=0.0, yaw_deg=params.turn_max_yaw_deg * 3)
        t += self.TICK
        machine.step(centerline_frame(t, -1.0), late_imu.read(), t)
        assert machine.state is DriveState.TURN

    def test_imu_loss_keeps_turning(self, params):
        # IMU가 도중에 사라져도 회전을 포기하지 않는다 — 시간 기준으로 강등될 뿐이다.
        # 종전에는 여기서 CRUISE로 나갔고, 코너 중간에 나가면 인도를 벗어난다
        machine, _, t = self.turning_machine(params, offset=-1.0)
        machine.step(centerline_frame(t + self.TICK, -1.0), None, t + self.TICK)
        assert machine.state is DriveState.TURN

    def test_obstacle_during_turn_evades(self, params):
        machine, imu, t = self.turning_machine(params, offset=-1.0)
        command = machine.step(obstacle_frame(t + self.TICK), imu.read(), t + self.TICK)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)

    def test_trace_gate_precedes_turn_gate(self, params):
        # 블록이 확정되면 인도 중심 오차가 회전 임계를 넘도록 포화돼 있어도 TRACE로 간다 —
        # CRUISE에서 블록 게이트가 회전 판정보다 앞이다. 유도선을 따라가야 하는
        # 구간에서 감속 없이 깊은 조향으로 코너를 도는 것을 막는다.
        # 블록이 화면 끝으로 치우쳐 있어도 마찬가지다(정렬 게이트 제거 — '최우선').
        imu = MockImu()
        for block_offset in (0.0, -1.0):
            machine = started_machine(params)
            t = 0.0
            for _ in range(int(params.turn_confirm_sec / self.TICK) + 8):
                t += self.TICK
                machine.step(SegmentationFrame(
                    timestamp=t, sidewalk_detected=True,
                    sidewalk_center_offset_ratio=-1.0,   # 회전 임계를 넘는 목표 오차
                    braille_detected=True,
                    braille_center_offset_ratio=block_offset,
                    braille_proximity=params.inspect_enter_proximity), imu.read(), t)
            assert machine.state is DriveState.TRACE

    def test_flickering_block_still_allows_turn(self, params):
        # 반대쪽: 블록이 디바운스를 통과하지 못하면(한 프레임 오탐) 포화된 인도 중심
        # 오차는 그대로 코너로 읽혀야 한다 — 진입 조건이 디바운스뿐이므로 그것이
        # 유일한 방어선이고, 이 테스트가 그 방어선을 고정한다
        imu = MockImu()
        machine = started_machine(params)
        t = 0.0
        for index in range(int(params.turn_confirm_sec / self.TICK) + 8):
            t += self.TICK
            # 매 틱 검출이 뒤집혀 confirm_sec을 한 번도 채우지 못한다
            machine.step(SegmentationFrame(
                timestamp=t, sidewalk_detected=True,
                sidewalk_center_offset_ratio=-1.0,
                braille_detected=bool(index % 2),
                braille_center_offset_ratio=-1.0,
                braille_proximity=params.inspect_enter_proximity), imu.read(), t)
        assert machine.state is DriveState.TURN

    def test_pending_does_not_survive_detour(self, params):
        # 포화 도중 다른 상태를 다녀오면 확정 타이머가 리셋돼야 한다 — 낡은
        # 기준 시각으로 복귀 첫 틱에 곧바로 TURN에 드는 회귀 방어
        imu = MockImu()
        machine = started_machine(params)
        _, t = self.saturate(machine, imu, 0.0, params.turn_confirm_sec * 0.7)
        assert machine.state is DriveState.CRUISE

        detour = t + self.TICK
        machine.step(obstacle_frame(detour), imu.read(), detour)
        assert machine.state is DriveState.AVOID
        # 회피 국면은 시각으로 끝난다: 장애물 소멸 → 통과 → 중심 재정렬 → CRUISE
        resume = detour + self.TICK
        machine.step(clear_frame(resume), imu.read(), resume)      # SWERVE → PASS
        resume += params.avoid_pass_sec + self.TICK
        machine.step(clear_frame(resume), imu.read(), resume)      # PASS → RETURN
        resume += self.TICK
        machine.step(clear_frame(resume), imu.read(), resume)      # 중심 오차 0 → CRUISE
        assert machine.state is DriveState.CRUISE

        # 복귀 후 첫 포화 틱: 확정 시간이 다시 필요하므로 아직 CRUISE
        command = machine.step(centerline_frame(resume + self.TICK, -1.0),
                               imu.read(), resume + self.TICK)
        assert machine.state is DriveState.CRUISE

    def test_turn_steer_stays_inside_hardware(self, config, params):
        servo = config["hardware"]["steering_servo"]
        assert params.turn_steer_left_deg <= servo["left_offset_limit_deg"]
        assert params.turn_steer_right_deg <= servo["right_offset_limit_deg"]


class TestAvoidBypass:
    """장애물 회피 — 정지 없이 경로 이득 큰 쪽 벗어나기 → 통과 → 중심 재정렬 복귀
    → CRUISE (원 직선 경로 합류는 중심 추종이 담당). **IMU를 요구하지 않는다.**"""

    TICK = 0.05

    def bypass_started(self, params, bias: float = 0.3, cost_profile=None):
        """장애물을 보고 회피가 막 시작된 (machine, imu, t) — 정지 구간이 없다."""
        imu = MockImu()
        machine = started_machine(params)
        t = 1.0
        command = machine.step(biased_obstacle_frame(t, bias, cost_profile),
                               imu.read(), t)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)
        return machine, imu, t

    def swerve_steer(self, params, bias: float, cost_profile=None) -> float:
        """우회 시작 후 벗어나기 정상 상태(슬루율 통과)의 조향값."""
        machine, imu, t = self.bypass_started(params, bias, cost_profile)
        for _ in range(20):
            t += self.TICK
            command = machine.step(biased_obstacle_frame(t, bias, cost_profile),
                                   imu.read(), t)
        assert machine.state is DriveState.AVOID   # yaw 불변 → 아직 벗어나기 중
        return command.steering_offset_deg

    def test_never_stops_while_evading(self, params):
        # 회귀 방어: 장애물을 본 뒤 재확인 창 내내 스로틀이 0으로 떨어지지 않는다.
        # (종전에는 recheck_sec 3초 동안 정지 대기했다 — 그 정지를 없앤 것이 요구사항)
        imu = MockImu()
        machine = started_machine(params)
        t = 1.0
        end = t + params.avoid_recheck_sec
        while t < end:
            command = machine.step(biased_obstacle_frame(t, 0.3), imu.read(), t)
            assert machine.state is DriveState.AVOID
            assert command.throttle_pct == pytest.approx(
                params.avoid_bypass_throttle_pct)
            t += self.TICK

    def test_swerve_steers_away_from_obstacle_bias(self, params):
        # 코스트맵이 없으면 장애물 치우침의 반대쪽 — 오른쪽 장애물 → 왼쪽 우회
        assert self.swerve_steer(params, bias=0.3) == pytest.approx(
            -params.turn_steer_left_deg)
        assert self.swerve_steer(params, bias=-0.3) == pytest.approx(
            params.turn_steer_right_deg)

    def test_costmap_decides_even_when_both_halves_have_infeasible_slots(self, params):
        # 정상적인 인도는 좌우 **양쪽** 끝에 차도·화단이 걸린다. ∞를 비용 합에
        # 넣으면 양쪽이 모두 ∞로 동점 처리돼 코스트맵이 방향 결정에 아무 기여를
        # 못 했다(사실상 항상 치우침 폴백). 이제 ∞는 개수로 세고, 통행 가능한
        # 슬롯끼리 비용을 비교한다 — 왼쪽이 더 막혀 있으면 오른쪽으로 간다.
        left_worse = (float("inf"), float("inf"), 0.9, 0.0, 0.0, 0.0, float("inf"))
        # 치우침 폴백은 좌(-1)를 고르지만 코스트맵은 우(+1)가 맞다고 본다
        assert self.swerve_steer(params, bias=0.3, cost_profile=left_worse
                                 ) == pytest.approx(params.turn_steer_right_deg)

    def test_costmap_gain_beats_bias(self, params):
        # 좌/우 중 경로 이득(통행 비용 합이 낮은 쪽)이 치우침 폴백보다 우선한다:
        # 장애물이 오른쪽(→폴백은 좌)이어도 왼쪽 절반이 비싸면 오른쪽으로 간다
        left_costly = (0.9, 0.9, 0.9, 0.0, 0.0, 0.0)
        assert self.swerve_steer(params, bias=0.3,
                                 cost_profile=left_costly) == pytest.approx(
            params.turn_steer_right_deg)

    def test_swerve_ignores_lingering_obstacle(self, params):
        # 벗어나기 중 화면에 남은 그 장애물 때문에 재정지하면 우회가 시작도 못 한다
        machine, imu, t = self.bypass_started(params)
        command = machine.step(biased_obstacle_frame(t + self.TICK, 0.3),
                               imu.read(), t + self.TICK)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)

    def test_full_bypass_rejoins_cruise(self, params):
        # 벗어나기(장애물이 경로에서 빠짐) → 통과(pass_sec) → 복귀(중심 재정렬) → CRUISE
        machine, imu, t = self.bypass_started(params, bias=0.3)   # 좌 우회
        t += self.TICK
        command = machine.step(clear_frame(t), imu.read(), t)     # → PASS
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)
        assert command.steering_offset_deg != 0.0   # 슬루율 때문에 아직 중립은 아니다

        t += params.avoid_pass_sec + self.TICK
        machine.step(clear_frame(t), imu.read(), t)               # → RETURN
        assert machine.state is DriveState.AVOID

        t += self.TICK
        command = machine.step(clear_frame(t), imu.read(), t)     # 중심 오차 0 → 복귀 완료
        assert machine.state is DriveState.CRUISE
        assert command.throttle_pct == 0

    def test_return_waits_until_centered(self, params):
        # 복귀는 중심 오차가 좁혀질 때까지 이어진다 — 즉시 끝나면 옆으로 비켜난 채
        # CRUISE로 나가고, 그 상태의 중심 추종은 반대쪽 상한까지 조향을 밀어붙인다
        machine, imu, t = self.bypass_started(params, bias=0.3)
        t += self.TICK
        machine.step(clear_frame(t), imu.read(), t)               # → PASS
        t += params.avoid_pass_sec + self.TICK
        machine.step(clear_frame(t), imu.read(), t)               # → RETURN

        off_center = params.avoid_return_center_abs + 0.2
        t += self.TICK
        machine.step(centerline_frame(t, off_center), imu.read(), t)
        assert machine.state is DriveState.AVOID                 # 아직 중심이 아니다

        t += self.TICK
        machine.step(centerline_frame(t, params.avoid_return_center_abs / 2),
                     imu.read(), t)
        assert machine.state is DriveState.CRUISE

    def test_new_obstacle_during_pass_swerves_again(self, params):
        # 회피 중 또 막히면 **다시 비껴간다** — 종전에는 정지 대기로 돌아갔고, 그것은
        # 사람이 많은 인도에서 정지 연쇄가 된다
        machine, imu, t = self.bypass_started(params, bias=0.3)
        t += self.TICK
        machine.step(clear_frame(t), imu.read(), t)               # → PASS

        t += self.TICK
        command = machine.step(obstacle_frame(t), imu.read(), t)  # 새 장애물 → 재선회
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)

    def test_swerve_phase_timeout_moves_on(self, params):
        # 장애물이 화면에서 계속 잡혀도 선회를 무한정 이어가면 인도를 벗어난다 —
        # 국면 상한에서 통과로 넘어가고, 이어서 복귀·CRUISE까지 흘러간다
        machine, imu, t = self.bypass_started(params)
        expired = t + params.avoid_phase_timeout_sec + self.TICK
        machine.step(biased_obstacle_frame(expired, 0.3), imu.read(), expired)
        assert machine.state is DriveState.AVOID                 # SWERVE → PASS

        expired += params.avoid_pass_sec + self.TICK
        machine.step(clear_frame(expired), imu.read(), expired)   # PASS → RETURN
        expired += self.TICK
        machine.step(clear_frame(expired), imu.read(), expired)
        assert machine.state is DriveState.CRUISE

    def test_imu_loss_mid_bypass_keeps_evading(self, params):
        # 회피 도중 IMU가 사라져도 선회를 멈추지 않는다 — 종전에는 정지로 되돌아갔다
        machine, _, t = self.bypass_started(params)
        command = machine.step(biased_obstacle_frame(t + self.TICK, 0.3),
                               None, t + self.TICK)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)

    def test_slope_origin_avoid_never_bypasses(self, params):
        # 경사에서 넘어온 AVOID(avoid_brake)는 IMU가 있어도 우회하지 않는다 —
        # 경사 위 저속 우회는 밀림·등판 토크 양쪽으로 나쁘다. 브레이크 대기 유지
        imu = MockImu(pitch_deg=params.slope_enter_pitch_deg)
        machine = started_machine(params)
        machine.step(clear_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE
        machine.step(obstacle_frame(2.0), imu.read(), 2.0)
        assert machine.state is DriveState.AVOID

        after = 2.0 + params.avoid_recheck_sec * 2
        command = machine.step(obstacle_frame(after), imu.read(), after)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == 0 and command.brake

    def test_slope_hold_still_returns_when_the_obstacle_clears(self, params):
        """경사발 HOLD는 all_blocked 중에도 **복귀 출구가 남아 있어야 한다** (0805 실외).

        all_blocked가 복귀를 막는 것은 재정향으로 승격할 수 있을 때만 뜻이 있다.
        경사발(avoid_brake)에서는 재정향이 금지돼 있어(경사 위 후진·선회 = 밀림·등판
        토크) 승격 분기가 죽고 선회 분기도 막혀 있다 — 그때 all_blocked가 복귀까지
        막으면 이 HOLD에는 출구가 하나도 남지 않고 장애물이 치워져도 영구 브레이크가
        된다(수동 개입만이 탈출). 경사는 막다른 길이 아니다: 풀리면 주행으로 돌려보내고
        pitch가 남아 있으면 다음 틱에 CRUISE가 곧바로 SLOPE로 되돌린다.
        """
        imu = MockImu(pitch_deg=params.slope_enter_pitch_deg)
        machine = started_machine(params)
        machine.step(clear_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE
        machine.step(obstacle_frame(2.0), imu.read(), 2.0)
        assert machine.state is DriveState.AVOID
        assert machine.context.avoid_brake            # 경사발이라 브레이크 대기다

        # 장애물이 치워졌지만 코스트맵은 여전히 전 슬롯 ∞(경사 정상 너머가 안 보임).
        # 종전(0805 이전)에는 이 장면이 영구 브레이크였다.
        after = 2.0 + params.avoid_recheck_sec * 2
        machine.step(all_blocked_frame(after), imu.read(), after)
        assert machine.state is DriveState.CRUISE     # 출구가 살아 있다

    def test_no_imu_evades_immediately(self, params):
        # IMU 없이도 첫 틱에 선회가 시작된다 — 재확인 대기를 거치지 않는다
        machine = started_machine(params)
        command = machine.step(biased_obstacle_frame(1.0, 0.3), None, 1.0)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == pytest.approx(params.avoid_bypass_throttle_pct)
        assert machine.context.avoid_phase.value == "SWERVE"


class TestReverseThenTurn:
    """후진 복구가 끝나면 **직진이 아니라 선회**로 넘어간다.

    0804 실기: 뒤로 빼고 나서 CRUISE로 그냥 돌아가면 직진해서 방금 물러난 벽에 그대로
    다시 박았다. 뒤로 뺀 목적은 꺾을 공간을 만든 것이므로 유리한 쪽으로 꺾어야 한다.
    """

    TICK = 0.05

    def blocked_lost(self, t: float) -> SegmentationFrame:
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)
        return SegmentationFrame(timestamp=t, sidewalk_detected=False,
                                 obstacles=(blocking,),
                                 cost_profile=(0.2, 0.9, 0.9, 0.9, 0.2))

    def test_reverse_recovery_hands_off_to_turn_not_straight(self, params):
        machine = started_machine(params)
        machine.step(self.blocked_lost(0.1), None, 0.1)      # 디바운스 시작
        armed = 0.1 + params.recovery_reverse_debounce_sec + 0.05
        machine.step(self.blocked_lost(armed), None, armed)  # 후진 복구 시작
        done = armed + params.recovery_reverse_min_sec + 0.05
        seen = SegmentationFrame(timestamp=done, sidewalk_detected=True,
                                 cost_profile=(0.2, 0.9, 0.9, 0.9, 0.2))
        command = machine.step(seen, None, done)
        assert machine.state is DriveState.DEAD_END       # CRUISE 직진이 아니다
        assert command.throttle_pct > 0
        assert command.steering_offset_deg != 0           # 꺾는다

    def test_slope_never_reverses(self, params):
        """**경사에서는 후진 복구를 하지 않는다** (0805 실외 근거를 지키는 회귀 방어).

        이 가드는 GUARDED_STATES 전체에 걸리고 거기엔 SLOPE도 들어 있다. 경사 위
        후진은 내리막에서 중력 가속이 붙고 오르막에서 미끄러지는데, 후방은 카메라
        사각이라 그것을 관측할 수도 없다 — AvoidPhase가 경사 위 선회를 금지하는 것과
        같은 이유다. 경사에서는 종전대로 전진 복구·브레이크로 간다.
        """
        imu = MockImu(pitch_deg=params.slope_enter_pitch_deg)
        machine = started_machine(params)
        machine.step(clear_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE

        # 평지라면 후진이 걸리는 장면 그대로를 경사에서 먹인다
        machine.step(self.blocked_lost(1.1), imu.read(), 1.1)
        armed = 1.1 + params.recovery_reverse_debounce_sec + 0.05
        command = machine.step(self.blocked_lost(armed), imu.read(), armed)
        assert command.throttle_pct >= 0           # 후진이 아니다
        assert machine.context.reverse_recovery_since is None

        # 경사에서는 짧은 창(perception_grace_sec)이 그대로 적용돼 IDLE 폴백으로 간다 —
        # 후진 상한(recovery_reverse_sec)으로 늘어나지 않는다.
        expired = 1.1 + params.perception_grace_sec + 0.05
        machine.step(self.blocked_lost(expired), imu.read(), expired)
        assert machine.state is DriveState.IDLE


class TestStraightenBeforeFollowing:
    """탈출 직후 **바퀴를 중앙으로 정렬한 뒤에** 추종을 시작한다 (0804 실측 근거).

    조향 슬루율 제한 때문에 -40°에서 중앙까지 0.5~0.6초가 걸리는데, 그동안 CRUISE가
    스로틀 100%로 전진하면 19cm를 30° 꺾인 채 달린다 — 방금 피한 쪽으로 호를 그리며
    다시 들어가 '재정향 완료 → 0.3초 뒤 다시 AVOID'가 됐다. 실측 흔적: CRUISE 조향
    상한이 좌15/우12인데 기록된 범위가 -33.3~+20.1이었고, 상한 초과 표본 22건이
    **전부** 직전 DEAD_END였다.
    """

    TICK = 0.05

    def reoriented(self, params):
        """재정향이 완료돼 정렬 국면에 막 들어간 (machine, imu, t)."""
        imu = MockImu()
        machine = started_machine(params)
        machine.step(all_blocked_frame(1.0), imu.read(), 1.0)
        t = 1.0 + params.avoid_recheck_sec
        machine.step(all_blocked_frame(t), imu.read(), t)
        assert machine.state is DriveState.DEAD_END
        t += params.dead_end_reverse_min_sec + self.TICK
        machine.step(clear_frame(t), imu.read(), t)              # 후진 종료 → 선회
        t += params.dead_end_forward_sec + self.TICK
        machine.step(clear_frame(t), imu.read(), t)              # 호 완주 → 완료
        return machine, imu, t

    def test_completion_goes_to_straighten_not_cruise(self, params):
        machine, _, _ = self.reoriented(params)
        assert machine.state is DriveState.DEAD_END
        assert machine.context.dead_end_phase is DeadEndPhase.STRAIGHTEN

    def test_straighten_holds_still(self, params):
        # 정렬은 **정지한 채로** 한다 — 꺾인 채 전진하는 것이 문제였다
        machine, imu, t = self.reoriented(params)
        t += self.TICK
        command = machine.step(clear_frame(t), imu.read(), t)
        assert command.throttle_pct == 0

    def test_resumes_following_after_alignment(self, params):
        machine, imu, t = self.reoriented(params)
        t = settle_to_cruise(machine, clear_frame, t, imu)
        assert machine.state is DriveState.CRUISE
        assert abs(machine.context.prev_steer_deg) <= params.dead_end_straighten_deg

    def test_straighten_has_a_cap(self, params):
        # 서보가 안 따라와도 영원히 서 있지 않는다
        machine, imu, t = self.reoriented(params)
        expired = t + params.dead_end_straighten_max_sec + self.TICK
        machine.step(clear_frame(expired), imu.read(), expired)
        assert machine.state is DriveState.CRUISE


class TestDeadEndUturn:
    """후진↔선회를 반복해도 안 풀리면 U턴으로 승격한다 (0804 실기 요구).

    재정향은 '이 방향에서 나갈 길'을 찾는 기동이라, 그 길이 애초에 없으면 몇 번을
    해도 같은 자리다. U턴에서만 완료 기준이 각도인 이유도 같다 — '경로가 보인다'는
    판정으로 이미 여러 번 실패한 자리다.
    """

    TICK = 0.05

    def started(self, params):
        imu = MockImu()
        machine = started_machine(params)
        machine.step(all_blocked_frame(1.0), imu.read(), 1.0)
        t = 1.0 + params.avoid_recheck_sec
        machine.step(all_blocked_frame(t), imu.read(), t)
        assert machine.state is DriveState.DEAD_END
        return machine, imu, t

    def run_cycles(self, machine, imu, t, params, cycles: int):
        """막힌 채로 후진↔선회 사이클을 cycles회 돌린다."""
        for _ in range(cycles):
            t += params.dead_end_reverse_max_sec + self.TICK      # 후진 → 선회
            machine.step(all_blocked_frame(t), imu.read(), t)
            t += params.dead_end_forward_sec + self.TICK          # 선회 호 끝
            machine.step(all_blocked_frame(t), imu.read(), t)
        return t

    def test_escalates_to_uturn_after_configured_cycles(self, params):
        machine, imu, t = self.started(params)
        self.run_cycles(machine, imu, t, params, params.dead_end_uturn_after_cycles)
        assert machine.state is DriveState.DEAD_END
        assert machine.context.dead_end_phase is DeadEndPhase.UTURN

    def test_does_not_uturn_before_the_limit(self, params):
        # 진입 자체가 1번째 시도다 — 한 사이클 실패하면 2번째(반대쪽 선회)이고,
        # U턴은 3번째부터다
        machine, imu, t = self.started(params)
        self.run_cycles(machine, imu, t, params,
                        params.dead_end_uturn_after_cycles - 2)
        assert machine.context.dead_end_phase is not DeadEndPhase.UTURN

    def test_second_attempt_turns_the_other_way(self, params):
        # 같은 방향으로 반복하면 그쪽이 막힌 자리에서는 몇 번을 해도 같은 결과다
        machine, imu, t = self.started(params)
        first = machine.context.dead_end_direction
        self.run_cycles(machine, imu, t, params, 1)
        assert machine.context.dead_end_direction == -first

    def test_opposite_is_relative_to_what_was_tried(self, params):
        """반대는 '직전에 시도한 쪽'의 반대다 — 지금 유리한 쪽의 반대가 아니다.

        코스트맵은 매 틱 바뀐다. 관측 기준으로 잡으면 1회에 좌를 시도한 뒤 2회 시점에
        우가 유리해질 때 그 반대가 다시 좌가 되어 같은 방향을 반복한다
        (0804 실기: 1번째 좌 → 2번째도 좌).
        """
        from driving.state_machine import _attempt_direction
        右 = 1
        # 지금 유리한 쪽이 어느 쪽이든, 직전에 우(+1)를 했으면 다음은 좌(-1)다
        frame = all_blocked_frame(0.0)
        assert _attempt_direction(frame, 2, previous=右) == -右
        assert _attempt_direction(frame, 2, previous=-右) == 右

    def test_uturn_completes_at_target_yaw(self, params):
        machine, imu, t = self.started(params)
        t = self.run_cycles(machine, imu, t, params,
                            params.dead_end_uturn_after_cycles)
        assert machine.context.dead_end_phase is DeadEndPhase.UTURN
        # U턴 목표각을 채우면 경로가 안 보여도 완료된다 — 각도가 기준이다
        imu.yaw_deg += (params.dead_end_uturn_target_yaw_deg
                        - params.dead_end_uturn_tolerance_deg)
        t += self.TICK
        machine.step(all_blocked_frame(t), imu.read(), t)
        settle_to_cruise(machine, all_blocked_frame, t, imu)

    def test_uturn_takes_a_real_path_without_finishing(self, params):
        """회차 도중 **진짜로 트인 경로**가 나오면 다 돌지 않고 그리로 간다.

        U턴은 목적이 아니라 수단이다 (0804 실기 요구). 다만 기준은 강한 쪽이다 —
        인도가 보이고 정면을 막는 것이 없어야 한다.
        """
        machine, imu, t = self.started(params)
        t = self.run_cycles(machine, imu, t, params,
                            params.dead_end_uturn_after_cycles)
        assert machine.context.dead_end_phase is DeadEndPhase.UTURN
        # 최소 각도를 돌기 전에는 경로가 보여도 안 빠진다 — 진입 순간 이미 참인
        # 조건으로 이탈하면 회전 0°짜리 'U턴'이 된다 (0804 실기)
        t += self.TICK
        machine.step(clear_frame(t), imu.read(), t)
        assert machine.state is DriveState.DEAD_END
        imu.yaw_deg += params.dead_end_uturn_min_yaw_deg
        t += self.TICK
        machine.step(clear_frame(t), imu.read(), t)
        assert "경로 확보" in machine.context.reason
        settle_to_cruise(machine, clear_frame, t, imu)

    def test_uturn_keeps_turning_on_a_weak_signal(self, params):
        # 'blocked 아님'만으로는 안 끝낸다 — 벽이 코앞이어도 슬롯이 ∞만 아니면
        # 참이 되고, 그러면 다시 박는다. 그 실패는 재정향 완료 판정에서 이미 겪었다.
        machine, imu, t = self.started(params)
        t = self.run_cycles(machine, imu, t, params,
                            params.dead_end_uturn_after_cycles)
        assert machine.context.dead_end_phase is DeadEndPhase.UTURN
        t += self.TICK
        machine.step(obstacle_frame(t), imu.read(), t)    # 비용은 유한, 정면은 막힘
        assert machine.state is DriveState.DEAD_END       # 계속 돈다

    def test_uturn_direction_is_fixed_not_alternating(self, params):
        # 회차는 한 방향으로 끝까지 도는 기동이다 — 중간에 바뀌면 번 각도가 상쇄된다
        machine, imu, t = self.started(params)
        self.run_cycles(machine, imu, t, params,
                        params.dead_end_uturn_after_cycles)
        assert machine.context.dead_end_phase is DeadEndPhase.UTURN
        assert machine.context.dead_end_direction == params.dead_end_uturn_direction

    def test_uturn_falls_back_to_time_without_imu(self, params):
        # IMU가 없으면 각도를 못 재므로 시간으로 대신한다 — '아무것도 못 함'보다 낫다
        machine = started_machine(params)
        machine.step(all_blocked_frame(1.0), None, 1.0)
        t = 1.0 + params.avoid_recheck_sec
        machine.step(all_blocked_frame(t), None, t)
        assert machine.state is DriveState.DEAD_END
        t = self.run_cycles(machine, _NoImu(), t, params,
                            params.dead_end_uturn_after_cycles)
        assert machine.context.dead_end_phase is DeadEndPhase.UTURN
        t += params.dead_end_uturn_sec + self.TICK
        machine.step(all_blocked_frame(t), None, t)
        settle_to_cruise(machine, all_blocked_frame, t)


class _NoImu:
    """IMU 미장착 대역 — run_cycles가 read()만 부른다."""

    def read(self):
        return None


class TestAvoidThrashEscalation:
    """반복 회피 승격 — 인지가 '한쪽은 열려 있다'고 우겨도 FSM 이력으로 막힘을 판정한다.

    0804 실내 실측: 재정향 코드가 정상 동작하는데도 90초 동안 회피만 14회 반복됐고
    `all_directions_blocked`는 **한 번도** True가 되지 않아 승격이 영영 일어나지 않았다.
    승격 조건을 인지에만 맡기면 인지 오탐 한 종류로 상태 전체가 도달 불가가 된다 —
    같은 실패를 `TestWallTriggersTurnaround` 주석이 이미 한 번 기록하고 있다.
    """

    TICK = 0.05

    def swervable_frame(self, t: float) -> SegmentationFrame:
        """정면은 막혔는데 인지는 '좌우 중 한쪽은 열렸다'고 본다 — 오탐 장면 그대로."""
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)
        return SegmentationFrame(timestamp=t, sidewalk_detected=True,
                                 obstacles=(blocking,),
                                 cost_profile=(0.2, 0.9, 0.9, 0.9, 0.2),
                                 all_directions_blocked=False)

    def back_to_cruise(self, machine, t: float) -> float:
        """맑은 프레임을 먹여 CRUISE로 돌려놓는다 — AVOID 복귀는 여러 국면을 거친다
        (SWERVE → PASS → RETURN), 한 틱에 끝나지 않는다."""
        for _ in range(60):
            if machine.state is DriveState.CRUISE:
                return t
            t += self.TICK
            machine.step(clear_frame(t), None, t)
        raise AssertionError(f"CRUISE로 복귀하지 못했다 (상태={machine.state})")

    def thrash(self, params, cycles: int, collect=None):
        """회피 진입 ↔ CRUISE 복귀를 cycles회 반복한 (machine, t)."""
        machine = started_machine(params)
        t = 1.0
        for _ in range(cycles):
            command = machine.step(self.swervable_frame(t), None, t)
            if collect is not None:
                collect.append(command)
            if machine.state is DriveState.DEAD_END:
                return machine, t
            t = self.back_to_cruise(machine, t) + 0.5   # 창(6초) 안의 간격
        return machine, t

    def test_repeated_avoid_escalates_to_reorient(self, params):
        machine, _ = self.thrash(params, params.avoid_thrash_limit)
        assert machine.state is DriveState.DEAD_END

    def test_escalation_reverses_first(self, params):
        commands = []
        machine, _ = self.thrash(params, params.avoid_thrash_limit, collect=commands)
        assert machine.state is DriveState.DEAD_END
        command = commands[-1]
        # 승격의 첫 명령은 후진이다 — 인지가 '열렸다'고 한 방향으로 또 선회하면
        # 같은 루프를 한 번 더 도는 것뿐이다
        assert command.throttle_pct == pytest.approx(-params.dead_end_throttle_pct)

    def test_below_limit_keeps_swerving(self, params):
        machine, _ = self.thrash(params, params.avoid_thrash_limit - 1)
        assert machine.state is not DriveState.DEAD_END

    def test_gap_resets_the_count(self, params):
        # 창을 넘겨 조용했으면 그 사이에 실제로 나아갔다는 뜻 — 처음부터 다시 센다
        machine = started_machine(params)
        t = 1.0
        for _ in range(params.avoid_thrash_limit - 1):
            machine.step(self.swervable_frame(t), None, t)
            t = self.back_to_cruise(machine, t) + 0.5
        t += params.avoid_thrash_window_sec + 1.0      # 충분히 조용한 구간
        machine.step(self.swervable_frame(t), None, t)
        assert machine.state is DriveState.AVOID       # 카운트 리셋 → 승격하지 않는다


class TestDeadEndTurnaround:
    """막다른 길 재정향 — 좌/우 모두 경로 없음 유지 → 후진으로 공간 확보 → 유리한
    쪽으로 선회 전진 → **시각으로** 완료. 진입은 AVOID 재확인 경유다.

    0804 개편: 종전은 좌회전 고정 180° K턴이었고 완료 판정이 yaw 단독이었다.
    yaw_sign 부호가 틀리자 누적이 반대로 가며 완료 조건에 도달 자체가 불가능해져
    매번 timeout 30초를 채웠다. 완료를 시각으로 옮기면 그 종류의 실수가 주행을
    멈추지 못한다 — AVOID·TURN이 이미 같은 이유로 시각 판정이다.
    """

    TICK = 0.05

    def turnaround_started(self, params, imu=None):
        """전 방향 통행 불가가 재확인까지 유지돼 재정향이 막 시작된 (machine, imu, t).

        첫 국면은 **후진**이다 — 벽에 붙은 채로는 선회할 공간이 없다.
        """
        imu = MockImu() if imu is None else imu
        reading = None if imu is False else imu.read()
        machine = started_machine(params)
        machine.step(all_blocked_frame(1.0), reading, 1.0)
        assert machine.state is DriveState.AVOID   # 일단 정지 후 재확인
        t = 1.0 + params.avoid_recheck_sec
        command = machine.step(all_blocked_frame(t), reading, t)
        assert machine.state is DriveState.DEAD_END
        assert command.throttle_pct == pytest.approx(-params.dead_end_throttle_pct)
        assert command.steering_offset_deg == pytest.approx(0.0)
        return machine, imu, t

    def test_all_blocked_persisting_enters_dead_end(self, params):
        self.turnaround_started(params)

    def test_starts_with_reverse_not_forward(self, params):
        # 개편의 핵심 — 벽에 코가 닿은 상태에서 전진 호부터 시도하면 헛바퀴만 돈다
        _, _, _ = self.turnaround_started(params)   # 진입 명령이 후진임을 위에서 검증

    def test_all_blocked_without_imu_still_reorients(self, params):
        # IMU 없이도 재정향한다 — 완료가 시각이라 yaw가 필요 없다.
        # 종전에는 IMU 게이트라 센서가 빠지면 막다른 길에서 아무것도 못 했고,
        # 그것은 AvoidPhase 주석이 기록한 0803 실외 실패와 같은 형태다.
        machine = started_machine(params)
        machine.step(all_blocked_frame(1.0), None, 1.0)
        assert machine.state is DriveState.AVOID
        t = 1.0 + params.avoid_recheck_sec
        command = machine.step(all_blocked_frame(t), None, t)
        assert machine.state is DriveState.DEAD_END
        assert command.throttle_pct == pytest.approx(-params.dead_end_throttle_pct)

    def test_turn_arc_steers_toward_chosen_side(self, params):
        machine, imu, t = self.turnaround_started(params)
        # 막힘이 유지되면 후진은 상한까지 이어진다 — 고정 시간이 아니다
        t += params.dead_end_reverse_max_sec + self.TICK
        command = machine.step(all_blocked_frame(t), imu.read(), t)   # 후진 → 선회 전진
        assert machine.state is DriveState.DEAD_END
        assert command.throttle_pct == pytest.approx(params.dead_end_throttle_pct)
        # all_blocked_frame은 좌우 대칭이라 동점 → 좌(조향 여유가 더 크다)
        assert command.steering_offset_deg == pytest.approx(-params.dead_end_steer_left_deg)

    def test_alternates_reverse_and_turn_arc(self, params):
        # 국면 경계를 한 틱 넘겨 밟는다 — 등호 경계가 아니라 '교대가 일어나는가'다
        machine, imu, t = self.turnaround_started(params)
        t += params.dead_end_reverse_max_sec + self.TICK
        command = machine.step(all_blocked_frame(t), imu.read(), t)   # → 선회 전진
        assert command.throttle_pct == pytest.approx(params.dead_end_throttle_pct)

        t += params.dead_end_forward_sec + self.TICK
        command = machine.step(all_blocked_frame(t), imu.read(), t)   # → 다시 후진
        assert command.throttle_pct == pytest.approx(-params.dead_end_throttle_pct)
        assert command.steering_offset_deg == pytest.approx(0.0)

    def test_reverse_continues_while_blocked(self, params):
        # 후진은 고정 시간이 아니라 막힘이 풀릴 때까지다 — 0.9초 고정이던 종전
        # 구조는 벽에 바짝 붙었을 때 선회 공간을 못 만들고 다시 벽에 박았다.
        machine, imu, t = self.turnaround_started(params)
        mid = t + params.dead_end_reverse_min_sec + self.TICK
        command = machine.step(all_blocked_frame(mid), imu.read(), mid)
        assert command.throttle_pct == pytest.approx(-params.dead_end_throttle_pct)

    def test_reverse_ends_early_once_clear(self, params):
        # 막힘이 풀리면 상한을 기다리지 않고 최소 시간 뒤 선회로 넘어간다
        machine, imu, t = self.turnaround_started(params)
        early = t + params.dead_end_reverse_min_sec + self.TICK
        command = machine.step(clear_frame(early), imu.read(), early)
        assert machine.state is DriveState.DEAD_END      # 아직 완료가 아니다
        assert command.throttle_pct == pytest.approx(params.dead_end_throttle_pct)

    def test_does_not_complete_during_reverse(self, params):
        # 반복 회피로 승격된 경우 인지는 그 순간에도 '안 막혔다'고 말한다 —
        # 여기서 완료를 보면 후진을 한 틱도 못 하고 같은 루프에 다시 빠진다
        machine, imu, t = self.turnaround_started(params)
        command = machine.step(clear_frame(t + self.TICK), imu.read(), t + self.TICK)
        assert machine.state is DriveState.DEAD_END
        assert command.throttle_pct == pytest.approx(-params.dead_end_throttle_pct)

    def test_completes_when_path_reappears(self, params):
        # 완료는 시각이다 — 각도가 아니라 '갈 수 있는 경로가 생겼는가'.
        # 단 최소 한 번의 후진과 선회를 마친 뒤에 판정한다.
        machine, imu, t = self.turnaround_started(params)
        t += params.dead_end_reverse_min_sec + self.TICK
        machine.step(clear_frame(t), imu.read(), t)          # 후진 종료 → 선회
        # 선회 호를 다 돈 뒤에 판정한다 — 매 틱 보면 진입 순간 바로 끝난다
        t += params.dead_end_forward_sec + self.TICK
        command = machine.step(clear_frame(t), imu.read(), t)
        assert command.throttle_pct == 0
        settle_to_cruise(machine, clear_frame, t, imu)   # 바퀴 정렬 뒤 추종 시작

    def test_completes_without_imu(self, params):
        # yaw 없이도 완료된다 — 부호·적분 오류가 출구를 막을 수 없다는 것이 개편의 요지
        machine = started_machine(params)
        machine.step(all_blocked_frame(1.0), None, 1.0)
        t = 1.0 + params.avoid_recheck_sec
        machine.step(all_blocked_frame(t), None, t)
        assert machine.state is DriveState.DEAD_END
        t += params.dead_end_reverse_min_sec + self.TICK
        machine.step(clear_frame(t), None, t)                # 후진 종료 → 선회
        t += params.dead_end_forward_sec + self.TICK         # 호를 다 돈 뒤 판정
        machine.step(clear_frame(t), None, t)
        settle_to_cruise(machine, clear_frame, t)

    def test_ignores_blocking_obstacle_during_turnaround(self, params):
        # 막다른 길에서는 벽·구석이 상시 blocking으로 잡힌다 — 재정지하면 재정향이
        # 영원히 시작을 못 하므로, **아직 막혀 있는 동안**의 장애물은 무시한다.
        machine, imu, t = self.turnaround_started(params)
        walled = replace(all_blocked_frame(t + self.TICK),
                         obstacles=(ObstacleRegion(lateral_bias=0.0, proximity=1.0,
                                                   blocking=True),))
        command = machine.step(walled, imu.read(), t + self.TICK)
        assert machine.state is DriveState.DEAD_END
        assert command.throttle_pct != 0

    def test_blocking_obstacle_blocks_completion(self, params):
        """정면을 막는 것이 남아 있으면 완료가 아니다 (0804 실기 요구).

        종전에는 blocking을 무시하고 'blocked 아님'만 봤다. 그러면 벽이 코앞이어도
        슬롯이 ∞만 아니면 통과해, 꺾자마자 빠져나가고 CRUISE가 곧장 다시 박았다 —
        시도 하나가 1.6초 만에 소진돼 3초 만에 U턴까지 갔다.
        대가로 좁은 곳에서는 상한(reverse_max/timeout)까지 가는 경우가 생기지만,
        그쪽이 '나갈 수 있다고 착각하고 다시 박는 것'보다 낫다.
        """
        machine, imu, t = self.turnaround_started(params)
        t += params.dead_end_reverse_min_sec + self.TICK
        machine.step(clear_frame(t), imu.read(), t)          # 경로 인지 → 선회
        t += params.dead_end_forward_sec + self.TICK
        machine.step(obstacle_frame(t), imu.read(), t)
        assert machine.state is DriveState.DEAD_END          # 아직 완료가 아니다

    def test_spin_cap_gives_up_to_manual_wait(self, params):
        # 한 바퀴 가까이 돌고도 길이 없으면 갇힌 것 — 무한 회전 대신 수동 대기.
        # 부호에 의존하지 않도록 절댓값으로 본다.
        machine, imu, t = self.turnaround_started(params)
        imu.yaw_deg = -params.dead_end_max_yaw_deg   # 반대 부호로도 걸려야 한다
        command = machine.step(all_blocked_frame(t + self.TICK), imu.read(), t + self.TICK)
        assert machine.state is DriveState.IDLE
        assert command.throttle_pct == 0 and command.brake

    def test_timeout_gives_up_to_manual_wait(self, params):
        machine, imu, t = self.turnaround_started(params)
        expired = t + params.dead_end_timeout_sec
        command = machine.step(all_blocked_frame(expired), imu.read(), expired)
        assert machine.state is DriveState.IDLE
        assert command.throttle_pct == 0 and command.brake

    def test_imu_loss_mid_reorient_keeps_going(self, params):
        # IMU가 도중에 빠져도 재정향은 계속된다 — 회전 상한만 비활성이고,
        # timeout이 여전히 무한 반복을 막는다
        machine, imu, t = self.turnaround_started(params)
        command = machine.step(all_blocked_frame(t + self.TICK), None, t + self.TICK)
        assert machine.state is DriveState.DEAD_END
        assert command.throttle_pct != 0

    def test_estop_still_wins_during_turnaround(self, params):
        # 공통 가드는 재정향 중에도 산다 — 코리도가 차도로 덮이면 즉시 ESTOP
        machine, imu, t = self.turnaround_started(params)
        command = machine.step(roadway_critical_frame(t + self.TICK), imu.read(),
                               t + self.TICK)
        assert machine.state is DriveState.ESTOP
        assert command.brake

    def test_roadway_risk_during_turnaround_escapes(self, params):
        # 차도가 코리도에 들어오면 재정향을 접고 선회로 벗어난다 — 막다른 길이 차도
        # 앞인 경우가 흔하고, 그때 후진하며 차도에 들어가는 것보다 선회가 맞다
        machine, imu, t = self.turnaround_started(params)
        machine.step(roadway_risk_frame(t + self.TICK), imu.read(), t + self.TICK)
        assert machine.state is DriveState.ESCAPE

    def test_dead_end_steer_stays_inside_hardware(self, config, params):
        limits = config["hardware"]["steering_servo"]
        assert params.dead_end_steer_left_deg <= limits["left_offset_limit_deg"]
        assert params.dead_end_steer_right_deg <= limits["right_offset_limit_deg"]


class TestImuGate:
    """완료 기준 4: IMU mock 교체만으로 IMU 의존 경로가 열리는 구조 확인."""

    def test_jetson_imu_without_sensor_keeps_gate_closed(self, config, params):
        # 센서 미배선(버스 열기 실패) 젯슨 IMU로는 어떤 경사에서도 SLOPE에 못 들어간다.
        # 실제 버스를 열지 않도록 실패하는 bus_factory를 주입한다.
        imu_config = config["hardware"]["imu"]

        def no_device(bus_number: int):
            raise OSError(2, "No such device")

        skeleton = JetsonImu(
            bus=imu_config["bus"], address=imu_config["address"],
            forward_axis=imu_config["forward_axis"], up_axis=imu_config["up_axis"],
            gyro_bias_samples=imu_config["gyro_bias_samples"],
            complementary_alpha=imu_config["complementary_alpha"],
            yaw_sign=imu_config["yaw_sign"],
            bus_factory=no_device,
        )
        assert skeleton.read() is None

        machine = started_machine(params)
        machine.step(clear_frame(1.0), skeleton.read(), 1.0)
        assert machine.state is DriveState.CRUISE

    def test_mock_imu_swap_opens_slope_path(self, params):
        # 같은 코드에 IMU mock만 꽂으면 SLOPE 진입이 활성화된다
        imu = MockImu(pitch_deg=params.slope_enter_pitch_deg)
        machine = started_machine(params)
        machine.step(clear_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE

        # 경사 해소 → CRUISE 복귀
        imu.pitch_deg = 0.0
        machine.step(clear_frame(2.0), imu.read(), 2.0)
        assert machine.state is DriveState.CRUISE

    def test_obstacle_during_slope_brakes_into_avoid(self, params):
        # 경사 중 장애물 인식 → AVOID 전이, 이때는 밀림 방지 위해 브레이크 명령
        imu = MockImu(pitch_deg=params.slope_enter_pitch_deg)
        machine = started_machine(params)
        machine.step(clear_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE

        command = machine.step(obstacle_frame(2.0), imu.read(), 2.0)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == 0 and command.brake

    def test_slope_exit_when_imu_lost(self, params):
        # SLOPE 도중 IMU가 사라지면(게이트 소실) 안전하게 CRUISE로 돌아온다
        imu = MockImu(pitch_deg=params.slope_enter_pitch_deg)
        machine = started_machine(params)
        machine.step(clear_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.SLOPE

        machine.step(clear_frame(2.0), None, 2.0)
        assert machine.state is DriveState.CRUISE


class TestStartRequestHold:
    """시작 요청 유지창 — 0804 실외 '터치를 눌러도 안 나감'의 직접 원인.

    IDLE 탈출 조건은 '시작 요청 + 인도 확인' 둘인데 요청은 1틱에 소비됐다. 경계
    소실로 IDLE에 떨어진 직후는 정의상 인도가 안 보이는 상태라, 누른 요청이
    매번 그대로 버려졌다. 유지창 안에서만 재시도하고, 넘기면 버린다 —
    무한 래치는 '수동 개입만이 IDLE의 출구'를 무력화한다.
    """

    def test_start_survives_until_sidewalk_appears(self, params):
        machine = StateMachine(params=params, now=0.0)
        machine.request_start()

        # 인도 미확인 → 아직 IDLE, 그러나 요청은 유지된다
        machine.step(boundary_lost_frame(0.0), None, 0.0)
        assert machine.state is DriveState.IDLE
        assert machine.start_pending

        # 유지창 안에서 인도가 들어오면 출발한다 — 다시 누르지 않아도
        t = params.start_request_hold_sec / 2.0
        machine.step(clear_frame(t), None, t)
        assert machine.state is DriveState.CRUISE
        assert not machine.start_pending

    def test_start_dropped_after_hold_window(self, params):
        machine = StateMachine(params=params, now=0.0)
        machine.request_start()
        machine.step(boundary_lost_frame(0.0), None, 0.0)
        assert machine.start_pending

        # 창을 넘기면 요청을 버린다
        t = params.start_request_hold_sec
        machine.step(boundary_lost_frame(t), None, t)
        assert machine.state is DriveState.IDLE
        assert not machine.start_pending

        # 그 뒤 인도가 들어와도 스스로 출발하지 않는다 (자리를 떠난 뒤 자동 출발 금지)
        machine.step(clear_frame(t + 0.1), None, t + 0.1)
        assert machine.state is DriveState.IDLE

    def test_repress_reopens_window(self, params):
        machine = StateMachine(params=params, now=0.0)
        machine.request_start()
        machine.step(boundary_lost_frame(0.0), None, 0.0)

        # 창이 끝나기 직전에 다시 누르면 창이 갱신된다
        t = params.start_request_hold_sec - 0.1
        machine.request_start()
        machine.step(boundary_lost_frame(t), None, t)
        assert machine.start_pending

        later = t + params.start_request_hold_sec - 0.1
        machine.step(clear_frame(later), None, later)
        assert machine.state is DriveState.CRUISE

    def test_perception_outage_does_not_drop_request(self, params):
        """카메라 장애(frame=None) 중에도 요청은 유지된다 — 인지 두절은 IDLE 유지다."""
        machine = StateMachine(params=params, now=0.0)
        machine.request_start()
        machine.step(None, None, 0.0)
        assert machine.state is DriveState.IDLE
        assert machine.start_pending


class TestTraceApproachThrottle:
    """접근 구간만 살짝 감속 — 도착 후 추종 속도는 종전 그대로다.

    임계값은 조향 혼합이 쓰는 것과 같은 inspect.enter_proximity다: '블록 쪽으로 가는
    중'의 정의가 조향과 속도에서 갈리면 한쪽만 튜닝했을 때 조용히 어긋난다.
    """

    def _enter_trace(self, params, proximity: float):
        machine = started_machine(params)
        t = 1.0
        while machine.state is not DriveState.TRACE and t < 4.0:
            machine.step(braille_frame(t, 0.0, proximity), None, t)
            t += 0.05
        assert machine.state is DriveState.TRACE
        return machine, t

    def test_approach_uses_reduced_throttle(self, params):
        far = params.inspect_enter_proximity / 2.0
        machine, t = self._enter_trace(params, far)
        command = machine.step(braille_frame(t, 0.0, far), None, t)
        assert command.throttle_pct == params.trace_approach_throttle_pct

    def test_arrived_restores_trace_throttle(self, params):
        machine, t = self._enter_trace(params, params.inspect_enter_proximity)
        command = machine.step(
            braille_frame(t, 0.0, params.inspect_enter_proximity), None, t)
        assert command.throttle_pct == params.trace_throttle_pct


class TestWallTriggersTurnaround:
    """벽·담장으로 좌우까지 막히면 회차(180° K턴)로 승격된다.

    0804 확인: 회차 코드는 있었지만 트리거가 '전 슬롯 비용 ∞'뿐이어서 실외에서
    사실상 발동하지 않았다 — ∞는 차도·횡단보도만이고, 벽은 미분류(0.9)라 비용이
    유한하다. 그 장면에서는 좌우 회피만 무한 반복됐다(SWERVE→PASS→RETURN→재막힘).
    지금은 인지 계층의 all_directions_blocked(자유거리 절대 판정)가 같은 게이트를 연다.
    """

    def wall_frame(self, t: float) -> SegmentationFrame:
        """정면·좌·우 모두 자유공간 없음. 비용은 유한하다(벽은 차도가 아니다)."""
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)
        return SegmentationFrame(timestamp=t, sidewalk_detected=True,
                                 obstacles=(blocking,),
                                 cost_profile=(0.9,) * 5,
                                 all_directions_blocked=True)

    def test_wall_escalates_to_turnaround(self, params):
        imu = MockImu()
        machine = started_machine(params)
        machine.step(self.wall_frame(1.0), imu.read(), 1.0)
        assert machine.state is DriveState.AVOID      # 먼저 정지 후 재확인

        t = 1.0 + params.avoid_recheck_sec
        command = machine.step(self.wall_frame(t), imu.read(), t)
        assert machine.state is DriveState.DEAD_END
        # 첫 국면은 후진이다 — 벽에 코가 닿은 채로 전진 호부터 시도하면 헛바퀴다
        assert command.throttle_pct == pytest.approx(-params.dead_end_throttle_pct)

    def test_wall_without_imu_still_reorients(self, params):
        # 0804 개편: 재정향 완료가 시각이라 IMU 없이도 성립한다. 종전에는 IMU 게이트라
        # 센서가 빠지면 벽 앞에서 좌우 회피만 무한 반복했다.
        machine = started_machine(params)
        machine.step(self.wall_frame(1.0), None, 1.0)
        t = 1.0 + params.avoid_recheck_sec
        machine.step(self.wall_frame(t), None, t)
        assert machine.state is DriveState.DEAD_END

    def test_clearing_wall_returns_to_cruise(self, params):
        # 재확인에서 풀리면 회차하지 않는다 — 오탐 한 번에 180° 돌지 않게
        imu = MockImu()
        machine = started_machine(params)
        machine.step(self.wall_frame(1.0), imu.read(), 1.0)
        t = 1.0 + params.avoid_recheck_sec
        machine.step(clear_frame(t), imu.read(), t)
        assert machine.state is DriveState.CRUISE
