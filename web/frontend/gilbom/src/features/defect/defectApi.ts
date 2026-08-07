import { apiJson, jsonBody } from '../../shared/api/apiClient'
import type {
  DefectClusterDetails,
  DefectDetail,
  DefectFilters,
  DefectHistoryItem,
  DefectImageUrl,
  DefectListItem,
  DefectMapResponse,
  DefectMergeRequest,
  DefectMergeResult,
  DefectObservation,
  DefectStatusUpdateRequest,
  DefectSummary,
  DefectUpdateRequest,
  PageResponse,
} from './defectTypes'

const BASE_PATH = '/api/v1/defects'

/**
 * 목록·지도·요약이 같은 필터를 공유한다. 비어 있는 값은 아예 보내지 않아
 * 서버 기본값을 그대로 쓰게 한다.
 */
function toQuery(filters: DefectFilters): URLSearchParams {
  const query = new URLSearchParams()

  const q = filters.q.trim()
  if (q.length > 0) {
    query.set('q', q)
  }
  // date-time 입력은 로컬 시각이므로 UTC ISO로 바꿔 보낸다.
  if (filters.from.length > 0) {
    query.set('from', new Date(filters.from).toISOString())
  }
  if (filters.to.length > 0) {
    query.set('to', new Date(filters.to).toISOString())
  }
  for (const status of filters.workflowStatuses) {
    query.append('workflowStatuses', status)
  }
  for (const defectType of filters.defectTypes) {
    query.append('defectTypes', defectType)
  }
  for (const severity of filters.severities) {
    query.append('severities', severity)
  }
  if (filters.patrolAreaId.length > 0) {
    query.set('patrolAreaId', filters.patrolAreaId)
  }
  if (filters.patrolRouteId.length > 0) {
    query.set('patrolRouteId', filters.patrolRouteId)
  }
  if (filters.robotId.length > 0) {
    query.set('robotId', filters.robotId)
  }
  if (filters.minConfidence !== null) {
    query.set('minConfidence', filters.minConfidence.toFixed(2))
  }
  if (filters.bbox.length > 0) {
    query.set('bbox', filters.bbox)
  }
  return query
}

export function getDefects(
  page: number,
  size: number,
  sort: string,
  filters: DefectFilters,
): Promise<PageResponse<DefectListItem>> {
  const query = toQuery(filters)
  query.set('page', String(page))
  query.set('size', String(size))
  if (sort.length > 0) {
    query.set('sort', sort)
  }
  return apiJson(`${BASE_PATH}?${query}`)
}

/** 22.5 GeoJSON FeatureCollection. 지도 뷰포트가 바뀌면 bbox만 갱신해 다시 부른다. */
export function getDefectMap(
  filters: DefectFilters,
): Promise<DefectMapResponse> {
  return apiJson(`${BASE_PATH}/map?${toQuery(filters)}`)
}

export function getDefectSummary(
  filters: Pick<DefectFilters, 'from' | 'to' | 'patrolAreaId'>,
): Promise<DefectSummary> {
  const query = new URLSearchParams()
  if (filters.from.length > 0) {
    query.set('from', new Date(filters.from).toISOString())
  }
  if (filters.to.length > 0) {
    query.set('to', new Date(filters.to).toISOString())
  }
  if (filters.patrolAreaId.length > 0) {
    query.set('patrolAreaId', filters.patrolAreaId)
  }
  return apiJson(`${BASE_PATH}/summary?${query}`)
}

export function getDefect(defectId: string): Promise<DefectDetail> {
  return apiJson(`${BASE_PATH}/${defectId}`)
}

/**
 * 지도 묶음 목록의 재료를 **한 번에** 받는다.
 *
 * <p>예전에는 결함마다 상세와 이미지 URL을 따로 불러 10건짜리 묶음 하나에 스무 개가
 * 한꺼번에 나갔다. 그중 하나만 401이어도 전역 처리가 로그인을 끊었다.
 *
 * <p>`signal`로 끊을 수 있다. 목록을 닫거나 다른 묶음을 누르면 이전 요청은 취소한다 —
 * 결과만 버리면 서버 일은 그대로 남는다.
 */
export function getDefectClusterDetails(
  defectIds: string[],
  signal?: AbortSignal,
): Promise<DefectClusterDetails> {
  const query = new URLSearchParams({ defectIds: defectIds.join(',') })
  return apiJson(`${BASE_PATH}/cluster-details?${query}`, { signal })
}

export function getDefectObservations(
  defectId: string,
  page = 0,
  size = 20,
): Promise<PageResponse<DefectObservation>> {
  return apiJson(
    `${BASE_PATH}/${defectId}/observations?page=${page}&size=${size}`,
  )
}

export function getDefectHistory(
  defectId: string,
  page = 0,
  size = 20,
): Promise<PageResponse<DefectHistoryItem>> {
  return apiJson(`${BASE_PATH}/${defectId}/history?page=${page}&size=${size}`)
}

/**
 * 만료가 짧은 조회 URL을 발급받는다. 응답의 `imageUrl`을 그대로 `<img src>`에
 * 넣으면 되고, 저장소가 S3로 바뀌어도 이 호출부는 변하지 않는다.
 *
 * 이미지가 없으면 404, 저장소를 쓸 수 없으면 503이다.
 */
export function getDefectImageUrl(
  defectId: string,
  observationId?: string,
): Promise<DefectImageUrl> {
  const query = observationId ? `?observationId=${observationId}` : ''
  return apiJson(`${BASE_PATH}/${defectId}/image-url${query}`)
}

// ------------------------------------------------------------------ 변경

export function updateDefect(
  defectId: string,
  request: DefectUpdateRequest,
): Promise<DefectDetail> {
  return apiJson(`${BASE_PATH}/${defectId}`, {
    method: 'PATCH',
    body: jsonBody(request),
  })
}

export function changeDefectStatus(
  defectId: string,
  request: DefectStatusUpdateRequest,
): Promise<DefectDetail> {
  return apiJson(`${BASE_PATH}/${defectId}/status`, {
    method: 'PATCH',
    body: jsonBody(request),
  })
}

export function mergeDefects(
  request: DefectMergeRequest,
): Promise<DefectMergeResult> {
  return apiJson(`${BASE_PATH}/merge`, {
    method: 'POST',
    body: jsonBody(request),
  })
}
