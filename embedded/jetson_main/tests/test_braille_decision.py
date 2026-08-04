"""점자블록 파손 판정(band_argmax 이식) 테스트 — 순수 계층만, 무거운 의존성 없음.

핵심 회귀 방어: normal과 defect_full은 같은 띠의 배타적 라벨이라 모델이 둘을
동시에 낸다. "defect_full 박스가 있는가"로 세면 과대집계(실측 66.7% vs 실제 15.3%)
— 반드시 띠 묶음별 argmax 승자로 판정해야 한다 (models/README.md).

임계값은 config.yaml perception.braille에서 읽는다. 알고리즘 경계(운영점 변화에
따른 거동)를 검증할 때만 dataclasses.replace로 파생값을 만든다 — 튜닝 숫자
하드코딩이 아니라 로직 검증이다.
"""

from dataclasses import replace

import pytest

from perception.braille import (CLASS_DEFECT_FULL, CLASS_DEFECT_PARTIAL,
                                CLASS_NORMAL, BrailleParams, decide_band_argmax,
                                group_bands)

T = 0.0  # 판정 타임스탬프 — 결과에 영향 없음


@pytest.fixture(scope="module")
def params(config) -> BrailleParams:
    return BrailleParams.from_config(config["perception"]["braille"])


def box(x: float = 0.0, y: float = 0.0, size: float = 100.0):
    return (x, y, x + size, y + size)


def det(conf: float, cls_name: str, at: float = 0.0):
    """같은 at 값 → 같은 위치(완전 겹침, IoU=1) / 멀리 떨어뜨리면 다른 띠."""
    return (conf, cls_name, box(x=at))


class TestBandGrouping:
    def test_overlapping_predictions_form_one_band(self, params):
        groups = group_bands([det(0.9, CLASS_NORMAL), det(0.6, CLASS_DEFECT_FULL)],
                             params.iou_same_band)
        assert len(groups) == 1
        assert len(groups[0]) == 2
        assert groups[0][0][0] == 0.9  # 정렬 덕에 첫 원소가 argmax 승자

    def test_distant_predictions_form_separate_bands(self, params):
        groups = group_bands([det(0.9, CLASS_NORMAL, at=0.0),
                              det(0.8, CLASS_NORMAL, at=1000.0)],
                             params.iou_same_band)
        assert len(groups) == 2

    def test_partial_never_joins_bands(self, params):
        # defect_partial은 띠 묶음 대상이 아니다 (원본과 동일)
        groups = group_bands([det(0.9, CLASS_NORMAL), det(0.8, CLASS_DEFECT_PARTIAL)],
                             params.iou_same_band)
        assert len(groups) == 1
        assert len(groups[0]) == 1


class TestBandArgmaxDecision:
    def test_no_detections_is_not_defect(self, params):
        verdict = decide_band_argmax([], params, T)
        assert not verdict.is_defect
        assert verdict.reason == "검출 없음"
        assert verdict.band_count == 0

    def test_only_partials_is_band_less(self, params):
        verdict = decide_band_argmax([det(0.9, CLASS_DEFECT_PARTIAL)], params, T)
        assert not verdict.is_defect
        assert verdict.reason == "띠 검출 없음"

    def test_single_normal_band(self, params):
        verdict = decide_band_argmax([det(0.9, CLASS_NORMAL)], params, T)
        assert not verdict.is_defect
        assert verdict.band_count == 1

    def test_argmax_beats_naive_counting(self, params):
        # 핵심 회귀: 같은 띠에 normal 0.9 + defect_full 0.6 → 정상 판정이어야 한다.
        # 'defect_full 박스 존재'로 세면 여기서 불량이 나온다 (66.7% 과대집계 사고).
        verdict = decide_band_argmax(
            [det(0.9, CLASS_NORMAL), det(0.6, CLASS_DEFECT_FULL)], params, T)
        assert not verdict.is_defect
        assert verdict.band_count == 1
        assert verdict.defect_band_count == 0

    def test_defect_winner_makes_band_defective(self, params):
        verdict = decide_band_argmax(
            [det(0.9, CLASS_DEFECT_FULL), det(0.6, CLASS_NORMAL)], params, T)
        assert verdict.is_defect
        assert verdict.defect_band_count == 1

    def test_one_defective_band_among_normals(self, params):
        verdict = decide_band_argmax(
            [det(0.9, CLASS_NORMAL, at=0.0), det(0.8, CLASS_DEFECT_FULL, at=1000.0)],
            params, T)
        assert verdict.is_defect
        assert verdict.band_count == 2
        assert verdict.defect_band_count == 1

    def test_band_conf_operating_point(self, params):
        # 운영점 로직 경계: 승자 신뢰도가 band_conf 미만이면 불량으로 세지 않는다
        strict = replace(params, band_conf=0.5)
        verdict = decide_band_argmax([det(0.4, CLASS_DEFECT_FULL)], strict, T)
        assert not verdict.is_defect
        verdict = decide_band_argmax([det(0.5, CLASS_DEFECT_FULL)], strict, T)
        assert verdict.is_defect


class TestPartialPromotion:
    """띠가 정상일 때 defect_partial ≥ max(2, min_defects)곳이면 불량 승격."""

    def test_two_partials_promote(self, params):
        above = params.defect_conf + 0.01
        verdict = decide_band_argmax(
            [det(0.9, CLASS_NORMAL),
             det(above, CLASS_DEFECT_PARTIAL, at=200.0),
             det(above, CLASS_DEFECT_PARTIAL, at=400.0)], params, T)
        assert verdict.is_defect
        assert verdict.partial_count == 2
        assert verdict.defect_band_count == 0  # 띠 자체는 정상 — 승격 판정

    def test_one_partial_does_not_promote(self, params):
        above = params.defect_conf + 0.01
        verdict = decide_band_argmax(
            [det(0.9, CLASS_NORMAL), det(above, CLASS_DEFECT_PARTIAL, at=200.0)],
            params, T)
        assert not verdict.is_defect

    def test_low_conf_partials_ignored(self, params):
        below = params.defect_conf - 0.01
        verdict = decide_band_argmax(
            [det(0.9, CLASS_NORMAL),
             det(below, CLASS_DEFECT_PARTIAL, at=200.0),
             det(below, CLASS_DEFECT_PARTIAL, at=400.0)], params, T)
        assert not verdict.is_defect
        assert verdict.partial_count == 0

    def test_min_defects_floor_is_two(self, params):
        # 원본 그대로: 하한은 max(2, min_defects) — min_defects=1이어도 2곳 필요
        floor_one = replace(params, min_defects=1)
        above = params.defect_conf + 0.01
        verdict = decide_band_argmax(
            [det(0.9, CLASS_NORMAL), det(above, CLASS_DEFECT_PARTIAL, at=200.0)],
            floor_one, T)
        assert not verdict.is_defect

    def test_partials_do_not_affect_defective_band_verdict(self, params):
        # defect_conf는 띠 판정에 무영향 — 불량 띠가 있으면 파손 수는 부가 정보
        above = params.defect_conf + 0.01
        verdict = decide_band_argmax(
            [det(0.9, CLASS_DEFECT_FULL), det(above, CLASS_DEFECT_PARTIAL, at=200.0)],
            params, T)
        assert verdict.is_defect
        assert verdict.defect_band_count == 1
        assert verdict.partial_count == 1
