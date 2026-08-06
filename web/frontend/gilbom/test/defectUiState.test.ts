import assert from 'node:assert/strict'
import test from 'node:test'
import {
  bboxForDefectMapFilter,
  clusterZoomPlan,
  defectMapFilterKey,
  defectTypeTone,
} from '../src/features/defect/defectMapState.ts'
import {
  countActiveFilterGroups,
  DEFAULT_DEFECT_FILTERS,
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
 * 묶음 클릭 확대. 1px = 1m인 배율(`metersPerPixel = 1`)을 넘겨 미터 값이 그대로 화면
 * 픽셀이 되게 했다 — 기대값을 손으로 읽을 수 있다.
 */
const ONE_METER_PER_PIXEL = 1
const VIEWPORT = { widthPx: 800, heightPx: 500 }
const BASE = { latitude: 37.5, longitude: 127 }

/** 기준점에서 북쪽 `north`m, 동쪽 `east`m 떨어진 좌표. */
function offsetBy(north: number, east: number) {
  return {
    latitude: BASE.latitude + north / 111_320,
    longitude: BASE.longitude
      + east / (111_320 * Math.cos((BASE.latitude * Math.PI) / 180)),
  }
}

test('구성원 전체가 화면에 담기는 만큼만 확대한다', () => {
  /*
   * 동서로 300m 퍼진 묶음. 쓸 수 있는 폭은 800px × 0.7 = 560px이므로 1px이 최소
   * 0.536m는 덮어야 한다. 지금 1px = 1m이니 한 단계(0.5m)까지만 들어갈 수 있다.
   */
  const { step } = clusterZoomPlan(
    [offsetBy(0, 0), offsetBy(0, 150), offsetBy(0, 300)],
    ONE_METER_PER_PIXEL,
    VIEWPORT,
  )
  assert.equal(step, 1)

  // 같은 묶음을 1px = 8m인 배율에서 누르면 세 단계까지 들어간다(8 → 1m).
  const zoomedOut = clusterZoomPlan(
    [offsetBy(0, 0), offsetBy(0, 150), offsetBy(0, 300)],
    8,
    VIEWPORT,
  )
  assert.equal(zoomedOut.step, 3)   // 네 단계면 0.5m로 내려가 300m가 넘친다
})

test('확대 뒤에도 구성원이 화면 안에 남는다', () => {
  /*
   * 이 시험이 진짜 요구사항이다. 10건이 300m에 퍼져 있고 그 안에 2m 붙은 쌍이 있다 —
   * 가장 가까운 쌍을 갈라내려던 예전 방식은 최대 배율까지 뛰어 **나머지가 화면 밖으로
   * 나갔다.**
   */
  const members = [
    ...Array.from({ length: 8 }, (_, index) => offsetBy(0, index * 40)),
    offsetBy(0, 100),
    offsetBy(2, 100),
  ]
  const metersPerPixel = 8
  const { step } = clusterZoomPlan(members, metersPerPixel, VIEWPORT)

  const nextMetersPerPixel = metersPerPixel / 2 ** step
  const widthPx = (280 / nextMetersPerPixel)
  assert.ok(
    widthPx <= VIEWPORT.widthPx,
    `구성원 폭 ${Math.round(widthPx)}px이 화면 ${VIEWPORT.widthPx}px을 넘었다`,
  )
  // 담기는 한 최대한 확대하므로, 한 단계만 더 가면 넘친다.
  assert.ok((280 / (nextMetersPerPixel / 2)) > VIEWPORT.widthPx)
})

test('세로로 긴 묶음은 화면 높이에 맞춘다', () => {
  /*
   * 가로는 여유가 있어도 세로가 넘치면 구성원이 위아래로 밀려난다. 남북 300m는 높이
   * 500px × 0.7 = 350px에 담겨야 하므로 1px이 0.857m 이상이어야 한다.
   */
  const members = [offsetBy(0, 0), offsetBy(300, 10)]
  const { step } = clusterZoomPlan(members, 8, VIEWPORT)
  assert.equal(step, 3)   // 8 → 1m (0.857m보다 크다). 네 단계면 0.5m로 넘친다.
})

test('중심은 구성원을 감싸는 사각형의 중심이다', () => {
  /*
   * 평균으로 잡으면 한쪽에 몰린 구성원 쪽으로 중심이 치우쳐 반대쪽 끝이 화면 밖으로
   * 나간다. 사각형 중심이어야 양쪽 끝이 대칭으로 담긴다.
   */
  const members = [
    offsetBy(0, 0),
    offsetBy(0, 10),
    offsetBy(0, 20),
    offsetBy(0, 300),
  ]
  const { center } = clusterZoomPlan(members, 8, VIEWPORT)
  const expected = offsetBy(0, 150)
  assert.ok(Math.abs(center.longitude - expected.longitude) < 1e-9)
  assert.ok(Math.abs(center.latitude - BASE.latitude) < 1e-9)
})

test('좌표가 완전히 같으면 상한까지 확대한다', () => {
  /*
   * 감쌀 사각형이 한 점이라 계산이 서지 않는다. 그때는 상한으로 멈추고, 실제 자르기는
   * 부르는 쪽(지도 최대 배율)이 한다.
   */
  const same = { ...BASE }
  assert.equal(
    clusterZoomPlan([same, { ...same }], ONE_METER_PER_PIXEL, VIEWPORT).step,
    21,
  )
  assert.equal(
    clusterZoomPlan([same], ONE_METER_PER_PIXEL, VIEWPORT, 3).step,
    3,
  )
})

test('이미 화면을 채우고 있어도 한 단계는 확대한다', () => {
  // 클릭했는데 아무 일도 없으면 눌리지 않은 것으로 읽힌다.
  const members = [offsetBy(0, 0), offsetBy(0, 5_000)]
  assert.equal(
    clusterZoomPlan(members, ONE_METER_PER_PIXEL, VIEWPORT).step,
    1,
  )
})

test('배율이나 화면 크기를 재지 못하면 한 단계만 확대한다', () => {
  // 레이아웃 전에는 폭이 0으로 잡힌다. 0으로 나누지 않는다.
  const members = [offsetBy(0, 0), offsetBy(0, 20)]
  assert.equal(clusterZoomPlan(members, 0, VIEWPORT).step, 1)
  assert.equal(
    clusterZoomPlan(members, ONE_METER_PER_PIXEL, { widthPx: 0, heightPx: 0 })
      .step,
    1,
  )
})

/*
 * "필터" 버튼 배지. 조건이 전부 모달 안으로 들어가면서, 화면에 무엇이 걸렸는지 알리는
 * 것이 이 숫자 하나뿐이다. 고른 항목 수가 아니라 **손댄 조건 묶음 수**를 센다.
 */
test('기본 조건에서는 배지에 셀 것이 없다', () => {
  assert.equal(countActiveFilterGroups(DEFAULT_DEFECT_FILTERS), 0)
  // 검색어는 검색창에 그대로 보이므로 세지 않는다.
  assert.equal(
    countActiveFilterGroups({ ...DEFAULT_DEFECT_FILTERS, q: 'INC' }),
    0,
  )
})

test('한 묶음에서 여럿을 골라도 조건 하나로 센다', () => {
  assert.equal(
    countActiveFilterGroups({
      ...DEFAULT_DEFECT_FILTERS,
      severities: ['LOW', 'HIGH', 'CRITICAL'],
    }),
    1,
  )
  assert.equal(
    countActiveFilterGroups({
      ...DEFAULT_DEFECT_FILTERS,
      severities: ['CRITICAL'],
      defectTypes: ['DAMAGED'],
      from: '2026-08-03T00:00',
      minConfidence: 0.6,
    }),
    4,
  )
})

test('처리 상태는 기본 셋과 다를 때만 센다', () => {
  // 순서만 다른 것은 같은 조건이다.
  assert.equal(
    countActiveFilterGroups({
      ...DEFAULT_DEFECT_FILTERS,
      workflowStatuses: ['IN_PROGRESS', 'NEW', 'ACCEPTED'],
    }),
    0,
  )
  assert.equal(
    countActiveFilterGroups({
      ...DEFAULT_DEFECT_FILTERS,
      workflowStatuses: ['NEW', 'ACCEPTED', 'IN_PROGRESS', 'RESOLVED'],
    }),
    1,
  )
  // 전부 푼 것("전체 보기")도 기본과 다르다.
  assert.equal(
    countActiveFilterGroups({ ...DEFAULT_DEFECT_FILTERS, workflowStatuses: [] }),
    1,
  )
})
