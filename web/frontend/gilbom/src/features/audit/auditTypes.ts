import type { PageResponse } from '../admin/adminTypes'

export type AuditResult = 'SUCCESS' | 'FAILURE' | 'DENIED'

export type AuditLogItem = {
  auditId: number
  requestId: string | null
  actorUserId: string | null
  actorType: 'USER' | 'SYSTEM' | 'ROBOT'
  actionType: string
  targetType: string
  targetId: string | null
  beforeValue: Record<string, unknown> | null
  afterValue: Record<string, unknown> | null
  reason: string | null
  result: AuditResult
  ipAddress: string | null
  userAgent: string | null
  createdAt: string
}

export type AuditLogPageResponse = PageResponse<AuditLogItem>

export type AuditLogFilters = {
  actionType: string
  targetType: string
  result: AuditResult | ''
  createdFrom: string
  createdTo: string
}
