"""자차 연결(ego-connected) 주행가능 판정 — '차도를 인도로 본다'의 방어선.

0803 실외 테스트의 1번 문제: 세그가 **길 건너 차도를 sidewalk로 오분류**하는 경우가
있었다. 종전 코드는 sidewalk 마스크를 전역 합집합으로 썼기 때문에 그 픽셀이 무게중심·
중심선·경계 판정에 그대로 섞여 조향을 차도 쪽으로 끌어당겼다. 관련 연구도 이 혼동을
"road-sidewalk confusion … a false-safe representation of an unsafe region"으로 부르며
안전 치명 실패 모드로 분류한다.

방어선의 원리: 로봇은 지금 인도 위에 있다. 그러므로 **내 발밑에서 열이 끊기지 않고
이어진 영역만** 내 주행면이다 — 길 건너 영역은 사이에 차도가 있어 열이 끊긴다.
산식은 자유공간 도달거리와 같은 것 하나뿐이다(perception.segmentation._ego_run_mask).

테스트 전략은 tests/test_free_space.py와 같다: numpy·cv2는 보드 전용이라 개발 PC에서
실행할 수 없으므로, **numpy 구현과 같은 알고리즘을 순수 파이썬으로 미러링**해 합성
장면에 돌린다. 검증 대상은 numpy 코드가 아니라 알고리즘의 판단이다 — 두 구현이
갈라지면 이 파일의 미러를 먼저 고칠 것.
"""

import pytest

HEIGHT, WIDTH = 480, 640


# --- numpy 계층 미러 (perception.segmentation._close_vertical_gaps / _ego_run_mask) ---
# 두 연산 모두 **세로 방향뿐**이라 열 단위로 독립적이다 — 그래서 1차원으로 미러링된다.

def dilate(column, gap_rows: int):
    """세로 선 구조요소(길이 2g+1) 팽창 — numpy 판의 shifted-OR 누적과 같다."""
    size = len(column)
    out = list(column)
    for shift in range(1, gap_rows + 1):
        for index in range(size - shift):
            out[index] = out[index] or column[index + shift]
        for index in range(shift, size):
            out[index] = out[index] or column[index - shift]
    return out


def erode(grown, gap_rows: int):
    """같은 구조요소로 침식 — numpy 판의 shifted-AND 누적과 같다."""
    size = len(grown)
    out = list(grown)
    for shift in range(1, gap_rows + 1):
        for index in range(size - shift):
            out[index] = out[index] and grown[index + shift]
        for index in range(shift, size):
            out[index] = out[index] and grown[index - shift]
    return out


def closing(column, gap_rows: int):
    """세로 닫힘 = 팽창 → 침식, 그리고 원본 OR (원래 픽셀은 반드시 살아남는다)."""
    if gap_rows <= 0:
        return list(column)
    grown = dilate(column, gap_rows)
    eroded = erode(grown, gap_rows)
    return [raw or closed for raw, closed in zip(column, eroded)]


def ego_run(column, scan_from: int, gap_rows: int):
    """scan_from 행부터 위로 **연속된** 주행가능 구간만 True로 남긴 열."""
    band = closing(column[:scan_from + 1], gap_rows)
    run = [False] * (scan_from + 1)
    for index in range(scan_from, -1, -1):
        if not band[index]:
            break
        run[index] = True
    return run


# --- 합성 장면 ---

def blank_scene():
    """열 × 행 불리언 격자 (scene[x][y]) — 전부 비주행."""
    return [[False] * HEIGHT for _ in range(WIDTH)]


def fill(scene, x_from: int, x_to: int, y_from: int, y_to: int) -> None:
    for x in range(x_from, x_to):
        for y in range(y_from, y_to):
            scene[x][y] = True


def centroid_offset(scene, rows, columns) -> float:
    """주어진 행·열 범위 주행가능 픽셀의 무게중심 x → -1(좌끝) ~ +1(우끝).

    perception.segmentation._offset_ratio / _centroid_offset_ratio와 같은 관례다.
    """
    total, weighted = 0, 0
    for x in columns:
        for y in rows:
            if scene[x][y]:
                total += 1
                weighted += x
    assert total, "주행가능 픽셀이 하나도 없는 장면"
    return (weighted / total) / (WIDTH - 1) * 2.0 - 1.0


def apply_ego(scene, scan_from: int, gap_rows: int):
    """장면 전체에 자차 연결을 적용한 새 장면 (scan_from 위쪽만 대체 — numpy 판과 동일)."""
    out = [list(column) for column in scene]
    for x in range(WIDTH):
        run = ego_run(scene[x], scan_from, gap_rows)
        out[x][:scan_from + 1] = run
    return out


class TestRoadMisreadAsSidewalk:
    """핵심 회귀: 길 건너 차도가 sidewalk로 잡혀도 조향 목표를 끌어당기지 못한다."""

    @pytest.fixture
    def scene(self):
        """내 인도(가운데) + 차도 띠 + 길 건너 오분류 인도(왼쪽 치우침).

        오분류 패치를 왼쪽으로 치우쳐 두는 것이 요점이다 — 전역 합집합의 실제 피해는
        '면적이 는다'가 아니라 '무게중심·중심선이 그쪽으로 끌려간다'는 것이다.
        """
        scene = blank_scene()
        fill(scene, 200, 440, 320, HEIGHT)     # 내 인도: 화면 가운데, 발밑부터
        # 260~319행 = 차도(비주행) — 여기서 열이 끊긴다
        fill(scene, 0, 150, 200, 260)          # 길 건너 오분류 패치: 왼쪽
        return scene

    def test_naive_union_is_pulled_toward_the_road(self, scene, post):
        # 종전 동작을 고정한다 — 이것이 고치려는 대상이다
        rows = range(HEIGHT // 2, HEIGHT)
        naive = centroid_offset(scene, rows, range(WIDTH))
        assert naive < -0.05, "오분류 패치가 무게중심을 왼쪽으로 끌지 않으면 장면이 무의미"

    def test_ego_connection_drops_the_across_street_patch(self, scene, post):
        rows = range(HEIGHT // 2, HEIGHT)
        naive = centroid_offset(scene, rows, range(WIDTH))
        ego = apply_ego(scene, scan_from=HEIGHT - 1, gap_rows=post.ego_gap_rows)
        connected = centroid_offset(ego, rows, range(WIDTH))

        # 내 인도(200~440)의 중심 = 화면 중앙 → 오차 ≈ 0
        assert abs(connected) < 0.02
        assert abs(connected) < abs(naive)

    def test_across_street_rows_are_gone(self, scene, post):
        ego = apply_ego(scene, scan_from=HEIGHT - 1, gap_rows=post.ego_gap_rows)
        assert any(ego[x][400] for x in range(200, 440))     # 내 인도는 남는다
        assert not any(ego[x][y] for x in range(0, 150) for y in range(200, 260))


class TestGapClosing:
    """세로 끊김 메우기 — 그림자·맨홀 한두 행이 그 위 인도를 통째로 잘라내지 않게."""

    def column_with_break(self, break_rows: int):
        """발밑부터 이어지는 인도 열에 break_rows 만큼의 끊김을 넣는다."""
        column = [False] * 200 + [True] * (HEIGHT - 200)
        for y in range(300, 300 + break_rows):
            column[y] = False
        return column

    def test_small_break_is_bridged(self, post):
        # 그림자·타일 이음선 폭 — 메워져야 그 위 인도가 내 것으로 남는다
        column = self.column_with_break(post.ego_gap_rows // 2)
        run = ego_run(column, scan_from=HEIGHT - 1, gap_rows=post.ego_gap_rows)
        assert run[250], "끊김 위쪽이 살아남지 않았다 — 닫힘이 동작하지 않는다"

    def test_wide_break_is_not_bridged(self, post):
        # 차도 폭의 끊김은 메워지면 안 된다 — 그러면 방어선 자체가 무력해진다
        column = self.column_with_break(post.ego_gap_rows * 6)
        run = ego_run(column, scan_from=HEIGHT - 1, gap_rows=post.ego_gap_rows)
        assert not run[250]

    def test_break_without_closing_cuts_everything_above(self, post):
        # 닫힘이 필요한 이유의 반대 증명: gap_rows=0이면 한 행 끊김에 위가 다 날아간다
        column = self.column_with_break(1)
        assert not ego_run(column, scan_from=HEIGHT - 1, gap_rows=0)[250]
        assert ego_run(column, scan_from=HEIGHT - 1, gap_rows=post.ego_gap_rows)[250]

    def test_closing_never_removes_pixels(self, post):
        # 닫힘은 정의상 원본을 포함한다 — 아니면 주행가능 면적이 조용히 줄어든다
        column = self.column_with_break(3)
        closed = closing(column, post.ego_gap_rows)
        for raw, out in zip(column, closed):
            assert out or not raw


class TestObstacleShortensOwnColumns:
    """자차 연결과 자유공간 도달거리가 같은 산식이라는 것의 귀결 — 물체는 자기 열만 끊는다."""

    def test_person_columns_reach_less_than_neighbours(self, post):
        scene = blank_scene()
        fill(scene, 0, WIDTH, 137, HEIGHT)        # 지평선(137행) 아래 전부 인도
        fill_person = range(300, 340)             # 사람이 서 있는 열 구간
        for x in fill_person:
            for y in range(221, 309):             # 다리·몸통이 인도를 가린다
                scene[x][y] = False

        scan_from = HEIGHT - 1
        person = sum(ego_run(scene[320], scan_from, post.ego_gap_rows))
        clear = sum(ego_run(scene[100], scan_from, post.ego_gap_rows))
        assert person < clear
