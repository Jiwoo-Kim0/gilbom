"""세그멘테이션 요약 순수 계층(build_frame) + mock 시나리오 매핑 테스트.

numpy 계층(summarize_class_masks)은 개발 PC에 numpy가 없어 보드에서 검증한다 —
여기서는 MaskSummary(스칼라)에 임계값을 적용하는 순수 계층만 다룬다.
임계값은 config.yaml perception.segmentation.post에서 읽는다 (숫자 하드코딩 금지).
"""

from dataclasses import replace

import pytest

from perception.mocks import ScriptedSegmentationProvider
from perception.segmentation import (MaskSummary, PostParams, build_frame,
                                     corridor_bounds, evaluate_centerline,
                                     fit_centerline, obstacle_band_y1)

T = 12.5  # 프레임 타임스탬프 — 판정에 영향 없음


def summary(**overrides) -> MaskSummary:
    """무해한 기본값(인도 잘 보임·위험 없음) 위에 덮어쓰기.

    obstacle_height_ratio는 1.0(세로로 꽉 찬 영역)이 기본값이다 — 장애물 테스트가
    보려는 것은 비율·근접도 임계이므로 세로 폭 조건은 통과시켜 둔다. 세로 폭 자체는
    TestObstacleGeometry가 따로 본다.
    """
    values = dict(
        drivable_fraction=0.5,
        drivable_center_offset_ratio=0.0,
        roadway_corridor_fraction=0.0,
        obstacle_corridor_fraction=0.0,
        obstacle_lateral_bias=0.0,
        obstacle_proximity=0.0,
        obstacle_height_ratio=1.0,
        braille_fraction=0.0,
        braille_center_offset_ratio=0.0,
        braille_proximity=0.0,
    )
    values.update(overrides)
    return MaskSummary(**values)


class TestSidewalk:
    def test_detected_above_threshold(self, post):
        frame = build_frame(summary(), post, T)
        assert frame.sidewalk_detected
        assert frame.timestamp == T

    def test_lost_below_threshold(self, post):
        frame = build_frame(
            summary(drivable_fraction=post.sidewalk_min_fraction * 0.5), post, T)
        assert not frame.sidewalk_detected

    def test_no_drivable_pixels_means_lost(self, post):
        # 픽셀이 하나도 없으면(centroid=None) 비율과 무관하게 소실 + 오차 0
        frame = build_frame(
            summary(drivable_center_offset_ratio=None), post, T)
        assert not frame.sidewalk_detected
        assert frame.sidewalk_center_offset_ratio == 0.0

    def test_center_offset_passthrough_and_clamp(self, post):
        assert build_frame(summary(drivable_center_offset_ratio=0.3),
                           post, T).sidewalk_center_offset_ratio == pytest.approx(0.3)
        assert build_frame(summary(drivable_center_offset_ratio=1.7),
                           post, T).sidewalk_center_offset_ratio == 1.0


def curve_samples(coeffs, count=12):
    """주어진 2차 곡선 위의 표본 count개 — y는 0(바로 앞)~1(멀리)에 균등."""
    return tuple((index / (count - 1), evaluate_centerline(coeffs, index / (count - 1)))
                 for index in range(count))


class TestCenterlineFit:
    """행별 중심 표본 → RANSAC 2차 중심선. 실패는 None으로 알리고 폴백시킨다."""

    CURVE = (0.1, 0.2, 0.4)   # x(y) = 0.1 + 0.2y + 0.4y²

    def test_straight_line_recovered(self, post):
        samples = curve_samples((0.2, 0.0, 0.0))
        fit = fit_centerline(samples, post.centerline)
        assert fit is not None
        assert evaluate_centerline(fit, 0.5) == pytest.approx(0.2, abs=1e-6)

    def test_curve_recovered(self, post):
        fit = fit_centerline(curve_samples(self.CURVE), post.centerline)
        assert fit is not None
        for y in (0.0, 0.5, 1.0):
            assert evaluate_centerline(fit, y) == pytest.approx(
                evaluate_centerline(self.CURVE, y), abs=1e-6)

    def test_outliers_rejected(self, post):
        # 12개 중 3개가 엉뚱한 가지(교차로·갈라진 인도)를 잡아도 본줄기를 복원한다
        samples = list(curve_samples(self.CURVE))
        for index in (2, 5, 9):
            samples[index] = (samples[index][0], -0.9)
        fit = fit_centerline(tuple(samples), post.centerline)
        assert fit is not None
        assert evaluate_centerline(fit, 0.5) == pytest.approx(
            evaluate_centerline(self.CURVE, 0.5), abs=1e-6)

    def test_too_few_samples_gives_none(self, post):
        samples = curve_samples(self.CURVE)[:post.centerline.min_samples - 1]
        assert fit_centerline(samples, post.centerline) is None

    def test_no_consensus_gives_none(self, post):
        # 좌우로 흩어져 합의가 안 되면 억지로 선을 내놓지 않는다
        scattered = tuple((index / 11, 0.9 if index % 2 else -0.9) for index in range(12))
        assert fit_centerline(scattered, post.centerline) is None

    def test_deterministic(self, post):
        samples = curve_samples(self.CURVE)
        assert (fit_centerline(samples, post.centerline)
                == fit_centerline(samples, post.centerline))


class TestCenterlineInFrame:
    """build_frame에서 중심선이 무게중심을 대체하고, 실패 시 강등되는지."""

    CURVE = (0.0, 0.3, 0.3)   # 오른쪽으로 굽는 길: 바로 앞은 0.0, 멀수록 +

    def test_centerline_beats_centroid(self, post):
        frame = build_frame(
            summary(drivable_center_offset_ratio=-0.9,   # 무게중심은 왼쪽으로 끌렸다
                    centerline_samples=curve_samples(self.CURVE)), post, T)
        assert frame.sidewalk_center_offset_ratio == pytest.approx(
            evaluate_centerline(self.CURVE, post.centerline.lookahead_ratio), abs=1e-6)

    def test_falls_back_to_centroid_without_samples(self, post):
        frame = build_frame(summary(drivable_center_offset_ratio=-0.9), post, T)
        assert frame.sidewalk_center_offset_ratio == pytest.approx(-0.9)

    def test_lookahead_reads_further_ahead(self, post):
        # 같은 곡선이라도 lookahead를 올리면 더 먼 지점(더 굽은 쪽)을 목표로 삼는다
        samples = curve_samples(self.CURVE)
        near = replace(post, centerline=replace(post.centerline, lookahead_ratio=0.0))
        far = replace(post, centerline=replace(post.centerline, lookahead_ratio=1.0))
        near_offset = build_frame(summary(centerline_samples=samples),
                                  near, T).sidewalk_center_offset_ratio
        far_offset = build_frame(summary(centerline_samples=samples),
                                 far, T).sidewalk_center_offset_ratio
        assert near_offset == pytest.approx(0.0, abs=1e-6)
        assert far_offset > near_offset

    def test_offset_stays_clamped(self, post):
        # 곡선이 화면 밖을 가리켜도 계약 범위(-1~+1)를 넘지 않는다
        frame = build_frame(
            summary(centerline_samples=curve_samples((0.9, 0.5, 0.5))), post, T)
        assert frame.sidewalk_center_offset_ratio == 1.0


class TestRoadwayRisk:
    def test_risk_at_threshold(self, post):
        frame = build_frame(
            summary(roadway_corridor_fraction=post.roadway_risk_fraction), post, T)
        assert frame.roadway_encroachment_risk

    def test_no_risk_below_threshold(self, post):
        frame = build_frame(
            summary(roadway_corridor_fraction=post.roadway_risk_fraction * 0.9),
            post, T)
        assert not frame.roadway_encroachment_risk


class TestObstacle:
    def test_below_fraction_reports_nothing(self, post):
        frame = build_frame(
            summary(obstacle_corridor_fraction=post.obstacle_min_fraction * 0.5,
                    obstacle_proximity=1.0), post, T)
        assert frame.obstacles == ()
        assert not frame.has_blocking_obstacle()

    def test_far_obstacle_reported_but_not_blocking(self, post):
        frame = build_frame(
            summary(obstacle_corridor_fraction=post.obstacle_min_fraction,
                    obstacle_lateral_bias=-0.4,
                    obstacle_proximity=post.obstacle_blocking_proximity * 0.5),
            post, T)
        assert len(frame.obstacles) == 1
        assert frame.obstacles[0].lateral_bias == pytest.approx(-0.4)
        assert not frame.has_blocking_obstacle()

    def test_near_obstacle_blocks(self, post):
        frame = build_frame(
            summary(obstacle_corridor_fraction=post.obstacle_min_fraction,
                    obstacle_proximity=post.obstacle_blocking_proximity),
            post, T)
        assert frame.has_blocking_obstacle()


class TestObstacleGeometry:
    """세로 폭 조건 — '미분류 픽셀'에서 노면 오분류 띠를 걸러내는 기하 게이트.

    비율만으로 판정하면 세그가 놓친 노면(얇고 넓은 띠)이 그대로 장애물이 되어
    실외에서 상시 회피가 걸린다. 지면에 선 물체는 화면에서 위아래로 뻗는다.
    """

    def near_summary(self, post, height_ratio: float):
        return summary(obstacle_corridor_fraction=post.obstacle_min_fraction,
                       obstacle_proximity=post.obstacle_blocking_proximity,
                       obstacle_height_ratio=height_ratio)

    def test_flat_band_is_not_an_obstacle(self, post):
        # 비율·근접도는 임계를 넘겼지만 세로로 얇다 → 노면 오분류로 보고 무시
        frame = build_frame(
            self.near_summary(post, post.obstacle_min_height_ratio * 0.5), post, T)
        assert frame.obstacles == ()
        assert not frame.has_blocking_obstacle()

    def test_tall_region_at_threshold_is_an_obstacle(self, post):
        frame = build_frame(
            self.near_summary(post, post.obstacle_min_height_ratio), post, T)
        assert frame.has_blocking_obstacle()

    def test_gate_is_a_live_knob(self, post):
        # 임계가 0이면 게이트가 죽어 종전(비율만) 동작으로 돌아간다 — 그건 설정 실수다
        assert 0.0 < post.obstacle_min_height_ratio < 1.0
        assert 0.0 <= post.obstacle_bottom_margin_ratio < 1.0


class TestObstacleBand:
    """장애물 판정 밴드 경계 — 관제 오버레이와 numpy 계층이 공유하는 유일한 산식."""

    SHAPE = (480, 640)

    def bounds(self, post):
        height, width = self.SHAPE
        _, _, y0 = corridor_bounds(height, width, post)
        return height, y0, obstacle_band_y1(height, y0, post)

    def test_band_excludes_bottom_margin(self, post):
        height, y0, y1 = self.bounds(post)
        assert y0 < y1 <= height
        excluded = (height - y1) / (height - y0)
        assert excluded == pytest.approx(post.obstacle_bottom_margin_ratio, abs=0.01)

    def test_band_never_collapses(self, post):
        # 여백이 1.0이어도 최소 한 행은 남긴다 — 빈 슬라이스는 통계가 아니라 버그다
        height, width = self.SHAPE
        _, _, y0 = corridor_bounds(height, width, post)
        full = replace(post, obstacle_bottom_margin_ratio=1.0)
        assert obstacle_band_y1(height, y0, full) == y0 + 1

    def test_zero_margin_keeps_whole_corridor(self, post):
        height, width = self.SHAPE
        _, _, y0 = corridor_bounds(height, width, post)
        none = replace(post, obstacle_bottom_margin_ratio=0.0)
        assert obstacle_band_y1(height, y0, none) == height


class TestBraille:
    def test_below_fraction_zeroes_fields(self, post):
        # 미발견이면 잔여 통계가 있어도 필드를 0으로 눌러 게이트 오작동을 막는다
        frame = build_frame(
            summary(braille_fraction=post.braille_min_fraction * 0.5,
                    braille_center_offset_ratio=0.9, braille_proximity=0.9),
            post, T)
        assert not frame.braille_detected
        assert frame.braille_center_offset_ratio == 0.0
        assert frame.braille_proximity == 0.0

    def test_detected_with_fields(self, post):
        frame = build_frame(
            summary(braille_fraction=post.braille_min_fraction,
                    braille_center_offset_ratio=0.25, braille_proximity=0.6),
            post, T)
        assert frame.braille_detected
        assert frame.braille_center_offset_ratio == pytest.approx(0.25)
        assert frame.braille_proximity == pytest.approx(0.6)


class TestMockBrailleMapping:
    """config mock_scenarios의 braille 절이 프레임 필드로 매핑되는지."""

    def test_braille_scenario_round_trip(self, config):
        scenario = config["mock_scenarios"]["braille_trace"]
        fake_now = [0.0]
        provider = ScriptedSegmentationProvider.from_config(
            scenario, clock=lambda: fake_now[0])

        frame = provider.get_latest()  # 0초 구간: 블록 없음
        assert frame is not None and not frame.braille_detected

        with_braille = [(i, s) for i, s in enumerate(scenario) if s.get("braille")]
        assert with_braille, "braille_trace 시나리오에 braille 구간이 없다"
        index, first = with_braille[0]
        # 해당 구간의 중간 시각으로 이동해 매핑 확인
        begin = scenario[index - 1]["until_sec"] if index else 0.0
        fake_now[0] = (begin + first["until_sec"]) / 2

        frame = provider.get_latest()
        assert frame is not None and frame.braille_detected
        assert frame.braille_center_offset_ratio == pytest.approx(
            first["braille"]["center_offset_ratio"])
        assert frame.braille_proximity == pytest.approx(
            first["braille"]["proximity"])


class TestPerClassConfidence:
    """클래스별 신뢰도 하한 — 점자블록은 재현율이, 차도는 정밀도가 미션이다.

    실측(2026-08-05, 실사 31장면 육안 라벨)이 이 분리의 근거다: 전역 conf 0.25에서
    점자블록 재현율이 8장면 중 **2장면**이고, 낮추면 오르지만(0.05에서 5/8) 같은
    손잡이가 차도·횡단보도까지 함께 내린다 — 그쪽 오탐은 코리도 20%면 선회,
    55%면 즉시 ESTOP 이라 미션을 끝낸다. 그래서 하한을 클래스마다 따로 든다.
    """

    def thresholds(self, seg) -> dict:
        return dict(seg.get("class_conf") or {})

    def test_config_lowers_only_the_braille_class(self, seg):
        overrides = self.thresholds(seg)
        assert seg["braille_class"] in overrides, "점자블록 하한이 없으면 재현율 문제가 그대로다"
        assert overrides[seg["braille_class"]] < seg["conf"]
        # 차도 계열은 낮추지 않는다 — 오탐의 대가가 선회·ESTOP 이다
        for name, policy in seg["classes"].items():
            if policy.get("roadway"):
                assert overrides.get(name, seg["conf"]) >= seg["conf"]

    def test_low_confidence_braille_survives(self, seg):
        from perception.segmentation import keep_instances

        overrides = self.thresholds(seg)
        braille, floor = seg["braille_class"], overrides[seg["braille_class"]]
        keep = keep_instances([braille, braille], [floor, floor / 2.0],
                              overrides, seg["conf"])
        assert keep == (True, False)          # 하한 이상만 살아남는다

    def test_other_classes_keep_the_default_floor(self, seg):
        from perception.segmentation import keep_instances

        overrides = self.thresholds(seg)
        just_under = float(seg["conf"]) - 0.01
        keep = keep_instances(["roadway", "roadway"], [seg["conf"], just_under],
                              overrides, seg["conf"])
        assert keep == (True, False)          # 표에 없는 클래스는 전역 conf 그대로
