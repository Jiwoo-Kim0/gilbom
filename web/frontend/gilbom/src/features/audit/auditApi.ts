import { apiJson } from '../../shared/api/apiClient'
import type {
  AuditLogFilters,
  AuditLogPageResponse,
} from './auditTypes'
import { seoulInputToInstant } from '../../shared/time/seoulTime.ts'

function appendDateTime(
  query: URLSearchParams,
  name: string,
  value: string,
): void {
  // 입력은 타임존이 없는 값이다. 서울 기준으로 해석해 UTC로 보낸다.
  const instant = seoulInputToInstant(value)
  if (instant !== null) {
    query.set(name, instant)
  }
}

export function getAuditLogs(
  page: number,
  size: number,
  filters: AuditLogFilters,
): Promise<AuditLogPageResponse> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  })
  const actionType = filters.actionType.trim().toUpperCase()
  const targetType = filters.targetType.trim().toUpperCase()
  if (actionType.length > 0) {
    query.set('actionType', actionType)
  }
  if (targetType.length > 0) {
    query.set('targetType', targetType)
  }
  if (filters.result.length > 0) {
    query.set('result', filters.result)
  }
  appendDateTime(query, 'createdFrom', filters.createdFrom)
  appendDateTime(query, 'createdTo', filters.createdTo)

  return apiJson(`/api/v1/audit-logs?${query}`)
}
