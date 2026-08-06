import assert from 'node:assert/strict'
import test from 'node:test'
import {
  bboxForDefectMapFilter,
  clusterSplitZoomStep,
  defectMapFilterKey,
  defectTypeTone,
} from '../src/features/defect/defectMapState.ts'
import {
  DEFECT_SORT_OPTIONS,
  EMPTY_DEFECT_FILTERS,
} from '../src/features/defect/defectTypes.ts'

test('날짜 필터가 바뀌면 이전 지도 bbox를 재사용하지 않는다', () => {
  const initialFilters = {
    ...EMPTY_DEFECT_FILTERS,
    from: '2026-08-01T00:00',
  }
  const initialKey = defectMapFilterKey(initialFilters)
  const viewport = {
    filterKey: initialKey,
    bbox: '128.1,36.1,128.4,36.4',
  }

  assert.equal(bboxForDefectMapFilter(viewport, initialKey), viewport.bbox)

  const changedKey = defectMapFilterKey({
    ...initialFilters,
    from: '2026-08-05T00:00',
  })
  assert.equal(bboxForDefectMapFilter(viewport, changedKey), '')
})

test('파손과 미설치 마커는 서로 다른 유형 색상 클래스를 사용한다', () => {
  assert.equal(defectTypeTone('DAMAGED'), 'damaged')
  assert.equal(defectTypeTone('MISSING'), 'missing')
  assert.notEqual(defectTypeTone('DAMAGED'), defectTypeTone('MISSING'))
  assert.equal(defectTypeTone('INSTALLATION_INVALID'), 'installation-invalid')
  assert.equal(defectTypeTone('UNKNOWN_ANOMALY'), 'unknown-anomaly')
})

test('최초 발견순은 오래된 최초 발견부터 정렬한다', () => {
  const option = DEFECT_SORT_OPTIONS.find(
    ({ label }) => label === '최초 발견순',
  )
  assert.equal(option?.value, 'firstSeenAt,asc')
})

/*
 * 묶음 클릭 확대. 격자 한 칸이 44px이므로 가장 가까운 쌍의 축 방향 거리가 44px 이상이
 * 되어야 갈린다. 확대 한 단계가 화면 거리를 두 배로 만든다.
 */
/*
 * 확대 단계 계산. 1px = 1m인 배율을 넘겨 미터 값이 그대로 화면 픽셀 거리가 되게 했다 —
 * 기대값을 손으로 읽을 수 있다. 격자 한 칸은 실제와 같은 44px이다.
 */
const ONE_METER_PER_PIXEL = 1
/** 서울 위도에서 동서로 `meters`만큼 떨어진 경도 차이. */
function eastWestDegrees(meters: number): number {
  return meters / (111_320 * Math.cos((37.5 * Math.PI) / 180))
}
/** 남북으로 `meters`만큼 떨어진 위도 차이. */
function northSouthDegrees(meters: number): number {
  return meters / 111_320
}

test('한 번의 클릭으로 묶음이 갈릴 만큼 확대 단계를 계산한다', () => {
  const pairApartBy = (meters: number) => [
    { latitude: 37.5, longitude: 127 },
    { latitude: 37.5, longitude: 127 + eastWestDegrees(meters) },
  ]
  // 22px이면 한 단계(×2 = 44px)로 갈린다.
  assert.equal(clusterSplitZoomStep(pairApartBy(22), ONE_METER_PER_PIXEL, 44), 1)
  // 11px이면 두 단계(×4 = 44px)가 필요하다.
  assert.equal(clusterSplitZoomStep(pairApartBy(11), ONE_METER_PER_PIXEL, 44), 2)
  // 3px이면 네 단계(×16 = 48px)다 — 예전 고정값(+2)으로는 다시 같은 숫자가 나왔다.
  assert.equal(clusterSplitZoomStep(pairApartBy(3), ONE_METER_PER_PIXEL, 44), 4)
})

test('가장 가까운 쌍이 기준이다', () => {
  // 멀리 있는 점이 섞여 있어도 촘촘한 쌍을 갈라야 묶음이 풀린다.
  const members = [
    { latitude: 37.5, longitude: 127 },
    { latitude: 37.5 + northSouthDegrees(200), longitude: 127 },
    { latitude: 37.5 + northSouthDegrees(5), longitude: 127 },
  ]
  assert.equal(clusterSplitZoomStep(members, ONE_METER_PER_PIXEL, 44), 4)
})

test('세로가 가까워도 가로가 멀면 이미 다른 칸이다', () => {
  /*
   * 격자가 정사각형이라 같은 칸에 들어가려면 가로·세로가 **둘 다** 가까워야 한다.
   * 그래서 축 방향 최대값(Chebyshev)으로 본다. 가로로 500px 떨어져 있으면 세로가
   * 붙어 있어도 애초에 묶이지 않는다.
   */
  const far = [
    { latitude: 37.5, longitude: 127 },
    { latitude: 37.5 + northSouthDegrees(11), longitude: 127 + eastWestDegrees(500) },
  ]
  assert.equal(clusterSplitZoomStep(far, ONE_METER_PER_PIXEL, 44), 1)
  // 둘 다 가까울 때만 확대가 필요하다.
  const near = [
    { latitude: 37.5, longitude: 127 },
    { latitude: 37.5 + northSouthDegrees(11), longitude: 127 + eastWestDegrees(5) },
  ]
  assert.equal(clusterSplitZoomStep(near, ONE_METER_PER_PIXEL, 44), 2)
})

test('같은 좌표라도 지금 배율에 따라 필요한 단계가 다르다', () => {
  /*
   * 예전 구현은 지도 projection이 준 값을 화면 픽셀이라고 **가정**했다. 그 가정이 틀리면
   * (예: 값이 훨씬 큰 단위로 오면) 이미 멀다고 보고 한 단계만 확대해, 여러 번 눌러야
   * 갈리는 증상이 남는다. 지금은 지도에서 잰 배율(m/px)로 화면 거리를 만든다.
   */
  const members = [
    { latitude: 37.5, longitude: 127 },
    { latitude: 37.5 + northSouthDegrees(22), longitude: 127 },
  ]
  // 1px이 8m면 22m는 화면에서 2.75px뿐이다 → 네 단계(×16 = 44px).
  assert.equal(clusterSplitZoomStep(members, 8, 44), 4)
  assert.equal(clusterSplitZoomStep(members, ONE_METER_PER_PIXEL, 44), 1)
})

test('이미 떨어져 있어도 한 단계는 확대한다', () => {
  // 클릭했는데 아무 일도 없으면 눌리지 않은 것으로 읽힌다.
  const members = [
    { latitude: 37.5, longitude: 127 },
    { latitude: 37.5 + northSouthDegrees(300), longitude: 127 },
  ]
  assert.equal(clusterSplitZoomStep(members, ONE_METER_PER_PIXEL, 44), 1)
})

test('좌표가 완전히 같으면 상한까지 확대한다', () => {
  /*
   * 아무리 확대해도 갈리지 않는다. 계산이 서지 않으니 상한으로 멈춘다. 상한은 넉넉히
   * 두고 실제 자르기는 부르는 쪽(지도 최대 확대 단계)이 한다 — 작게 잡으면 촘촘한
   * 묶음에서 쓸 수 있는 확대폭을 스스로 버린다.
   */
  const same = { latitude: 37.5, longitude: 127 }
  assert.equal(clusterSplitZoomStep([same, { ...same }], ONE_METER_PER_PIXEL, 44), 21)
  assert.equal(clusterSplitZoomStep([same], ONE_METER_PER_PIXEL, 44), 21)
  // 상한은 넘겨서 조절할 수 있다.
  assert.equal(clusterSplitZoomStep([same], ONE_METER_PER_PIXEL, 44, 3), 3)
})

test('배율을 재지 못하면 한 단계만 확대한다', () => {
  // 지도 폭이 0으로 잡히는 순간(레이아웃 전)에 0으로 나누지 않는다.
  const members = [
    { latitude: 37.5, longitude: 127 },
    { latitude: 37.5 + northSouthDegrees(1), longitude: 127 },
  ]
  assert.equal(clusterSplitZoomStep(members, 0, 44), 1)
})
