"""빈 프레임 — 세그가 아무것도 못 잡은 틱을 '앞이 막혔다'로 읽지 않는다.

이 파일이 지키는 사실은 하나다: **'못 봤다'와 '막혔다'는 다르다.**

미분류 픽셀이 곧 장애물이라는 정의(_class_unions) 위에서는 마스크가 하나도 없는
프레임이 자동으로 '화면 전체가 장애물 + 막다른 길'이 된다. 2026-08-05 실기에서
주행 틱의 22.8%가 그런 프레임이었고, 그 한 틱들이 AVOID 를 SWERVE↔PASS 로 왕복시켜
20회 중 13회가 회피 상한 6초를 다 쓰게 만들었다 — 그리고 그 뒤 후진 8회가
'전 방향 막힘 → 수동 대기'로 순찰을 끝냈다. 근거·수치는
perception/segmentation.py BlankFrameGate 독스트링에 있다.

그렇다고 무조건 무시할 수는 없다. 벽에 코를 붙이면 정말로 분류할 것이 없을 수 있고
그때는 같은 신호가 참이다. 둘을 가르는 것은 지속 시간이다 — 그래서 여기서는
'짧은 빈 프레임은 빼고, 이어지면 통과시킨다'를 양쪽 다 검증한다.
"""

import pytest

from perception.segmentation import (BlankFrameGate, MaskSummary, UNKNOWN_CLASS,
                                     build_frame, is_blank)

T = 3.0
SLOTS = 15


def blank_summary(**overrides) -> MaskSummary:
    """세그가 아무것도 못 잡은 틱 — 실기 로그에 찍히는 그 모양 그대로.

    known_fraction=0(아무 클래스도 없음) → 코리도 전체가 미분류라 근거리 장애물이
    잡히고, 자유거리는 전 슬롯 0이라 원거리 장애물·막다른 길도 함께 선다.
    """
    values = dict(
        drivable_fraction=0.0, drivable_center_offset_ratio=None,
        roadway_corridor_fraction=0.0,
        obstacle_corridor_fraction=1.0, obstacle_lateral_bias=0.0,
        obstacle_proximity=1.0, obstacle_height_ratio=1.0,
        braille_fraction=0.0, braille_center_offset_ratio=0.0, braille_proximity=0.0,
        slot_class_fractions=tuple({UNKNOWN_CLASS: 1.0} for _ in range(SLOTS)),
        free_reach_ratios=(0.0,) * SLOTS,
        free_block_object_ratios=(1.0,) * SLOTS,
        known_fraction=0.0,
    )
    values.update(overrides)
    return MaskSummary(**values)


def scene_summary(**overrides) -> MaskSummary:
    """정상 장면 — 인도가 보이고 정면이 트여 있다."""
    values = dict(
        drivable_fraction=0.5, drivable_center_offset_ratio=0.0,
        obstacle_corridor_fraction=0.0, obstacle_proximity=0.0,
        slot_class_fractions=tuple({"sidewalk": 1.0, UNKNOWN_CLASS: 0.0}
                                   for _ in range(SLOTS)),
        free_reach_ratios=(0.68,) * SLOTS, free_block_object_ratios=(0.0,) * SLOTS,
        known_fraction=0.9)
    values.update(overrides)
    return blank_summary(**values)


class TestWhatCountsAsBlank:
    def test_no_masks_at_all_is_blank(self, post):
        assert is_blank(blank_summary(), post) is True

    def test_a_real_scene_is_not(self, post):
        assert is_blank(scene_summary(), post) is False

    def test_a_sliver_of_classified_pixels_is_not(self, post):
        """임계는 '거의 아무것도 없다'의 여유값이지 튜닝 노브가 아니다."""
        assert is_blank(scene_summary(known_fraction=0.05), post) is False


class TestUnconfirmedBlankIsNotAnObstacle:
    """확정 전의 빈 프레임은 회피·재정향의 근거가 되지 않는다."""

    def test_no_obstacle_and_no_dead_end(self, post, costmap, fs):
        frame = build_frame(blank_summary(), post, T, costmap=costmap, free_space=fs,
                            blocking_confirmed=False)
        assert frame.has_blocking_obstacle() is False
        assert frame.all_directions_blocked is False
        assert frame.perception_blank is True

    def test_the_honest_fields_are_untouched(self, post, costmap, fs):
        """인도 미검출은 빈 프레임의 **정직한** 결과다 — 지우지 않는다.

        FSM 은 이것을 인지 소실로 이미 올바르게 처리한다(유예창 안의 저속 복구
        주행). 여기서 sidewalk_detected 까지 손대면 '보이지도 않는데 보인다'가 되고,
        그 유예창이 사라져 정말로 눈이 먼 구간을 순찰 속도로 달리게 된다.
        """
        frame = build_frame(blank_summary(), post, T, costmap=costmap, free_space=fs,
                            blocking_confirmed=False)
        assert frame.sidewalk_detected is False

    def test_a_real_scene_is_unaffected_by_the_flag(self, post, costmap, fs):
        """빈 프레임이 아니면 이 플래그는 아무것도 바꾸지 않는다."""
        summary = scene_summary(free_reach_ratios=(0.68,) * 7 + (0.05,) + (0.68,) * 7,
                                free_block_object_ratios=(0.0,) * 7 + (1.0,)
                                + (0.0,) * 7)
        blocked = build_frame(summary, post, T, costmap=costmap, free_space=fs,
                              blocking_confirmed=False)
        assert blocked.has_blocking_obstacle() is True
        assert blocked.perception_blank is False


class TestConfirmedBlankStillBlocks:
    """빈 상태가 이어지면 종전대로 통과시킨다 — 판단이 사라지는 것이 아니라 늦어진다."""

    def test_obstacle_and_dead_end_come_back(self, post, costmap, fs):
        frame = build_frame(blank_summary(), post, T, costmap=costmap, free_space=fs,
                            blocking_confirmed=True)
        assert frame.has_blocking_obstacle() is True
        assert frame.all_directions_blocked is True


class TestTheGate:
    """확정 시간을 재는 쪽 — Provider 가 소유하는 상태."""

    def test_a_scene_confirms_immediately(self, post):
        gate = BlankFrameGate()
        assert gate.confirmed(scene_summary(), post, 0.0) is True

    def test_a_short_blank_does_not_confirm(self, post):
        gate = BlankFrameGate()
        assert gate.confirmed(blank_summary(), post, 0.0) is False
        assert gate.confirmed(blank_summary(), post,
                              post.blank_confirm_sec / 2.0) is False

    def test_a_sustained_blank_confirms(self, post):
        """벽은 사라지지 않는다 — 이어지면 그때는 참이다."""
        gate = BlankFrameGate()
        gate.confirmed(blank_summary(), post, 0.0)
        assert gate.confirmed(blank_summary(), post,
                              post.blank_confirm_sec + 0.01) is True

    def test_one_good_frame_resets_the_timer(self, post):
        """추론 지터는 한두 틱이다 — 사이에 장면이 하나 들어오면 처음부터 다시 센다."""
        gate = BlankFrameGate()
        gate.confirmed(blank_summary(), post, 0.0)
        gate.confirmed(scene_summary(), post, post.blank_confirm_sec - 0.01)
        assert gate.confirmed(blank_summary(), post,
                              post.blank_confirm_sec + 0.01) is False

    def test_the_window_is_short_enough_to_stay_safe(self, post):
        """유예가 길면 정말로 막힌 경우의 대응이 그만큼 늦는다.

        유예 중 명령은 정지가 아니라 인지 소실 복구 주행(저속 + 직전 조향)이므로
        나아가는 거리가 이 시간에 비례한다. 1초를 넘기면 그 자체가 위험이다.
        """
        assert 0.0 < post.blank_confirm_sec <= 1.0
