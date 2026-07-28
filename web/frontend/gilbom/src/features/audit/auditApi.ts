import { apiJson } from '../../shared/api/apiClient'
import type {
  AuditLogFilters,
  AuditLogPageResponse,
} from './auditTypes'

function appendDateTime(
  query: URLSearchParams,
  name: string,
  value: string,
): void {
  if (value.length > 0) {
    query.set(name, new Date(value).toISOString())
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
