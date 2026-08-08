import type {
  DefectFilters,
  DefectType,
} from './defectTypes'

export type DefectTypeTone =
  | 'damaged'
  | 'missing'
  | 'installation-invalid'
  | 'unknown-anomaly'

export type DefectMapViewport = {
  filterKey: string
  bbox: string
}

export function defectTypeTone(defectType: DefectType): DefectTypeTone {
  switch (defectType) {
    case 'DAMAGED':
      return 'damaged'
    case 'MISSING':
      return 'missing'
    case 'INSTALLATION_INVALID':
      return 'installation-invalid'
    case 'UNKNOWN_ANOMALY':
      return 'unknown-anomaly'
  }
}

/**
 * 묶음 격자에서 두 좌표가 얼마나 떨어져 있나(m).
 *
 * <p>격자가 정사각형이라 **축 방향 차이가 둘 다** 한 칸보다 작을 때만 같은 칸에 들어간다.
 * 그래서 직선거리가 아니라 **축 방향 최대값**(Chebyshev)을 본다. 남북 500m·동서 1m인 쌍은
 * 직선거리로는 멀지만 격자로는 이미 갈려 있다.
 *
 * <p>짧은 거리라 등거리 근사로 충분하다. 경도 간격이 위도에 따라 줄어드는 것만 보정한다.
 */
export function gridDistanceMeters(
  a: { latitude: number; longitude: number },
  b: { latitude: number; longitude: number },
): number {
  const centerLatitude = ((a.latitude + b.latitude) / 2) * (Math.PI / 180)
  const northSouth = Math.abs(a.latitude - b.latitude) * 111_320
  const eastWest = Math.abs(a.longitude - b.longitude) * 111_320
    * Math.cos(centerLatitude)
  return Math.max(northSouth, eastWest)
}

/**
 * 고른 결함의 마커가 **이웃과 묶이지 않을** 만큼 확대해야 하는 단계.
 *
 * <p>목록에서 결함을 고르면 그 마커로 화면을 옮기는데, 묶음 안에 들어 있으면 정작 그
 * 마커가 보이지 않는다(숫자만 보인다). 가장 가까운 이웃이 격자 한 칸(`cellPx`)만큼
 * 떨어지면 갈리므로, 확대 한 단계가 화면 거리를 두 배로 만든다는 성질에서 단계가 나온다.
 *
 * <p>이미 갈려 있으면 **0**이다 — 확대할 이유가 없다. 이웃이 아예 없으면(혼자면) 어떤
 * 배율에서도 묶이지 않으니 역시 0이다.
 *
 * @param target 고른 결함의 좌표
 * @param others 그 결함을 뺀 나머지 결함 좌표
 * @param metersPerPixel 지금 배율에서 1px이 몇 m인가(지도에서 직접 잰 값)
 * @param cellPx 묶음 격자 한 칸의 크기
 * @param maxStep 좌표가 똑같아 계산이 서지 않을 때의 상한. 지도 최대 배율은 부르는 쪽이
 *   따로 자른다.
 */
export function unclusterZoomStep(
  target: { latitude: number; longitude: number },
  others: { latitude: number; longitude: number }[],
  metersPerPixel: number,
  cellPx: number,
  maxStep = 21,
): number {
  if (!(metersPerPixel > 0) || others.length === 0) {
    return 0
  }
  let nearestMeters = Number.POSITIVE_INFINITY
  for (const other of others) {
    nearestMeters = Math.min(nearestMeters, gridDistanceMeters(target, other))
  }
  const nearestPx = nearestMeters / metersPerPixel
  // 좌표가 완전히 같으면 어떤 배율에서도 갈리지 않는다. 갈 수 있는 데까지 간다.
  if (nearestPx <= 0) {
    return maxStep
  }
  // 1e-9는 딱 떨어질 때(예: 44px) 한 단계를 더 얹지 않으려는 보정이다.
  const needed = Math.ceil(Math.log2(cellPx / nearestPx) - 1e-9)
  return Math.min(Math.max(needed, 0), maxStep)
}

/** 묶음을 클릭했을 때 어디를 얼마나 확대할지. */
export interface ClusterZoomPlan {
  /** 지금 배율에서 몇 단계 더 확대할지. 최소 1이다. */
  step: number
  /** 확대 뒤 지도 중심. 구성원을 감싸는 사각형의 중심이다. */
  center: { latitude: number; longitude: number }
}

/**
 * 화면에서 묶음이 실제로 차지하는 비율. 나머지는 여백으로 남긴다.
 *
 * <p>가장자리에 딱 붙이면 마커 아이콘(44px)이 반쯤 잘리고, 아래쪽은 상세 카드가 덮는다.
 */
const CLUSTER_FIT_RATIO = 0.7

/**
 * 묶인 마커를 클릭했을 때 확대할 단계와 중심.
 *
 * <p>기준은 **"구성원 전체가 화면에 담기는 가장 큰 배율"**이다. 그러면 두 가지가 함께
 * 지켜진다.
 *
 * <ul>
 *   <li>클릭한 묶음의 결함이 **하나도 화면 밖으로 밀려나지 않는다** — 10건을 눌렀으면
 *       확대된 화면에 (묶음이든 낱개든) 10건이 들어 있다.
 *   <li>담기는 한 최대한 확대하므로 **묶음은 갈라진다.** 구성원을 감싼 사각형이 화면의
 *       {@link CLUSTER_FIT_RATIO}만큼을 채우니, 가장 먼 쌍은 격자 한 칸(44px)보다 훨씬
 *       멀어진다.
 * </ul>
 *
 * <p>처음에는 **가장 가까운 쌍**이 갈릴 배율을 구했다. 그건 "완전히 갈리기"를 노린 것인데,
 * 촘촘한 쌍이 하나만 있어도 최대 배율까지 뛰어 **나머지 구성원이 화면 밖으로 나갔다.**
 * 완전히 갈리는 것보다 **눌러서 본 것을 잃지 않는 것**이 먼저다 — 남은 묶음은 다시 누르면
 * 된다.
 *
 * <p>배율은 미터로 계산한다. 지도 SDK의 `fromCoordToOffset`이 무슨 단위를 주는지에 기대지
 * 않기 위해서다. `metersPerPixel`은 지도에서 직접 재서 넘긴다 — 축척 바와 같은 값이다.
 *
 * @param members 묶음에 든 좌표들
 * @param metersPerPixel 지금 배율에서 1px이 몇 m인가
 * @param viewport 지도 화면 크기(px)
 * @param maxStep 좌표가 똑같아 계산이 서지 않을 때의 상한. 지도 최대 배율은 부르는 쪽이
 *   따로 자른다.
 */
export function clusterZoomPlan(
  members: { latitude: number; longitude: number }[],
  metersPerPixel: number,
  viewport: { widthPx: number; heightPx: number },
  maxStep = 21,
): ClusterZoomPlan {
  const latitudes = members.map((member) => member.latitude)
  const longitudes = members.map((member) => member.longitude)
  const south = Math.min(...latitudes)
  const north = Math.max(...latitudes)
  const west = Math.min(...longitudes)
  const east = Math.max(...longitudes)
  const center = {
    latitude: (south + north) / 2,
    longitude: (west + east) / 2,
  }

  const usableWidth = viewport.widthPx * CLUSTER_FIT_RATIO
  const usableHeight = viewport.heightPx * CLUSTER_FIT_RATIO
  if (!(metersPerPixel > 0) || usableWidth <= 0 || usableHeight <= 0) {
    // 배율이나 화면 크기를 재지 못했다. 한 단계만 확대해 최소한 반응은 남긴다.
    return { step: 1, center }
  }

  const northSouthMeters = (north - south) * 111_320
  const eastWestMeters = (east - west) * 111_320
    * Math.cos((center.latitude * Math.PI) / 180)
  /*
   * 두 축이 각각 요구하는 배율 중 **더 느슨한 쪽**을 따른다. 가로가 담기는 배율로 확대해도
   * 세로가 넘치면 구성원이 위아래로 밀려난다.
   */
  const requiredMetersPerPixel = Math.max(
    eastWestMeters / usableWidth,
    northSouthMeters / usableHeight,
  )
  // 구성원 좌표가 모두 같으면 사각형이 한 점이라 계산이 서지 않는다. 상한까지 확대한다.
  if (requiredMetersPerPixel <= 0) {
    return { step: maxStep, center }
  }

  /*
   * 확대 한 단계가 1px이 덮는 거리를 절반으로 만든다. 요구 배율보다 더 잘게 내려가면
   * 화면을 벗어나므로 `floor`다(1e-9는 딱 떨어질 때의 부동소수 오차 보정).
   */
  const fits = Math.floor(
    Math.log2(metersPerPixel / requiredMetersPerPixel) + 1e-9,
  )
  // 이미 화면을 가득 채우고 있어도 클릭했으면 한 단계는 들어가야 반응으로 읽힌다.
  return { step: Math.min(Math.max(fits, 1), maxStep), center }
}

/** 지도에 지금 보이는 범위. 지도 SDK의 bounds를 그대로 옮겨 담는다. */
export interface MapViewBounds {
  south: number
  west: number
  north: number
  east: number
}

/**
 * 마커 아이콘이 가장자리에서 잘리는 만큼을 "안 보이는 것"으로 치는 여유.
 *
 * <p>화면 폭·높이의 8%다. 마커(44px)가 절반만 걸쳐 있으면 사용자에게는 보이지 않는
 * 것과 같다.
 */
const VIEW_EDGE_MARGIN_RATIO = 0.08

/**
 * 좌표가 지금 화면 밖에 있나.
 *
 * <p>새로 탐지된 결함이 **화면 밖일 때만** 지도를 옮기려고 쓴다. 보고 있는 자리에 이미
 * 들어와 있으면 지도가 움직이지 않아야 한다 — 사용자가 보던 위치를 이유 없이 빼앗는다.
 *
 * <p>가장자리는 {@link VIEW_EDGE_MARGIN_RATIO}만큼 안쪽으로 당겨서 본다. 딱 경계에 걸린
 * 마커는 반쯤 잘려 보이지 않으므로 "밖"으로 친다.
 */
export function isPointOutsideMapView(
  point: { latitude: number; longitude: number },
  view: MapViewBounds,
): boolean {
  const latitudeMargin = (view.north - view.south) * VIEW_EDGE_MARGIN_RATIO
  const longitudeMargin = (view.east - view.west) * VIEW_EDGE_MARGIN_RATIO
  return point.latitude < view.south + latitudeMargin
    || point.latitude > view.north - latitudeMargin
    || point.longitude < view.west + longitudeMargin
    || point.longitude > view.east - longitudeMargin
}

/**
 * 확대·이동한 **뒤에** 화면이 담을 범위.
 *
 * <p>지도 SDK에 물어보지 않고 계산하는 이유는, `setZoom` 직후의 `getBounds()`가 아직
 * 이전 값일 수 있어서다. 중심과 "그 배율에서 1px이 몇 m인가"만 있으면 우리가 정할 수 있다.
 *
 * <p>범위를 못 정하면(배율·화면 크기를 못 잼) `null`이다. 부르는 쪽이 그때의 대비책을
 * 정한다 — 화면 기준으로 고를 수 없으면 원래 알던 것만 쓰는 식이다.
 */
export function viewBoundsAt(
  center: { latitude: number; longitude: number },
  metersPerPixel: number,
  viewport: { widthPx: number; heightPx: number },
): MapViewBounds | null {
  if (
    !(metersPerPixel > 0)
    || !(viewport.widthPx > 0)
    || !(viewport.heightPx > 0)
  ) {
    return null
  }
  const latitudeSpan = (metersPerPixel * viewport.heightPx) / 2 / 111_320
  const longitudeSpan = (metersPerPixel * viewport.widthPx) / 2
    / (111_320 * Math.cos((center.latitude * Math.PI) / 180))
  return {
    south: center.latitude - latitudeSpan,
    north: center.latitude + latitudeSpan,
    west: center.longitude - longitudeSpan,
    east: center.longitude + longitudeSpan,
  }
}

/** 두 좌표 사이의 대략적인 거리(m). 화면 한 장 안의 정렬용이라 평면으로 본다. */
function roughDistanceMeters(
  from: { latitude: number; longitude: number },
  to: { latitude: number; longitude: number },
): number {
  const northSouth = (to.latitude - from.latitude) * 111_320
  const eastWest = (to.longitude - from.longitude) * 111_320
    * Math.cos((from.latitude * Math.PI) / 180)
  return Math.hypot(northSouth, eastWest)
}

/**
 * 그 범위 안에 보이는 결함을, **가운데에서 가까운 순서로** 고른다.
 *
 * <p>묶음을 눌러 목록을 펼 때 쓴다. 누른 묶음의 구성원만 담으면 확대된 화면에 함께 보이는
 * 이웃 결함이 목록에 없어, 마커는 여덟 개인데 목록은 다섯 줄인 상태가 된다. **화면에
 * 보이는 것과 목록이 같아야** 목록을 훑어 지도를 읽을 수 있다.
 *
 * <p>가장자리 판정은 {@link isPointOutsideMapView}와 같다 — 반쯤 잘린 마커는 보이지 않는
 * 것으로 친다. 가운데가 누른 묶음의 자리이므로, 잘라내야 할 때 구성원이 먼저 남는다.
 */
export function featuresInView<
  T extends { geometry: { coordinates: [number, number] } },
>(
  features: readonly T[],
  view: MapViewBounds,
  center: { latitude: number; longitude: number },
  limit: number,
): T[] {
  return features
    .map((feature) => {
      const [longitude, latitude] = feature.geometry.coordinates
      return { feature, point: { latitude, longitude } }
    })
    .filter(({ point }) => !isPointOutsideMapView(point, view))
    .sort(
      (left, right) =>
        roughDistanceMeters(center, left.point)
        - roughDistanceMeters(center, right.point),
    )
    .slice(0, Math.max(limit, 0))
    .map(({ feature }) => feature)
}

/**
 * 새 결함으로 옮겨갈 때 맞출 배율.
 *
 * <p>17이면 1px이 약 1.2m라 블록 하나가 구분된다 — "어디에 생겼는지"를 알아볼 수 있는
 * 가장 얕은 배율이다. 더 깊이 넣으면 주변 맥락(어느 길인지)이 사라진다.
 */
export const REVEAL_ZOOM = 17

/**
 * 새 결함으로 옮겨갈 때 쓸 배율. **줄이지는 않는다.**
 *
 * <p>이미 더 깊이 보고 있었다면 그대로 둔다. 사용자가 확대해 둔 것을 되돌리는 것은
 * 화면을 빼앗는 것과 같다.
 */
export function revealZoom(
  currentZoom: number,
  focusZoom = REVEAL_ZOOM,
  maxZoom = 21,
): number {
  return Math.max(currentZoom, Math.min(focusZoom, maxZoom))
}

/**
 * 두 GeoJSON 결과가 **화면에 같은 마커를 그리는가**.
 *
 * <p>실시간 알림이 오면 같은 조건으로 지도를 다시 읽는다. 대개 결과가 그대로인데, 응답
 * 객체가 새것이라 마커를 전부 지우고 다시 만들었다 — 지도가 깜빡이는 원인이었다. 내용이
 * 같으면 이전 배열을 그대로 들고 있어 다시 만들지 않는다.
 *
 * <p>비교는 **마커 모양을 정하는 값만** 본다. 좌표(위치), 유형(색), 심각도·상태(설명 글).
 * 신뢰도처럼 마커에 드러나지 않는 값은 보지 않는다 — 그것까지 보면 값이 조금 흔들릴
 * 때마다 다시 그린다.
 */
export function defectMapFeaturesEqual(
  a: {
    geometry: { coordinates: [number, number] }
    properties: {
      defectId: string
      defectType: string
      riskLevel: string
      workflowStatus: string
    }
  }[],
  b: typeof a,
): boolean {
  if (a === b) {
    return true
  }
  if (a.length !== b.length) {
    return false
  }
  return a.every((left, index) => {
    const right = b[index]
    return left.properties.defectId === right.properties.defectId
      && left.properties.defectType === right.properties.defectType
      && left.properties.riskLevel === right.properties.riskLevel
      && left.properties.workflowStatus === right.properties.workflowStatus
      && left.geometry.coordinates[0] === right.geometry.coordinates[0]
      && left.geometry.coordinates[1] === right.geometry.coordinates[1]
  })
}

export function defectMapFilterKey(filters: DefectFilters): string {
  return JSON.stringify({ ...filters, bbox: '' })
}

/**
 * 지도 범위는 그 범위를 만든 필터에서만 재사용한다. 날짜·검색·상태가 바뀌면 빈
 * bbox를 반환해 서버 전체 결과로 지도를 다시 맞춘다.
 */
export function bboxForDefectMapFilter(
  viewport: DefectMapViewport,
  filterKey: string,
): string {
  return viewport.filterKey === filterKey ? viewport.bbox : ''
}
