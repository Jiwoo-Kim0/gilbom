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
