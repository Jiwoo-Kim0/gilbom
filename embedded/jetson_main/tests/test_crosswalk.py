"""횡단보도 점자블록 **미설치 구역** 판정 — 명세 5단계.

명세: "횡단보도 탐지시 횡단보도와 sidewalk 클래스 사이에 점자블록 클래스 있는지 판단
→ 없으면 미설치 구역으로 지정 후 해당 위치와 사진 통신 예정."

화면 좌표로 옮기면: 로봇은 인도 위에 있고 횡단보도는 그 앞이므로 화면에서 횡단보도가
위, 인도가 아래다. 따라서 '사이'는 **횡단보도 마스크의 가장 가까운 행(최대 y) 바로
아래 띠**이고, x 범위는 횡단보도가 실제로 걸친 폭이다.

두 층을 나눠 본다 (다른 테스트 파일과 같은 전략):
  · 순수 층 — build_frame의 판정(횡단보도 발견 게이트 + 띠 비율 임계). 개발 PC에서
    그대로 돌아간다.
  · numpy 층 미러 — 띠 기하(_crosswalk_seam_braille_fraction)를 순수 파이썬으로
    다시 구현해 합성 장면에 돌린다. 검증 대상은 numpy 코드가 아니라 기하의 판단이다.
"""

import math

import pytest

from perception.segmentation import MaskSummary, build_frame

HEIGHT, WIDTH = 480, 640


def summary(**overrides) -> MaskSummary:
    """무해한 기본값(인도 잘 보임·장애물 없음·횡단보도 없음) 위에 덮어쓰기."""
    base = dict(
        drivable_fraction=0.6, drivable_center_offset_ratio=0.0,
        roadway_corridor_fraction=0.0, obstacle_corridor_fraction=0.0,
        obstacle_lateral_bias=0.0, obstacle_proximity=0.0, obstacle_height_ratio=0.0,
        braille_fraction=0.0, braille_center_offset_ratio=0.0, braille_proximity=0.0,
    )
    base.update(overrides)
    return MaskSummary(**base)


class TestCrosswalkVerdict:
    """순수 층 — 발견 게이트와 미설치 임계."""

    def test_no_crosswalk_never_reports_missing(self, post):
        """횡단보도가 없으면 미설치도 없다 — 게이트가 없으면 인도 한복판에서 보고가 나간다.

        '띠에 블록이 없다'는 조건은 횡단보도가 안 보일 때 **항상 참**이다(띠 자체가
        없으므로 비율 0). 이 게이트가 이 판정을 쓸 수 있게 만드는 유일한 조건이다.
        """
        frame = build_frame(summary(crosswalk_fraction=0.0,
                                    crosswalk_seam_braille_fraction=0.0),
                            post, timestamp=0.0)
        assert not frame.crosswalk_ahead
        assert not frame.braille_missing_at_crosswalk

    def test_crosswalk_with_seam_blocks_is_installed(self, post):
        frame = build_frame(
            summary(crosswalk_fraction=post.crosswalk_min_fraction * 2,
                    crosswalk_seam_braille_fraction=(
                        post.crosswalk_seam_braille_min_fraction * 2)),
            post, timestamp=0.0)
        assert frame.crosswalk_ahead
        assert not frame.braille_missing_at_crosswalk

    def test_crosswalk_without_seam_blocks_is_missing(self, post):
        frame = build_frame(
            summary(crosswalk_fraction=post.crosswalk_min_fraction * 2,
                    crosswalk_seam_braille_fraction=(
                        post.crosswalk_seam_braille_min_fraction / 2)),
            post, timestamp=0.0)
        assert frame.crosswalk_ahead
        assert frame.braille_missing_at_crosswalk

    def test_faint_crosswalk_is_ignored(self, post):
        # 노면 반사·흰 도색 오탐이 미설치 보고를 남발하지 않게 하는 하한.
        # 보고는 되돌릴 수 없는 외부 출력이라 발견 임계를 블록 하한보다 훨씬 높게 둔다
        frame = build_frame(
            summary(crosswalk_fraction=post.crosswalk_min_fraction / 2,
                    crosswalk_seam_braille_fraction=0.0),
            post, timestamp=0.0)
        assert not frame.crosswalk_ahead
        assert not frame.braille_missing_at_crosswalk

    def test_unobservable_seam_is_not_reported_as_missing(self, post):
        """seam=None(판정 보류)은 미설치가 아니다 — 0804 실외 오탐의 구조적 원인.

        '관측하지 못했다'와 '봤는데 없다'(0.0)는 다른 사실이다. 보고는 되돌릴 수
        없으므로 전자를 후자로 바꾸지 않는다. None이 나오는 실제 경우는 numpy 층이
        판단한다: 띠가 화면 밖으로 잘림 / 띠가 인도가 아님 (TestSeamGuards).
        """
        frame = build_frame(
            summary(crosswalk_fraction=post.crosswalk_min_fraction * 2,
                    crosswalk_seam_braille_fraction=None),
            post, timestamp=0.0)
        assert frame.crosswalk_ahead            # 횡단보도는 봤다
        assert not frame.braille_missing_at_crosswalk   # 그래도 보고하지 않는다

    def test_crosswalk_gate_is_stricter_than_braille_gate(self, post):
        assert post.crosswalk_min_fraction > post.braille_min_fraction

    def test_crosswalk_does_not_touch_driving_fields(self, post):
        # 미설치 판정은 **보고**이지 주행 판단이 아니다 — 주행 필드를 흔들면 안 된다
        installed = build_frame(
            summary(crosswalk_fraction=post.crosswalk_min_fraction * 2,
                    crosswalk_seam_braille_fraction=0.5), post, timestamp=0.0)
        missing = build_frame(
            summary(crosswalk_fraction=post.crosswalk_min_fraction * 2,
                    crosswalk_seam_braille_fraction=0.0), post, timestamp=0.0)
        assert installed.sidewalk_detected == missing.sidewalk_detected
        assert installed.obstacles == missing.obstacles
        assert (installed.roadway_encroachment_risk
                == missing.roadway_encroachment_risk)


# --- numpy 계층 미러 (perception.segmentation._crosswalk_seam_braille_fraction) ---

def quantile(values, q: float) -> float:
    """numpy.quantile 기본(선형 보간)과 같은 분위수 — 미러가 같은 행을 잡아야 한다."""
    xs = sorted(values)
    position = q * (len(xs) - 1)
    low = int(math.floor(position))
    high = min(low + 1, len(xs) - 1)
    return xs[low] + (xs[high] - xs[low]) * (position - low)


def seam_braille_fraction(crosswalk, braille, band_ratio: float, q: float,
                          drivable=None, drivable_min: float = 0.0,
                          min_near_ratio: float = 0.0,
                          max_tilt_band_ratio: float = float("inf")):
    """이음 띠 안의 점자블록 비율, 판정 불가면 None. 픽셀 집합은 (x, y).

    drivable=None은 '띠 전체가 인도'라는 뜻이다 — 기하만 보는 테스트용 지름길이고,
    가드 자체는 TestSeamGuards가 실제 집합을 넣어 확인한다.
    min_near_ratio·max_tilt_band_ratio의 기본값은 '가드 없음'이다 — 기하만 보는
    테스트가 거리·기울기 가드에 걸려 전부 None이 되지 않게 한다. 두 가드 자체는
    TestSeamObservability가 실제 값을 넣어 확인한다.
    """
    if not crosswalk:
        return None
    ys = [y for _, y in crosswalk]
    xs = [x for x, _ in crosswalk]
    near_y = int(quantile(ys, q))
    band_px = max(1, int(round(HEIGHT * band_ratio)))
    if near_y < HEIGHT * min_near_ratio:    # ③ 아직 멀다 → 판정 보류
        return None
    y_from = min(HEIGHT - 1, near_y)
    y_to = min(HEIGHT, near_y + band_px)
    if y_to - y_from < band_px:              # ① 띠가 화면 밖으로 잘렸다 → 판정 보류
        return None
    x_from = int(quantile(xs, 1.0 - q))
    x_to = min(WIDTH, max(x_from + 1, int(quantile(xs, q)) + 1))
    # ④ 하단 경계 기울기 — 좌/우 절반의 최근접 행 차이가 띠 높이에 비해 크면 보류
    mid_x = (x_from + x_to) // 2
    left = [y for (x, y) in crosswalk if x < mid_x]
    right = [y for (x, y) in crosswalk if x >= mid_x]
    if left and right:
        if abs(quantile(left, q) - quantile(right, q)) > band_px * max_tilt_band_ratio:
            return None
    area = (y_to - y_from) * (x_to - x_from)

    def in_band(pixels):
        return sum(1 for (x, y) in pixels
                   if y_from <= y < y_to and x_from <= x < x_to)

    if drivable is not None and in_band(drivable) / area < drivable_min:
        return None                          # ② 띠가 인도가 아니다 → 판정 보류
    return in_band(braille) / area


def rect(x_from: int, x_to: int, y_from: int, y_to: int):
    return {(x, y) for x in range(x_from, x_to) for y in range(y_from, y_to)}


class TestSeamGeometry:
    """띠 기하 — '어디를 보는가'가 이 판정의 전부다."""

    CROSSWALK = rect(200, 440, 120, 200)     # 로봇 앞 횡단보도 (화면 위쪽)

    def fraction(self, braille, post) -> float:
        return seam_braille_fraction(self.CROSSWALK, braille,
                                     post.crosswalk_seam_band_ratio,
                                     post.proximity_quantile)

    def test_warning_blocks_at_the_seam_are_found(self, post):
        # 점형(경고) 블록이 횡단보도 직전 인도에 깔려 있다 → 설치됨
        warning = rect(220, 420, 200, 250)
        assert (self.fraction(warning, post)
                >= post.crosswalk_seam_braille_min_fraction)

    def test_guide_line_behind_the_robot_is_not_counted(self, post):
        """회귀 방어: 이미 지나온 유도선을 세면 미설치를 **영원히** 검출하지 못한다.

        화면 전체에서 블록을 찾으면 로봇 발밑의 선형블록이 항상 잡혀 '있다'가 된다.
        띠를 횡단보도 최근접 행 바로 아래로 좁게 잡는 이유가 이것이다.
        """
        behind = rect(280, 360, 400, 470)    # 발밑 유도선 — 띠 밖이다
        assert (self.fraction(behind, post)
                < post.crosswalk_seam_braille_min_fraction)

    def test_blocks_beside_the_crosswalk_are_not_counted(self, post):
        # 띠의 x 범위는 횡단보도가 걸친 폭이다 — 옆 인도의 블록은 그 횡단보도의
        # 유도블록이 아니다
        beside = rect(0, 150, 200, 250)
        assert (self.fraction(beside, post)
                < post.crosswalk_seam_braille_min_fraction)

    def test_blocks_above_the_crosswalk_are_not_counted(self, post):
        # 띠는 횡단보도 **아래**(로봇 쪽)다. 건너편 인도의 블록을 세면 미설치를 놓친다
        far_side = rect(220, 420, 60, 110)
        assert (self.fraction(far_side, post)
                < post.crosswalk_seam_braille_min_fraction)

    def test_no_blocks_at_all_is_missing(self, post):
        assert self.fraction(set(), post) == 0.0

    def test_noise_pixel_does_not_drag_the_band_down(self, post):
        """최근접 행을 최대 y가 아니라 분위수로 잡는 이유.

        횡단보도 마스크 하단에 노이즈 픽셀 하나가 붙으면 최대 y 기준으로는 띠가 통째로
        인도 쪽으로 밀려 내려가, 실제로 있는 점형블록을 비껴간다.
        """
        noisy = self.CROSSWALK | {(320, 460)}
        warning = rect(220, 420, 200, 250)
        with_noise = seam_braille_fraction(noisy, warning,
                                           post.crosswalk_seam_band_ratio,
                                           post.proximity_quantile)
        assert with_noise >= post.crosswalk_seam_braille_min_fraction


class TestSeamGuards:
    """판정 보류(None) 두 경우 — 0804 실외 오탐 12건 중 확인된 2건의 원인.

    보고는 되돌릴 수 없으므로 '못 봤다'를 '없다'로 바꾸지 않는다. 이 가드가 없으면
    카메라가 하늘을 본 프레임과 이음부를 이미 밟은 프레임이 전부 미설치가 된다.
    """

    def fraction(self, crosswalk, braille, post, drivable=None):
        return seam_braille_fraction(crosswalk, braille,
                                     post.crosswalk_seam_band_ratio,
                                     post.proximity_quantile,
                                     drivable=drivable,
                                     drivable_min=post.crosswalk_seam_drivable_min_fraction)

    def test_crosswalk_at_frame_bottom_is_withheld(self, post):
        # 횡단보도가 화면 최하단에 걸렸다 = 띠가 화면 밖이다. 카메라가 위를 향했거나
        # 로봇이 이음부를 이미 밟고 있는 프레임 — 어느 쪽이든 판정은 무의미하다
        at_bottom = rect(200, 440, HEIGHT - 10, HEIGHT)
        assert self.fraction(at_bottom, set(), post) is None

    def test_seam_off_the_sidewalk_is_withheld(self, post):
        # 띠 안에 주행가능면이 없다 = 명세의 '횡단보도와 sidewalk 사이'가 아니다
        crosswalk = rect(200, 440, 120, 200)
        assert self.fraction(crosswalk, set(), post, drivable=set()) is None

    def test_seam_on_the_sidewalk_is_judged(self, post):
        # 같은 기하에 인도가 깔려 있으면 판정한다 — 가드가 정상 경로를 막지 않는다
        crosswalk = rect(200, 440, 120, 200)
        sidewalk = rect(180, 460, 200, 300)
        value = self.fraction(crosswalk, set(), post, drivable=sidewalk)
        assert value == 0.0                                    # 봤는데 블록이 없다
        assert value < post.crosswalk_seam_braille_min_fraction   # → 미설치


class TestSeamObservability:
    """판정 보류 ③④ — '관측할 수 있었는가'를 먼저 묻는다 (0805 실기 근거).

    미설치는 **부재의 증명**이다. 부재 증명은 '관측 가능했는데 없었다'를 요구하는데,
    종전 가드는 '너무 가까움'(띠 잘림)만 막고 있었다. 그래서:
      ③ 원거리 — crosswalk_min_fraction(0.03)은 도색면이 넓어 원거리에서도 넘는다.
        그 거리에서는 원근압축으로 띠가 지면 수 미터를 덮고 점형블록은 아직 몇
        픽셀이거나 화각 밖이다 → '보이기 전에 없다고 판정'.
      ④ 사선 — 최근접 행은 가장 가까운 한쪽 코너가 정하는데 x 범위는 횡단보도가
        걸친 폭 전체다. 기울기가 크면 수평 띠가 반대쪽에서 이음부를 완전히 비껴간다.
    """

    def fraction(self, crosswalk, braille, post):
        """실제 구현과 **같은 가드 값**으로 부른다 (미러가 어긋나면 이 클래스가 잡는다)."""
        return seam_braille_fraction(
            crosswalk, braille,
            post.crosswalk_seam_band_ratio, post.proximity_quantile,
            min_near_ratio=post.crosswalk_seam_min_near_ratio,
            max_tilt_band_ratio=post.crosswalk_seam_max_tilt_band_ratio)

    def near_y_of(self, ratio: float) -> int:
        return int(HEIGHT * ratio)

    def test_distant_crosswalk_is_withheld(self, post):
        """멀리 보이는 횡단보도는 판정하지 않는다 — 오탐의 지배적 경로였다."""
        top = self.near_y_of(post.crosswalk_seam_min_near_ratio) - 60
        distant = rect(200, 440, top, top + 50)
        assert self.fraction(distant, set(), post) is None

    def test_near_crosswalk_is_judged(self, post):
        """창 안으로 들어오면 판정한다 — 가드가 정상 경로를 막으면 미설치를 못 잡는다."""
        near = self.near_y_of(post.crosswalk_seam_min_near_ratio) + 10
        crosswalk = rect(200, 440, near - 60, near)
        assert self.fraction(crosswalk, set(), post) == 0.0     # 봤는데 없다 → 미설치

    def test_the_window_is_not_empty_before_the_estop_point(self, post):
        """판정 창이 ESTOP 지점 **아래**에서 열려야 한다 — 안 그러면 영영 보고 못 한다.

        횡단보도는 roadway_classes 합집합이라 코리도를 채우면 roadway_critical이
        먼저 ESTOP을 건다(수동 해제만이 출구). 그 지점의 near_y는
        corridor_height_ratio와 roadway_critical_fraction으로 정해지므로, 판정 하한이
        그보다 위면 창이 열리기 전에 미션이 멈춘다.
        """
        estop_near_ratio = (1.0 - post.corridor_height_ratio
                            + post.roadway_critical_fraction * post.corridor_height_ratio)
        assert post.crosswalk_seam_min_near_ratio < estop_near_ratio
        # 창의 반대쪽 끝(띠 잘림)도 하한보다 위여야 창이 존재한다
        assert post.crosswalk_seam_min_near_ratio < 1.0 - post.crosswalk_seam_band_ratio

    def test_slanted_crosswalk_edge_is_withheld(self, post):
        """사선 이음부 — 수평 띠 하나로 덮을 수 없으므로 보류한다."""
        base = self.near_y_of(post.crosswalk_seam_min_near_ratio) + 20
        band_px = round(HEIGHT * post.crosswalk_seam_band_ratio)
        drop = int(band_px * post.crosswalk_seam_max_tilt_band_ratio) + 12
        # 왼쪽은 base, 오른쪽은 base-drop 에서 끝난다 (오른쪽이 더 멀다)
        slanted = rect(200, 320, base - 60, base) | rect(320, 440, base - 60 - drop,
                                                        base - drop)
        assert self.fraction(slanted, set(), post) is None

    def test_mildly_slanted_edge_is_still_judged(self, post):
        """허용치 안의 기울기는 판정한다 — 실사에서 완전한 수평은 없다."""
        base = self.near_y_of(post.crosswalk_seam_min_near_ratio) + 20
        band_px = round(HEIGHT * post.crosswalk_seam_band_ratio)
        drop = max(1, int(band_px * post.crosswalk_seam_max_tilt_band_ratio) - 8)
        mild = rect(200, 320, base - 60, base) | rect(320, 440, base - 60 - drop,
                                                     base - drop)
        assert self.fraction(mild, set(), post) is not None


class TestMirrorMatchesImplementation:
    """미러 ≡ 실제 numpy 구현. **위 두 클래스가 검증하는 것은 미러**이므로 이 고리가
    없으면 구현만 조용히 어긋난다.

    실제로 어긋난 적이 있다: 0805에 거리·기울기 가드를 구현에 넣었는데 미러에는 안
    넣었고, 그래서 구현에서 가드를 도로 빼도 테스트가 전부 통과했다. 미러 전략의
    구조적 약점이고, 그 약점을 막는 것이 이 클래스의 유일한 일이다.

    numpy가 없는 개발 PC에서는 skip된다 — 보드와 numpy 있는 환경에서 걸린다.
    """

    def scenes(self, post):
        """(이름, 횡단보도, 점자블록) — 두 가드의 양쪽 경계를 다 지난다."""
        near = int(HEIGHT * post.crosswalk_seam_min_near_ratio)
        band = round(HEIGHT * post.crosswalk_seam_band_ratio)
        tilt = post.crosswalk_seam_max_tilt_band_ratio
        big, ok = int(band * tilt) + 12, max(1, int(band * tilt) - 8)
        return [
            ("원거리", rect(200, 440, near - 60, near - 10), set()),
            ("근거리·블록 없음", rect(200, 440, near - 50, near + 10), set()),
            ("근거리·블록 있음", rect(200, 440, near - 50, near + 10),
             rect(220, 420, near + 10, near + 50)),
            ("사선 초과", rect(200, 320, near - 40, near + 20)
             | rect(320, 440, near - 40 - big, near + 20 - big), set()),
            ("사선 허용", rect(200, 320, near - 40, near + 20)
             | rect(320, 440, near - 40 - ok, near + 20 - ok), set()),
            ("화면 최하단", rect(200, 440, HEIGHT - 10, HEIGHT), set()),
            ("횡단보도 없음", set(), set()),
        ]

    def test_every_scene_agrees(self, post):
        np = pytest.importorskip("numpy")
        from perception.segmentation import _crosswalk_seam_braille_fraction

        def mask(pixels):
            grid = np.zeros((HEIGHT, WIDTH), dtype=bool)
            for x, y in pixels:
                grid[y, x] = True
            return grid

        for name, crosswalk, braille in self.scenes(post):
            actual = _crosswalk_seam_braille_fraction(
                np, mask(crosswalk), mask(braille),
                np.ones((HEIGHT, WIDTH), dtype=bool),   # 띠 전체가 인도 (미러 기본값)
                height=HEIGHT, width=WIDTH,
                band_ratio=post.crosswalk_seam_band_ratio,
                quantile=post.proximity_quantile,
                drivable_min_fraction=0.0,
                min_near_ratio=post.crosswalk_seam_min_near_ratio,
                max_tilt_band_ratio=post.crosswalk_seam_max_tilt_band_ratio)
            expected = seam_braille_fraction(
                crosswalk, braille,
                post.crosswalk_seam_band_ratio, post.proximity_quantile,
                min_near_ratio=post.crosswalk_seam_min_near_ratio,
                max_tilt_band_ratio=post.crosswalk_seam_max_tilt_band_ratio)
            if expected is None:
                assert actual is None, f"{name}: 구현은 판정, 미러는 보류"
            else:
                assert actual is not None, f"{name}: 구현은 보류, 미러는 판정"
                assert actual == pytest.approx(expected), name
