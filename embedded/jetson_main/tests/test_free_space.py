"""자유공간 프로파일 순수 계층 — 원거리 장애물(보행자)이 잡히는지, 하늘이 안 잡히는지.

배경: 종전 장애물 판정은 화면 하단 60~90% 밴드 안에서만 돌았고, 카메라 0.3m·하향 10°
기준으로 그건 지면 0.56~1.14m뿐이었다. 4~5m 앞 보행자는 화면 y≈177에 찍혀 밴드
(y 288~)에 들어오지도 못했다 — 0803 실외 테스트에서 사람 다리·신발을 전혀 인지하지
못한 원인이다. 밴드를 넓히려면 정의를 바꿔야 한다: '밴드 안 미분류 픽셀을 센다'가
아니라 **'주행가능이 어디까지 이어지는가'**를 잰다.

이 파일은 그 판정을 numpy 없이 검증한다. numpy 계층은 열별 자유거리 프로파일
(슬롯당 실수 하나)만 내고, 구멍·회피 판정은 전부 순수 함수이므로 개발 PC에서
돈다 — 그것이 이 설계를 고른 이유이기도 하다.

임계값은 config.yaml perception.segmentation.free_space에서 읽는다 (하드코딩 금지).
"""

import pytest

from perception.segmentation import (CostmapParams, FreeSpaceParams, MaskSummary,
                                     PostParams, UNKNOWN_CLASS, build_frame,
                                     free_space_costs, free_space_notches,
                                     free_space_obstacle)

T = 7.5   # 프레임 타임스탬프 — 판정에 영향 없음
SLOTS = 15


def open_reaches(reach: float = 0.68, slots: int = SLOTS):
    """장애물 없는 인도 — 전 슬롯이 지평선까지 균등하게 열려 있다.

    0.68의 근거: 카메라 0.30m·하향 10°에서 지평선이 y≈138이고 판정 시작 행이 y=431이라
    (431-138)/432 ≈ 0.68이다. 절대값 자체는 판정에 쓰이지 않는다(노치는 상대 기준) —
    blocking의 절대 임계에만 쓰인다.
    """
    return [reach] * slots


def with_person(reaches, slot: int, reach: float, width: int = 1):
    """지정 슬롯의 자유거리를 짧게 만든다 = 그 방향에 물체가 서 있다."""
    out = list(reaches)
    for i in range(slot, min(len(out), slot + width)):
        out[i] = reach
    return out


class TestPedestrianAtRange:
    """4~5m 보행자가 실제로 잡히는가 — 이 변경의 존재 이유."""

    # 카메라 0.30m·하향 10°, 수직 FOV 45°(f≈580px) 기하:
    #   지평선 y≈138, 4.5m 사람의 발 y≈177, 판정 시작 y=431
    #   열린 열   (431-138)/432 = 0.678
    #   사람의 열 (431-177)/432 = 0.588   → 노치 (0.678-0.588)/0.678 ≈ 0.133
    OPEN = 0.678
    PERSON_45M = 0.588

    def test_notch_depth_matches_geometry(self, fs):
        reaches = with_person(open_reaches(self.OPEN), slot=7, reach=self.PERSON_45M)
        notches = free_space_notches(reaches)
        assert notches[7] == pytest.approx(0.133, abs=0.005)
        assert notches[0] == 0.0            # 열린 슬롯은 노치 0
        # 설정 임계가 이 기하를 실제로 잡는지 — 못 잡으면 4.5m 보행자를 다시 놓친다
        assert notches[7] >= fs.notch_min_ratio

    def test_pedestrian_becomes_obstacle(self, fs):
        reaches = with_person(open_reaches(self.OPEN), slot=7, reach=self.PERSON_45M)
        far = free_space_obstacle(reaches, fs)
        assert far is not None
        assert far.proximity == pytest.approx(1.0 - self.PERSON_45M, abs=1e-6)
        # 4.5m는 '비껴갈 대상'이지 '정지할 대상'이 아니다 — 절대 기준을 넘지 않는다
        assert not far.blocking

    def test_pedestrian_adds_cost_to_its_slot_only(self, fs):
        reaches = with_person(open_reaches(self.OPEN), slot=7, reach=self.PERSON_45M)
        costs = free_space_costs(reaches, fs)
        assert costs[7] > 0.0
        assert all(c == 0.0 for i, c in enumerate(costs) if i != 7)

    def test_cost_outweighs_target_pull(self, config, fs):
        """사람 슬롯의 비용이 목표 이탈 페널티보다 커야 실제로 비껴간다.

        코스트맵 방향 선택의 점수는 (통행 비용 + target_weight × 목표 이탈)이다.
        사람이 정확히 목표 방향에 서 있으면 그쪽 이탈은 0이므로, 비용이 이웃 슬롯의
        이탈 페널티보다 크지 않으면 사람 쪽을 계속 고른다.
        """
        from driving.steering_costmap import select_direction
        reaches = with_person(open_reaches(self.OPEN), slot=7, reach=self.PERSON_45M)
        profile = tuple(free_space_costs(reaches, fs))
        target = 0.0        # 인도 중심 = 정중앙 = 사람이 서 있는 슬롯
        chosen = select_direction(
            profile, target, None,
            float(config["driving"]["cruise"]["costmap"]["target_weight"]),
            float(config["driving"]["cruise"]["costmap"]["commit_weight"]))
        assert chosen is not None
        assert chosen != pytest.approx(0.0)     # 정중앙을 고르지 않았다 = 비껴갔다

    def test_two_slot_wide_person_still_detected(self, fs):
        # 겉보기 폭 39px vs 슬롯 폭 43px이라 두 슬롯에 걸치는 것이 정상이다
        reaches = with_person(open_reaches(self.OPEN), slot=7,
                              reach=self.PERSON_45M, width=2)
        far = free_space_obstacle(reaches, fs)
        assert far is not None
        costs = free_space_costs(reaches, fs)
        assert costs[7] > 0.0 and costs[8] > 0.0


class TestSkyIsNotAnObstacle:
    """정의를 바꾼 이유 — 밴드를 넓혀도 하늘·건물이 장애물이 되지 않아야 한다."""

    def test_open_sidewalk_reports_nothing(self, fs):
        """전 슬롯이 균등하게 열려 있으면 보고가 없다.

        화면 상단이 전부 미분류(하늘)여도 마찬가지다 — 자유거리는 '주행가능이 이어지는
        길이'이므로 그 위쪽 픽셀의 정체를 아예 묻지 않는다. 종전 정의('밴드 안 미분류
        비율')로 밴드를 위로 넓혔다면 이 장면이 100% 장애물이 됐다.
        """
        reaches = open_reaches()
        assert free_space_obstacle(reaches, fs) is None
        assert all(c == 0.0 for c in free_space_costs(reaches, fs))

    def test_uniformly_short_reach_is_not_a_notch(self, fs):
        """전 슬롯이 낮게 열려도(코너·오르막·짧은 인도) 노치는 없다.

        노치가 절대 기준이면 코너마다 전 방향이 장애물이 된다. 상대 기준이라
        '옆이 더 열려 있는가'만 묻는다.
        """
        reaches = open_reaches(0.25)
        assert all(n == 0.0 for n in free_space_notches(reaches))
        assert free_space_obstacle(reaches, fs) is None

    def test_gentle_slope_across_slots_is_ignored(self, fs):
        # 인도가 한쪽으로 기울어 자유거리가 완만히 변하는 것은 물체가 아니다
        reaches = [0.68 - 0.002 * i for i in range(SLOTS)]
        assert free_space_obstacle(reaches, fs) is None


class TestBlockingIsAbsolute:
    """정지·회피 기동은 '옆이 더 열렸다'가 아니라 '바로 앞이 막혔다'로만 간다."""

    def test_near_obstacle_blocks(self, fs):
        reaches = with_person(open_reaches(), slot=7,
                              reach=fs.blocking_reach_ratio / 2)
        far = free_space_obstacle(reaches, fs)
        assert far is not None and far.blocking

    def test_boundary_is_exclusive(self, fs):
        # reach == blocking_reach_ratio 는 blocking 아님 (미만 조건)
        reaches = with_person(open_reaches(), slot=7, reach=fs.blocking_reach_ratio)
        far = free_space_obstacle(reaches, fs)
        assert far is not None and not far.blocking

    def test_all_blocked_reports_blocking(self, fs):
        # 가로 담장·벽: 전 슬롯이 즉시 막힌다. 상대 노치는 0이지만 절대 판정이 잡는다
        reaches = [0.0] * SLOTS
        far = free_space_obstacle(reaches, fs)
        assert far is not None and far.blocking


class TestBlockingRequiresPathRelevance:
    """blocking은 '뭔가 막혀 있다'가 아니라 **'내 길이 막혔다'** 여야 한다.

    회귀 방어: 이 조건이 없으면 인도 오른쪽 끝의 화단·담장이 항상 자유거리를 끊으므로
    ① 평범한 인도 구간에서 상시 회피 기동이 돌고, ② 선회로 비켜난 뒤에도 그 화단이
    계속 잡혀 회피가 **끝나지 않는다**(국면 상한이 유일한 출구가 된다).
    경로 폭은 새 임계값이 아니라 진행 코리도(post.corridor_width_ratio)를 그대로 쓴다.
    """

    def test_edge_hedge_is_not_blocking(self, fs, post):
        # 화면 맨 끝 슬롯이 막혀 있어도 진행 경로 밖이면 회피 기동 대상이 아니다
        reaches = with_person(open_reaches(), slot=SLOTS - 1, reach=0.0)
        far = free_space_obstacle(reaches, fs,
                                  path_width_ratio=post.corridor_width_ratio)
        assert far is not None
        assert not far.blocking

    def test_center_object_is_blocking(self, fs, post):
        reaches = with_person(open_reaches(), slot=SLOTS // 2, reach=0.0)
        far = free_space_obstacle(reaches, fs,
                                  path_width_ratio=post.corridor_width_ratio)
        assert far is not None and far.blocking

    def test_edge_hedge_still_costs_steering(self, fs):
        # 경로 밖 노치도 **비용**은 낸다 — 상태 전이 없이 부드럽게 비껴가는 것은 옳다.
        # 경로 관련성은 blocking(기동)에만 걸린다
        reaches = with_person(open_reaches(), slot=SLOTS - 1, reach=0.0)
        costs = free_space_costs(reaches, fs)
        assert costs[SLOTS - 1] > 0.0
        assert costs[SLOTS // 2] == 0.0

    def test_wall_across_everything_is_still_blocking(self, fs, post):
        # 전 방향이 막히면 경로도 막힌 것이다 — 경로 조건이 이 경우를 놓치면 안 된다
        far = free_space_obstacle([0.0] * SLOTS, fs,
                                  path_width_ratio=post.corridor_width_ratio)
        assert far is not None and far.blocking

    def test_single_slot_profile_does_not_crash(self, fs, post):
        # 슬롯 폭 계산은 (n-1)로 나눈다 — n=1이면 0으로 나눈다. 현 config는 15지만
        # 순수 함수가 입력 하나로 죽는 것은 계약 위반이다
        far = free_space_obstacle([0.0], fs,
                                  path_width_ratio=post.corridor_width_ratio)
        assert far is not None and far.blocking


class TestLateralBias:
    """회피 방향은 노치의 반대쪽 — 치우침 부호가 맞아야 한다."""

    def test_left_object_biases_left(self, fs):
        far = free_space_obstacle(with_person(open_reaches(), 1, 0.2), fs)
        assert far is not None and far.lateral_bias < 0

    def test_right_object_biases_right(self, fs):
        far = free_space_obstacle(with_person(open_reaches(), SLOTS - 2, 0.2), fs)
        assert far is not None and far.lateral_bias > 0

    def test_center_object_is_centered(self, fs):
        far = free_space_obstacle(with_person(open_reaches(), SLOTS // 2, 0.2), fs)
        assert far is not None
        assert far.lateral_bias == pytest.approx(0.0, abs=1e-9)

    def test_deepest_run_wins_over_widest(self, fs):
        """넓고 얕은 노면 오분류보다 좁고 깊은 실물이 회피 대상이다."""
        reaches = open_reaches()
        for i in (0, 1, 2, 3, 4):
            reaches[i] = 0.60          # 넓고 얕은 노치 (좌측)
        reaches[12] = 0.15             # 좁고 깊은 노치 (우측) — 실물
        far = free_space_obstacle(reaches, fs)
        assert far is not None
        assert far.lateral_bias > 0    # 깊은 쪽(우측)을 골랐다
        assert far.blocking


class TestContiguousRunSemantics:
    """numpy 계층이 '연속' 구간을 세야 하는 이유 — 순수 파이썬 재현으로 고정한다.

    numpy 계층 자체(`_free_reach_ratios`)는 개발 PC에 numpy가 없어 보드에서 검증한다.
    다만 그 함수의 **의미론**은 여기서 못 박아 둘 수 있고, 그중 하나가 특히 틀리기 쉽다:
    물체 위·뒤로 보이는 인도 픽셀을 세면 안 된다. 4.5m 사람의 머리 위에는 원거리 인도가
    그대로 보이므로, 열별로 단순히 주행가능 픽셀을 **합계**하면 그 열은 이웃과 거의 같은
    값이 나오고 노치가 사라진다. 그래서 최하단부터 **끊기지 않고 이어지는** 길이만 센다
    (numpy 구현은 누적 AND).
    """

    def contiguous_from_bottom(self, column, scan_from: int) -> int:
        """`np.logical_and.accumulate(...).sum()`의 순수 파이썬 등가물."""
        count = 0
        for row in range(scan_from, -1, -1):
            if not column[row]:
                break
            count += 1
        return count

    def test_pixels_above_object_are_not_counted(self):
        # 아래 6칸 인도 → 물체 2칸 → 위 4칸 인도(원거리). 이어지는 것은 아래 6칸뿐이다
        column = [True] * 4 + [False] * 2 + [True] * 6
        scan_from = len(column) - 1
        assert self.contiguous_from_bottom(column, scan_from) == 6
        assert sum(column) == 10          # 단순 합계면 10 — 노치가 사라진다

    def test_fully_open_column(self):
        column = [True] * 12
        assert self.contiguous_from_bottom(column, 11) == 12

    def test_blocked_at_the_start_row(self):
        # 판정 시작 행이 이미 막혀 있으면 0 (자유거리 없음)
        column = [True] * 11 + [False]
        assert self.contiguous_from_bottom(column, 11) == 0

    def test_low_object_severity_survives_only_with_contiguity(self, fs):
        """위로 인도가 보이는 **낮은** 물체에서 두 방식이 갈린다.

        키 큰 보행자는 발끝부터 지평선 위까지 한 번에 가리므로 그 열에는 '물체 위의
        인도'가 애초에 없다 — 두 방식이 같은 값을 낸다. 갈리는 것은 가방·낮은 볼라드·
        개처럼 **상단이 지평선 아래에서 끝나는** 물체다: 그 위로 원거리 인도가 계속
        보이므로, 단순 합계는 그 픽셀을 함께 세어 '아직 꽤 열려 있다'고 보고한다.

        장면: 지평선 y=137, 판정 시작 y=431, 약 1m 앞 낮은 물체가 y 221~308을 가린다.
          연속:   431→309 = 123행 → 0.285  → blocking (바로 앞이 막혔다)
          합계:   123 + (221~136 = 84행)   → 0.479  → blocking 아님
        즉 단순 합계면 **정지해야 할 상황을 '비껴가면 된다'로 과소평가한다.**
        """
        scan_from = 431
        open_col = [False] * 137 + [True] * (480 - 137)
        low_object = list(open_col)
        for row in range(221, 309):
            low_object[row] = False

        contiguous = self.contiguous_from_bottom(low_object, scan_from) / (scan_from + 1)
        naive = sum(low_object[:scan_from + 1]) / (scan_from + 1)

        assert naive > contiguous                             # 합계가 과대평가한다
        assert contiguous < fs.blocking_reach_ratio           # 연속: 막힘으로 본다
        assert naive >= fs.blocking_reach_ratio               # 합계: 놓친다

    def test_tall_object_agrees_either_way(self, fs):
        # 대조: 키 큰 보행자는 지평선 위까지 가리므로 두 방식이 같다 (위 테스트의 전제)
        scan_from = 431
        open_col = [False] * 137 + [True] * (480 - 137)
        person = list(open_col)
        for row in range(60, 178):
            person[row] = False
        assert (self.contiguous_from_bottom(person, scan_from)
                == sum(person[:scan_from + 1]))


class TestFrameIntegration:
    """build_frame 결합 — 코스트맵 가산과 obstacles 추가, 그리고 폴백 보존."""

    def base(self, **overrides) -> MaskSummary:
        values = dict(
            drivable_fraction=0.5, drivable_center_offset_ratio=0.0,
            roadway_corridor_fraction=0.0, obstacle_corridor_fraction=0.0,
            obstacle_lateral_bias=0.0, obstacle_proximity=0.0,
            obstacle_height_ratio=1.0, braille_fraction=0.0,
            braille_center_offset_ratio=0.0, braille_proximity=0.0,
            slot_class_fractions=tuple({"sidewalk": 1.0, UNKNOWN_CLASS: 0.0}
                                       for _ in range(SLOTS)),
        )
        values.update(overrides)
        return MaskSummary(**values)

    def test_cost_is_added_to_semantic_costmap(self, post, costmap, fs):
        reaches = with_person(open_reaches(0.678), 7, 0.588)
        frame = build_frame(self.base(free_reach_ratios=tuple(reaches)),
                           post, T, costmap=costmap, free_space=fs)
        assert frame.cost_profile is not None
        # 시맨틱 비용(sidewalk=0.0)에 자유공간 비용만 얹혔다
        assert frame.cost_profile[7] > frame.cost_profile[0]

    def test_far_obstacle_appended(self, post, costmap, fs):
        reaches = with_person(open_reaches(), 7, 0.1)
        frame = build_frame(self.base(free_reach_ratios=tuple(reaches)),
                           post, T, costmap=costmap, free_space=fs)
        assert len(frame.obstacles) == 1
        assert frame.has_blocking_obstacle()

    def test_near_band_obstacle_is_preserved(self, post, costmap, fs):
        """근거리 밴드 판정을 대체하지 않는다 — 둘의 유효 범위가 다르다."""
        summary = self.base(
            obstacle_corridor_fraction=post.obstacle_min_fraction + 0.05,
            obstacle_proximity=post.obstacle_blocking_proximity + 0.1,
            free_reach_ratios=tuple(with_person(open_reaches(), 7, 0.1)))
        frame = build_frame(summary, post, T, costmap=costmap, free_space=fs)
        assert len(frame.obstacles) == 2       # 밴드 1개 + 자유공간 1개
        assert frame.has_blocking_obstacle()

    def test_disabled_falls_back(self, post, costmap, fs):
        """enabled=false면 종전 동작 그대로 — 폴백 경로가 살아 있어야 한다."""
        from dataclasses import replace
        off = replace(fs, enabled=False)
        reaches = with_person(open_reaches(), 7, 0.1)
        frame = build_frame(self.base(free_reach_ratios=tuple(reaches)),
                           post, T, costmap=costmap, free_space=off)
        assert frame.obstacles == ()
        assert frame.cost_profile is not None            # 시맨틱 코스트맵은 그대로
        assert all(c == frame.cost_profile[0] for c in frame.cost_profile)

    def test_absent_profile_falls_back(self, post, costmap, fs):
        # mock 인지처럼 프로파일이 없는 프레임 — 종전 경로만 돈다
        frame = build_frame(self.base(), post, T, costmap=costmap, free_space=fs)
        assert frame.obstacles == ()

    def test_slot_count_mismatch_skips_cost_merge(self, post, costmap, fs):
        """슬롯 수가 어긋나면 비용 가산을 건너뛴다 — 조용히 잘못 더하지 않는다.

        (설정상 같은 slot_count를 쓰므로 일어나지 않아야 하지만, 어긋났을 때
         원소별 zip이 짧은 쪽에서 잘려 프로파일이 조용히 짧아지는 것을 막는다.)
        """
        reaches = tuple(with_person(open_reaches(slots=SLOTS - 3), 5, 0.1))
        frame = build_frame(self.base(free_reach_ratios=reaches),
                           post, T, costmap=costmap, free_space=fs)
        assert frame.cost_profile is not None
        assert len(frame.cost_profile) == SLOTS          # 잘리지 않았다
        assert len(frame.obstacles) == 1                 # 장애물 보고는 그대로 산다

    # ---- '갈 수 없다'(막다른 길) 판정은 근거리 확증을 함께 요구한다 ----
    #
    # 자유거리가 짧아지는 이유는 물체만이 아니다: 오르막·크레스트·코너에서는 지면이
    # 시야에서 먼저 끝난다. 그 장면은 전 슬롯이 **균등하게** 짧아지므로 상대 노치로는
    # 안 잡히지만, 절대 기준을 전 슬롯에 적용하는 all_directions_blocked 에는 그대로
    # 걸린다 — 그리고 그 판정의 결과는 정지 → 재확인 → **후진 + 재정향**이다.
    # 인도 한복판에서 후진하는 그 기동이 '경사면에서 쓸데없이 멈춘다'의 최종 형태다.

    def blocked_everywhere(self, **overrides):
        return self.base(free_reach_ratios=tuple(open_reaches(0.0)), **overrides)

    def test_crest_alone_is_not_a_dead_end(self, post, costmap, fs):
        """전 슬롯이 균등하게 짧아도 바로 앞 노면이 멀쩡하면 막다른 길이 아니다."""
        reaches = open_reaches(fs.blocking_reach_ratio - 0.05)
        frame = build_frame(self.base(free_reach_ratios=tuple(reaches)),
                            post, T, costmap=costmap, free_space=fs)
        assert not frame.all_directions_blocked
        assert not frame.has_blocking_obstacle()     # 균등하면 노치도 없다

    def test_wall_with_near_evidence_is_a_dead_end(self, post, costmap, fs):
        """벽·담장은 다가서면 근거리 밴드를 채운다 — 그때는 그대로 막다른 길이다."""
        summary = self.blocked_everywhere(
            obstacle_corridor_fraction=post.obstacle_min_fraction + 0.05,
            obstacle_proximity=post.obstacle_blocking_proximity + 0.1)
        frame = build_frame(summary, post, T, costmap=costmap, free_space=fs)
        assert frame.all_directions_blocked
        assert frame.has_blocking_obstacle()

    def test_far_wall_waits_for_the_near_band(self, post, costmap, fs):
        """아직 근거리 밴드에 안 들어온 벽은 '막힘'으로 단정하지 않는다.

        놓치는 것이 아니라 미루는 것이다: 다가가면 밴드에 들어오고(위 테스트),
        그 사이에도 자유거리 노치가 코스트맵 비용으로 조향을 비껴 준다.
        """
        frame = build_frame(self.blocked_everywhere(), post, T,
                            costmap=costmap, free_space=fs)
        assert not frame.all_directions_blocked
        assert frame.has_blocking_obstacle()   # 회피 기동은 그대로 걸린다


class TestBlockedByWhatExactly:
    """같은 '짧은 자유거리'라도 **원인**이 다르면 대응이 달라야 한다.

    자유거리는 주행가능이 어디까지 이어지는지만 잰다. 그것이 짧아지는 이유는 둘이고
    (뭔가가 막았다 / 인도가 거기서 끝났다) 종전에는 구분이 없었다 — 그래서 횡단보도
    연석이 정면을 가로막은 물체와 똑같이 회피 기동을 유발했다.
    실측 2026-08-05(실사 38장): blocking 22건 중 6건이 crosswalk·roadway 에서 끊긴
    것이었고, 전부 횡단보도 접근 프레임이다 — 미설치 판정을 하러 가야 하는 자리다.
    """

    def objects(self, slot=None, ratio=1.0, width=1):
        out = [0.0] * SLOTS
        if slot is not None:
            for i in range(slot, min(SLOTS, slot + width)):
                out[i] = ratio
        return out

    def test_object_terminator_blocks(self, fs):
        far = free_space_obstacle(with_person(open_reaches(), 7, 0.1), fs,
                                  object_ratios=self.objects(7))
        assert far is not None and far.blocking

    def test_curb_terminator_does_not_block(self, fs):
        """인도가 끝난 것은 막힌 것이 아니다 — 차도 방어는 코리도 비율이 따로 한다."""
        far = free_space_obstacle(with_person(open_reaches(), 7, 0.1), fs,
                                  object_ratios=self.objects())
        assert far is not None            # 보고는 남는다(비용·치우침은 유효하다)
        assert not far.blocking           # 회피 기동만 걸지 않는다

    def test_object_standing_on_the_curb_still_blocks(self, fs):
        """연석에 사람이 서 있으면 이웃이 전부 연석이어도 그 사람은 피해야 한다.

        구간 평균을 쓰면 바로 이 경우가 지워진다 — 그래서 최댓값으로 본다.
        """
        reaches = with_person(open_reaches(), 6, 0.1, width=3)
        objects = self.objects(7)         # 가운데 한 슬롯만 물체
        far = free_space_obstacle(reaches, fs, object_ratios=objects)
        assert far is not None and far.blocking

    def test_threshold_is_inclusive(self, fs):
        far = free_space_obstacle(
            with_person(open_reaches(), 7, 0.1), fs,
            object_ratios=self.objects(7, ratio=fs.blocking_object_min_ratio))
        assert far is not None and far.blocking

    def test_absent_profile_keeps_the_old_behaviour(self, fs):
        """원인을 재지 않는 경로(mock 인지)는 종전 그대로 — 조용히 바뀌지 않는다."""
        far = free_space_obstacle(with_person(open_reaches(), 7, 0.1), fs)
        assert far is not None and far.blocking


class TestTerminatorInFrame:
    """build_frame 결합 — 종결자 프로파일이 실제로 기동 판정까지 닿는가."""

    def base(self, **overrides) -> MaskSummary:
        values = dict(
            drivable_fraction=0.5, drivable_center_offset_ratio=0.0,
            roadway_corridor_fraction=0.0, obstacle_corridor_fraction=0.0,
            obstacle_lateral_bias=0.0, obstacle_proximity=0.0,
            obstacle_height_ratio=1.0, braille_fraction=0.0,
            braille_center_offset_ratio=0.0, braille_proximity=0.0,
            slot_class_fractions=tuple({"sidewalk": 1.0, UNKNOWN_CLASS: 0.0}
                                       for _ in range(SLOTS)),
            free_reach_ratios=tuple(with_person(open_reaches(), 7, 0.1)),
        )
        values.update(overrides)
        return MaskSummary(**values)

    def test_crosswalk_edge_does_not_trigger_evasion(self, post, costmap, fs):
        frame = build_frame(self.base(free_block_object_ratios=(0.0,) * SLOTS),
                            post, T, costmap=costmap, free_space=fs)
        assert not frame.has_blocking_obstacle()
        # 비용은 그대로 얹힌다 — 연석 쪽으로 파고들지는 않는다
        assert frame.cost_profile[7] > frame.cost_profile[0]

    def test_real_object_still_triggers_evasion(self, post, costmap, fs):
        objects = [0.0] * SLOTS
        objects[7] = 1.0
        frame = build_frame(self.base(free_block_object_ratios=tuple(objects)),
                            post, T, costmap=costmap, free_space=fs)
        assert frame.has_blocking_obstacle()
