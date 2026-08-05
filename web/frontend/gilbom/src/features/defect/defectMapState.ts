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
