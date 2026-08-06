import assert from 'node:assert/strict'
import test from 'node:test'
import {
  bboxForDefectMapFilter,
  clusterZoomPlan,
  isPointOutsideMapView,
  defectMapFilterKey,
  defectTypeTone,
} from '../src/features/defect/defectMapState.ts'
import {
  cardPositionClasses,
  cardPositionFromDrag,
  cardPositionFromKey,
} from '../src/features/defect/defectCardDrag.ts'
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

/*
 * 상세 카드 끌어 옮기기. 위치는 CSP 때문에 정수 백분율 + CSS class다
 * (`defectCardDrag.ts`). 자리 자르기 규칙을 여기서 고정한다.
 */
const WIDE_MAP = {
  originLeft: 600,
  originTop: 16,
  containerWidth: 1000,
  containerHeight: 600,
  cardWidth: 340,
  cardHeight: 300,
}

test('끌면 백분율로 바뀌고 1%p 간격으로 맞춰진다', () => {
  // 600 - 300 = 300px → 30%
  assert.deepEqual(cardPositionFromDrag(WIDE_MAP, -300, 120), { x: 30, y: 23 })
  // 소수점은 반올림한다. 604px → 60.4% → 60%
  assert.equal(cardPositionFromDrag(WIDE_MAP, 4, 0).x, 60)
})

test('컨테이너 밖으로는 자른다', () => {
  const far = cardPositionFromDrag(WIDE_MAP, 9_999, 9_999)
  // 1000 - 340 - 16 = 644px → 64%
  assert.equal(far.x, 64)
  // 600 - 300 - 16 = 284px → 47%
  assert.equal(far.y, 47)

  const near = cardPositionFromDrag(WIDE_MAP, -9_999, -9_999)
  // 여백 16px은 남긴다. ceil(1.6%) = 2%, ceil(2.67%) = 3%
  assert.equal(near.x, 2)
  assert.equal(near.y, 3)
})

test('지도 높이를 꽉 채운 카드도 아래로는 움직인다', () => {
  /*
   * 기본 카드는 `top: 16; bottom: 16`이라 높이가 컨테이너-32다. "전부 보이는 자리"만
   * 허용하면 위아래로 1px도 못 움직인다. 손잡이(96px)가 남는 자리까지 넓힌다.
   */
  const tall = { ...WIDE_MAP, originTop: 16, cardHeight: 600 - 32 }
  const moved = cardPositionFromDrag(tall, 0, 200)
  assert.equal(moved.y, 36)
  // 손잡이는 남아야 한다. 600 - 96 = 504px → 84%
  assert.equal(cardPositionFromDrag(tall, 0, 9_999).y, 84)
})

test('위치 클래스는 10%p 자리와 1%p 자리로 갈린다', () => {
  assert.equal(
    cardPositionClasses({ x: 37, y: 4 }),
    'is-dragged dc-x-3 dc-xf-7 dc-y-0 dc-yf-4',
  )
  // 옮긴 적이 없으면 기본 자리다. 클래스를 붙이지 않는다.
  assert.equal(cardPositionClasses(null), '')
})

test('방향키는 2%p씩 옮기고 모르는 키는 흘려보낸다', () => {
  const at = { x: 30, y: 20 }
  assert.deepEqual(cardPositionFromKey(at, 'ArrowLeft', WIDE_MAP), { x: 28, y: 20 })
  assert.deepEqual(cardPositionFromKey(at, 'ArrowDown', WIDE_MAP), { x: 30, y: 22 })
  assert.equal(cardPositionFromKey(at, 'Enter', WIDE_MAP), null)
  // 자르기는 끌기와 같은 규칙을 쓴다.
  assert.equal(cardPositionFromKey({ x: 64, y: 20 }, 'ArrowRight', WIDE_MAP).x, 64)
})

/*
 * 새로 탐지된 결함이 화면 밖인지 판단하는 규칙. 지도 한 화면이 위경도 0.01도(약 1.1km)를
 * 덮는다고 두고 본다.
 */
const VIEW = { south: 37.495, west: 127.04, north: 37.505, east: 127.05 }

test('화면 가운데 있는 좌표는 밖이 아니다', () => {
  assert.equal(
    isPointOutsideMapView({ latitude: 37.5, longitude: 127.045 }, VIEW),
    false,
  )
})

test('화면 범위를 벗어난 좌표는 밖이다', () => {
  // 위·아래·좌·우 네 방향을 모두 본다. 한 방향만 보면 부호 실수를 놓친다.
  assert.equal(
    isPointOutsideMapView({ latitude: 37.52, longitude: 127.045 }, VIEW), true)
  assert.equal(
    isPointOutsideMapView({ latitude: 37.48, longitude: 127.045 }, VIEW), true)
  assert.equal(
    isPointOutsideMapView({ latitude: 37.5, longitude: 127.02 }, VIEW), true)
  assert.equal(
    isPointOutsideMapView({ latitude: 37.5, longitude: 127.07 }, VIEW), true)
})

test('가장자리에 걸친 좌표는 밖으로 친다', () => {
  /*
   * 범위 안이긴 하지만 경계에서 8%(여기서는 0.0008도, 약 90m) 안쪽까지는 마커가 반쯤
   * 잘려 보이지 않는다. 그대로 두면 "새 결함이 왔다는데 어디 있는지 안 보인다"가 된다.
   */
  assert.equal(
    isPointOutsideMapView({ latitude: 37.5049, longitude: 127.045 }, VIEW),
    true,
  )
  // 여유 밖으로 들어오면 보이는 것으로 본다.
  assert.equal(
    isPointOutsideMapView({ latitude: 37.5040, longitude: 127.045 }, VIEW),
    false,
  )
})
