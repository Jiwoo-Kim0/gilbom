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
 * 직선거리로는 멀지만 격자로도 이미 갈려 있다.
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
 * 묶인 마커를 클릭했을 때 **한 번에** 나뉘도록 올려야 하는 확대 단계.
 *
 * <p>묶음은 화면 좌표를 `cellPx` 격자로 나눠 만든다. 두 점이 같은 칸에 들어가면 축 방향
 * 차이가 둘 다 `cellPx`보다 작다는 뜻이므로, **가장 가까운 쌍**이 `cellPx`만큼 벌어지면
 * 갈린다. 확대 한 단계가 화면 거리를 두 배로 만들므로 필요한 단계는
 * `ceil(log2(cellPx / 지금 화면 거리))`다.
 *
 * <p>고정값(예전에는 `+2`)으로 두면 촘촘한 묶음은 확대해도 같은 숫자가 다시 나온다.
 *
 * <p>거리를 **미터로 받고 `metersPerPixel`로 나눠** 화면 거리를 만든다. 지도 SDK의
 * `fromCoordToOffset`이 무슨 단위를 주는지에 기대지 않기 위해서다(그 가정이 틀리면
 * 단계가 1로 계산돼 예전보다 더 나빠진다). `metersPerPixel`은 지도에서 직접 재서 넘긴다 —
 * 축척 바가 쓰는 값과 같다.
 *
 * @param members 묶음에 든 좌표들
 * @param metersPerPixel 지금 배율에서 1px이 몇 m인가
 * @param cellPx 묶음 격자 한 칸의 크기
 * @param maxStep 좌표가 똑같아 계산이 서지 않을 때의 상한. 지도 최대 배율은 부르는 쪽이
 *   따로 자른다.
 */
export function clusterSplitZoomStep(
  members: { latitude: number; longitude: number }[],
  metersPerPixel: number,
  cellPx: number,
  maxStep = 21,
): number {
  if (!(metersPerPixel > 0)) {
    return 1
  }
  let closestMeters = Number.POSITIVE_INFINITY
  for (let i = 0; i < members.length; i += 1) {
    for (let j = i + 1; j < members.length; j += 1) {
      closestMeters = Math.min(
        closestMeters,
        gridDistanceMeters(members[i], members[j]),
      )
    }
  }
  const closestPx = closestMeters / metersPerPixel
  // 한 점뿐이거나 좌표가 완전히 같으면 계산이 서지 않는다. 상한까지 확대한다.
  if (!Number.isFinite(closestPx) || closestPx <= 0) {
    return maxStep
  }
  /*
   * 딱 떨어지는 경우(예: 11px → ×4 = 44px)에 부동소수 오차로 `2.0000000001`이 나오면
   * `ceil`이 한 단계를 더 얹는다. 한 단계 더 확대해도 갈리기는 하지만 필요 이상으로
   * 들어가므로 아주 작은 값을 깎는다.
   */
  const needed = Math.ceil(Math.log2(cellPx / closestPx) - 1e-9)
  // 이미 갈릴 만큼 떨어져 있어도 클릭했으면 한 단계는 들어가야 반응으로 읽힌다.
  return Math.min(Math.max(needed, 1), maxStep)
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
