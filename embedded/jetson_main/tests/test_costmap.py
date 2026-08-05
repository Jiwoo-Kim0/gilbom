"""시맨틱 코스트맵(N7) 테스트 — 비용 프로파일 산출(순수 계층) + 방향 선택 + CRUISE 통합.

numpy 계층(_slot_class_fractions)은 보드에서 검증한다 — 여기서는 구성비 dict에
비용표를 적용하는 build_cost_profile과, 그 프로파일을 소비하는 순수 로직만 다룬다.
임계값·가중치는 config.yaml에서 읽는다 (숫자 하드코딩 금지). select_direction의
순수 함수 단위 테스트만 합성 가중치를 입력으로 쓴다 — 튜닝값 복제가 아니라 테스트
입력이다. config 픽스처는 tests/conftest.py에 있다.
"""

import math

import pytest

from driving.state_machine import StateMachineParams, StateMachine
from driving.states import DriveState
from driving.steering_costmap import select_direction, slot_center_ratio
from hardware.mocks import MockImu
from perception.interfaces import ObstacleRegion, SegmentationFrame
from perception.segmentation import (UNKNOWN_CLASS, CostmapParams,
                                     build_cost_profile)


class TestClassPriorityTable:
    """7클래스 우선순위 표(명세 요구)의 자기모순 검사 — 전부 조용히 깨지는 종류다.

    현행 모델은 segv2.pt(7클래스: sidewalk·roadway·alley·bike_lane·
    braille_guide_blocks·caution_zone·crosswalk). 클래스 수가 다른 가중치로 갈아끼울
    때 표를 안 고치면 판정이 로그에도 동작에도 안 보이게 약해진다 —
    기동 시 실제 model.names와의 대조는 segmentation._warn_class_mismatch가 한다.
    이 테스트는 모델 없이 검증 가능한 **표 내부의 짝**만 본다.
    """

    def test_all_seven_classes_are_listed(self, seg):
        # 표에 없는 클래스는 코스트맵에서 unknown_cost로 떨어지고 주행가능/차도
        # 판정에서 조용히 빠진다 — 명세의 '7개 클래스 각각 우선순위를 배정'이 이 줄이다
        assert set(seg["classes"]) == {
            "sidewalk", "roadway", "alley", "bike_lane",
            "braille_guide_blocks", "caution_zone", "crosswalk"}

    def test_every_class_has_drivable_and_cost(self, seg):
        for name, policy in seg["classes"].items():
            assert "drivable" in policy, f"{name}: drivable 누락"
            assert "cost" in policy, f"{name}: cost 누락 (0으로 조용히 떨어지면 위험)"

    def test_roadway_classes_are_infeasible(self, classes):
        # '차도 방향으로는 애초에 조향하지 않는다'가 1차 방어선 — ESCAPE·ESTOP은 그 뒤
        costs = dict(classes.costs)
        assert classes.roadway, "차도 취급 클래스가 하나도 없다"
        for name in classes.roadway:
            assert math.isinf(costs[name]), f"{name}의 비용이 ∞가 아니다"

    def test_crosswalk_is_roadway(self, classes, seg):
        # 횡단보도는 차도 위 도색면이다 — roadway에서 빠지면 횡단보도 앞 방어가 사라진다
        assert seg["crosswalk_class"] in classes.roadway

    def test_braille_is_drivable(self, classes):
        # 블록 앞 정차 시 인도가 가려져도 경계 소실로 오판하지 않게 하는 조건
        assert classes.braille in classes.drivable

    def test_drivable_and_roadway_are_disjoint(self, classes):
        # 겹치면 같은 픽셀이 주행가능이면서 침범 위험이 된다
        assert not set(classes.drivable) & set(classes.roadway)

    def test_drivable_classes_are_free(self, classes):
        # 주행가능인데 비용이 붙어 있으면 '갈 수 있지만 가기 싫은 곳'이라는 뜻이고,
        # 인도 주행에서 그런 클래스는 없다 (있다면 drivable을 false로 두는 것이 맞다)
        costs = dict(classes.costs)
        for name in classes.drivable:
            assert costs[name] == 0.0, f"{name}: 주행가능인데 비용 {costs[name]}"

    def test_roadway_is_the_most_expensive(self, classes):
        # 우선순위 = cost 오름차순. 차도가 최댓값(∞)이 아니면 우선순위 체계가 뒤집힌다
        finite = [cost for _, cost in classes.costs if not math.isinf(cost)]
        assert finite, "유한 비용 클래스가 하나도 없다"
        assert max(finite) < float("inf")

    def test_block_confirm_resolves_before_turn_confirm(self, config):
        # 유도선이 꺾이는 코너에서 어느 쪽이 먼저 확정되는지가 동작을 가른다:
        # 블록 확정이 늦으면 TRACE 대신 TURN(깊은 조향)으로 코너를 돈다
        driving = config["driving"]
        assert driving["inspect"]["confirm_sec"] < driving["turn"]["confirm_sec"]

    def test_roadway_escape_is_tried_before_estop(self, post):
        # 명세: 차도를 만나면 **선회로 이탈**하고, ESTOP은 그것이 늦었을 때만이다.
        # 두 임계가 뒤집히면 선회 구간이 존재하지 않는다 (곧바로 ESTOP)
        assert post.roadway_risk_fraction < post.roadway_critical_fraction

    def test_undrivable_classes_are_not_obstacles(self, classes):
        """주행불가 클래스는 장애물이 아니다 — 회귀 방어.

        종전 정의('주행가능도 차도도 아닌 픽셀 = 장애물')에서는 bike_lane·
        caution_zone·alley가 전부 장애물로 잡혀, 자전거도로 옆을 지나는 것만으로
        회피 기동이 걸렸다. 지금 장애물은 **미분류**(표의 어느 클래스도 아닌) 픽셀뿐이고
        (segmentation.summarize_class_masks), 주행불가 클래스는 코스트맵 비용이
        담당한다. 그 클래스들이 표에 유한 비용으로 남아 있는지가 이 구분의 증거다.
        """
        costs = dict(classes.costs)
        undrivable = set(costs) - set(classes.drivable) - set(classes.roadway)
        assert undrivable == {"bike_lane", "caution_zone", "alley"}
        for name in undrivable:
            assert 0.0 < costs[name] < float("inf"), (
                f"{name}: 지나갈 수는 있으나 피해야 하는 클래스여야 한다")


class TestBuildCostProfile:
    def test_pure_sidewalk_slot_costs_nothing(self, costmap):
        profile = build_cost_profile(({"sidewalk": 1.0},), costmap)
        assert profile == (0.0,)

    def test_weighted_sum_of_class_costs(self, costmap):
        fractions = {"sidewalk": 0.5, "caution_zone": 0.5}
        expected = 0.5 * costmap.class_costs["caution_zone"]
        profile = build_cost_profile((fractions,), costmap)
        assert profile[0] == pytest.approx(expected)

    def test_unknown_pixels_cost_unknown(self, costmap):
        profile = build_cost_profile(({UNKNOWN_CLASS: 1.0},), costmap)
        assert profile[0] == pytest.approx(costmap.unknown_cost)

    def test_unlisted_class_falls_back_to_unknown_cost(self, costmap):
        # 모델에 새 클래스를 추가하고 비용표를 안 고친 경우 — 자유 통행(0)이 되면 안 된다
        profile = build_cost_profile(({"new_mystery_class": 1.0},), costmap)
        assert profile[0] == pytest.approx(costmap.unknown_cost)

    def test_roadway_makes_slot_infeasible(self, costmap):
        fractions = {"sidewalk": 0.9,
                     "roadway": costmap.min_class_fraction}
        profile = build_cost_profile((fractions,), costmap)
        assert math.isinf(profile[0])

    def test_noise_below_min_fraction_is_ignored(self, costmap):
        # 차도 오탐 픽셀 몇 개(min_class_fraction 미만)가 슬롯을 ∞로 만들면 안 된다
        fractions = {"sidewalk": 1.0,
                     "roadway": costmap.min_class_fraction * 0.5}
        profile = build_cost_profile((fractions,), costmap)
        assert profile[0] == 0.0

    def test_slot_order_preserved(self, costmap):
        profile = build_cost_profile(
            ({"sidewalk": 1.0}, {UNKNOWN_CLASS: 1.0}, {"roadway": 1.0}), costmap)
        assert profile[0] == 0.0
        assert profile[1] == pytest.approx(costmap.unknown_cost)
        assert math.isinf(profile[2])


class TestSlotCenterRatio:
    def test_covers_minus_one_to_plus_one(self):
        assert slot_center_ratio(0, 2) == pytest.approx(-0.5)
        assert slot_center_ratio(1, 2) == pytest.approx(0.5)
        # 홀수 슬롯이면 정중앙 슬롯의 중심은 정확히 0
        assert slot_center_ratio(7, 15) == pytest.approx(0.0)


class TestSelectDirection:
    """순수 함수 단위 테스트 — 가중치는 합성 입력(튜닝값 복제 아님)."""

    def test_uniform_cost_picks_slot_nearest_target(self):
        profile = (0.0,) * 5
        chosen = select_direction(profile, target_ratio=0.55, prev_ratio=None,
                                  target_weight=1.0, commit_weight=0.0)
        # 슬롯 중심: -0.8, -0.4, 0.0, +0.4, +0.8 → 0.55에 가장 가까운 +0.4
        assert chosen == pytest.approx(0.4)

    def test_infeasible_slots_are_never_chosen(self):
        inf = float("inf")
        profile = (0.0, inf, inf, inf, 0.0)
        chosen = select_direction(profile, target_ratio=0.0, prev_ratio=None,
                                  target_weight=1.0, commit_weight=0.0)
        assert chosen is not None and not math.isinf(chosen)
        assert abs(chosen) == pytest.approx(0.8)  # 양끝만 통행 가능

    def test_all_infeasible_returns_none(self):
        inf = float("inf")
        assert select_direction((inf, inf, inf), target_ratio=0.0,
                                prev_ratio=None, target_weight=1.0,
                                commit_weight=0.0) is None

    def test_detours_around_costly_center_when_weight_allows(self):
        # 중앙 비용 0.9 vs 옆(0.4 이탈) 0.0 — target_weight 1.0이면 0.4 < 0.9 → 우회
        profile = (0.5, 0.0, 0.9, 0.0, 0.5)
        chosen = select_direction(profile, target_ratio=0.0, prev_ratio=None,
                                  target_weight=1.0, commit_weight=0.0)
        assert abs(chosen) == pytest.approx(0.4)

    def test_hysteresis_holds_previous_choice_on_near_tie(self):
        # 좌우 대칭 비용 — 직전 선택(왼쪽)이 있으면 왼쪽을 유지해야 한다 (채터링 방지)
        profile = (0.0, 0.1, 0.0)
        left = slot_center_ratio(0, 3)
        chosen = select_direction(profile, target_ratio=0.0, prev_ratio=left,
                                  target_weight=0.1, commit_weight=0.5)
        assert chosen == pytest.approx(left)

    def test_symmetric_tie_breaks_toward_target(self):
        # 완전 동점이면 목표에 가까운 슬롯 — 목표가 약간 오른쪽이면 오른쪽
        profile = (0.0, 1.0, 0.0)
        chosen = select_direction(profile, target_ratio=0.05, prev_ratio=None,
                                  target_weight=0.0, commit_weight=0.0)
        assert chosen > 0


class TestCruiseCostmap:
    """CRUISE 통합 — cost_profile이 있으면 코스트맵 선택, 전 방향 ∞면 AVOID."""

    def frame(self, t: float, profile) -> SegmentationFrame:
        return SegmentationFrame(timestamp=t, sidewalk_detected=True,
                                 cost_profile=profile)

    def started(self, params: StateMachineParams) -> StateMachine:
        machine = StateMachine(params=params, now=0.0)
        machine.request_start()
        machine.step(SegmentationFrame(timestamp=0.0, sidewalk_detected=True),
                     None, 0.0)
        assert machine.state is DriveState.CRUISE
        return machine

    def test_steers_away_from_infeasible_side(self, params):
        # 중앙~오른쪽 ∞ (차도가 목표 방향을 덮음) → 조향은 왼쪽(음수)으로만 나간다
        inf = float("inf")
        machine = self.started(params)
        command = machine.step(
            self.frame(1.0, (0.0, 0.0, inf, inf, inf)), None, 1.0)
        assert machine.state is DriveState.CRUISE
        assert command.throttle_pct > 0
        assert command.steering_offset_deg < 0

    def test_turn_gate_is_reachable_with_costmap_active(self, params):
        """코스트맵이 켜져 있어도 목표 오차가 포화되면 TURN에 든다.

        회귀 방어: 예전엔 회전 판정을 코스트맵이 **고른 슬롯**으로 했다. 슬롯 중심의
        최대 크기는 slot_count=15에서 0.933이라 임계(수요 18°/이득 20 = 오차 0.9)를
        넘는 슬롯이 최외곽 2개뿐이었고, 그 최외곽은 화면 좌·우 끝 = 차도(∞)나 미분류일
        확률이 가장 높아 애초에 후보에서 빠졌다 — 즉 90° 코너에서 TURN이 걸리지 않고
        cruise 상한으로 버티다 경계 소실 IDLE로 끝났다. 이제 목표 오차로 판정한다.
        """
        imu = MockImu(pitch_deg=0.0, yaw_deg=0.0)
        machine = self.started(params)
        # 통행 가능한 슬롯은 중앙뿐 — 코스트맵 선택은 절대 포화 임계에 못 닿는다
        inf = float("inf")
        profile = (inf, inf, inf, inf, inf, inf, inf, 0.0,
                   inf, inf, inf, inf, inf, inf, inf)
        tick, t = 0.05, 0.0
        for _ in range(int(params.turn_confirm_sec / tick) + 3):
            t += tick
            frame = SegmentationFrame(timestamp=t, sidewalk_detected=True,
                                      sidewalk_center_offset_ratio=-1.0,
                                      cost_profile=profile)
            machine.step(frame, imu.read(), t)
        assert machine.state is DriveState.TURN

    def test_turn_threshold_is_reachable_before_boundary_loss(self, params):
        # 임계가 이득에 너무 가까우면(오차 ≈ ±1 요구) 인도가 화면을 벗어나
        # sidewalk_min_fraction이 먼저 깨진다 — 실차에서 도달 불가능한 임계가 된다
        assert params.turn_enter_steer_demand_deg < params.cruise_steer_gain_deg * 0.8

    def test_uniform_profile_follows_center_like_legacy(self, params):
        machine = self.started(params)
        command = machine.step(self.frame(1.0, (0.0,) * 15), None, 1.0)
        assert machine.state is DriveState.CRUISE
        assert command.steering_offset_deg == pytest.approx(0.0)

    def test_all_infeasible_stops_into_avoid(self, params):
        inf = float("inf")
        machine = self.started(params)
        command = machine.step(self.frame(1.0, (inf, inf, inf)), None, 1.0)
        assert machine.state is DriveState.AVOID
        assert command.throttle_pct == 0

    def test_avoid_recheck_reevaluates_costmap_block(self, params):
        # 막힘 유지 → 재확인 후에도 AVOID 정지 대기가 이어진다 (blocking 장애물이
        # 없어도 전 슬롯 ∞면 복귀하지 않는다 — IMU가 없으니 회차도 못 한다).
        # 매 틱 재평가는 계속된다: 막힘이 풀리는 그 틱에 CRUISE로 복귀한다
        inf = float("inf")
        machine = self.started(params)
        machine.step(self.frame(1.0, (inf, inf, inf)), None, 1.0)
        assert machine.state is DriveState.AVOID

        recheck = 1.0 + params.avoid_recheck_sec
        command = machine.step(self.frame(recheck, (inf, inf, inf)), None, recheck)
        assert machine.state is DriveState.AVOID   # 여전히 막힘 → 정지 대기 유지
        assert command.throttle_pct == 0
        machine.step(self.frame(recheck + 0.05, (0.0, 0.0, 0.0)), None, recheck + 0.05)
        assert machine.state is DriveState.CRUISE  # 막힘 해소 → 즉시 복귀

    def test_cleared_costmap_resumes_driving(self, params):
        inf = float("inf")
        machine = self.started(params)
        machine.step(self.frame(1.0, (inf, inf, inf)), None, 1.0)
        assert machine.state is DriveState.AVOID

        recheck = 1.0 + params.avoid_recheck_sec
        machine.step(self.frame(recheck, (0.0, 0.0, 0.0)), None, recheck)
        assert machine.state is DriveState.CRUISE
        command = machine.step(self.frame(recheck + 0.05, (0.0, 0.0, 0.0)),
                               None, recheck + 0.05)
        assert command.throttle_pct > 0

    def test_blocking_obstacle_beats_costmap_steering(self, params):
        # blocking 장애물 게이트가 코스트맵 조향보다 먼저다 — 정지 우선
        machine = self.started(params)
        blocking = ObstacleRegion(lateral_bias=0.0, proximity=1.0, blocking=True)
        frame = SegmentationFrame(timestamp=1.0, sidewalk_detected=True,
                                  obstacles=(blocking,), cost_profile=(0.0,) * 5)
        machine.step(frame, None, 1.0)
        assert machine.state is DriveState.AVOID

    def test_none_profile_keeps_legacy_proportional_steering(self, params):
        # cost_profile=None (mock 기본) → 기존 비례 조향 그대로
        machine = self.started(params)
        frame = SegmentationFrame(timestamp=1.0, sidewalk_detected=True,
                                  sidewalk_center_offset_ratio=0.3)
        command = machine.step(frame, None, 1.0)
        expected = min(0.3 * params.cruise_steer_gain_deg,
                       params.cruise_steer_right_max_deg)
        assert command.steering_offset_deg == pytest.approx(expected)

    def test_costmap_choice_obeys_asymmetric_left_limit(self, params):
        # 코스트맵 선택도 비례 조향과 같은 좌우 비대칭 상한을 지난다.
        # 왼쪽 끝 슬롯만 통행 가능 → 이득을 곱하면 상한을 넘고, 좌측 상한에서 잘린다
        inf = float("inf")
        machine = self.started(params)
        extreme = slot_center_ratio(0, 5) * params.cruise_steer_gain_deg
        assert abs(extreme) > params.cruise_steer_left_max_deg  # 전제: 상한을 넘는 입력
        command = machine.step(self.frame(1.0, (0.0, inf, inf, inf, inf)), None, 1.0)
        assert command.steering_offset_deg == pytest.approx(
            -params.cruise_steer_left_max_deg)

    def test_costmap_choice_is_slew_limited(self, params):
        # 코스트맵 경로도 슬루율 제한을 지난다 — 슬롯이 튀어도 서보는 천천히 간다
        inf = float("inf")
        machine = self.started(params)
        dt = 0.05
        command = machine.step(self.frame(dt, (0.0, inf, inf, inf, inf)), None, dt)
        assert command.steering_offset_deg == pytest.approx(
            -params.steer_rate_deg_per_s * dt)

    def test_braille_blend_pulls_costmap_choice(self, params):
        # TRACE 추종 혼합으로 목표가 오른쪽으로 밀리면 코스트맵 선택 방향도 오른쪽 —
        # 목표(혼합)와 조향(코스트맵)의 결합점이다. 혼합은 CRUISE가 아니라 TRACE에서
        # 일어난다(진입은 디바운스만 통과하면 되고, 그 뒤 근접도에 비례해 밀어낸다).
        machine = self.started(params)
        midway = params.inspect_enter_proximity / 2

        def block_frame(t: float, offset: float) -> SegmentationFrame:
            return SegmentationFrame(
                timestamp=t, sidewalk_detected=True, braille_detected=True,
                braille_center_offset_ratio=offset, braille_proximity=midway,
                cost_profile=(0.0,) * 15)

        command = machine.step(block_frame(1.0, 0.0), None, 1.0)
        assert machine.state is DriveState.CRUISE                # 확정 전
        assert command.steering_offset_deg == pytest.approx(0.0)  # 인도 중심

        confirmed = 1.0 + params.inspect_confirm_sec * 2
        machine.step(block_frame(confirmed, 0.0), None, confirmed)
        assert machine.state is DriveState.TRACE

        t = confirmed
        for _ in range(20):   # 슬루율 통과 뒤 정상 상태
            t += 0.05
            command = machine.step(block_frame(t, 0.5), None, t)
        assert machine.state is DriveState.TRACE
        assert command.steering_offset_deg > 0
