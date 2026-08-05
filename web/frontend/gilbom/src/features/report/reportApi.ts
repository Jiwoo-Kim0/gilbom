import {
  apiFetch,
  apiJson,
  jsonBody,
} from '../../shared/api/apiClient.ts'
import type {
  ReportCreateRequest,
  ReportDecisionRequest,
  ReportDetail,
  ReportListFilters,
  ReportListResponse,
  ReportRejectionRequest,
  ReportTransitionRequest,
  ReportUpdateChanges,
  ReportUpdateRequest,
  ReportVersion,
  ReportVersionListResponse,
} from './reportTypes.ts'

const REPORT_BASE_PATH = '/api/v1/reports'
const REPLAY_SAFE_CHANGE = {
  csrfRetry: 'replay-safe',
} as const

type UuidFactory = () => string

function createUuid(): string {
  if (
    typeof globalThis.crypto === 'undefined'
    || typeof globalThis.crypto.randomUUID !== 'function'
  ) {
    throw new Error('이 브라우저에서는 안전한 UUID를 생성할 수 없습니다.')
  }
  return globalThis.crypto.randomUUID()
}

function normalizeForFingerprint(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => normalizeForFingerprint(item))
  }
  if (typeof value !== 'object' || value === null) {
    return value
  }

  return Object.fromEntries(
    Object.entries(value)
      .filter(([, item]) => item !== undefined)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, item]) => [key, normalizeForFingerprint(item)]),
  )
}

function requestFingerprint(input: ReportCreateRequest): string {
  return JSON.stringify(normalizeForFingerprint(input))
}

/**
 * 한 번의 생성 의도를 추적한다. 네트워크·503·CSRF 재시도 중에는 동일한 키를
 * 유지하고, 입력 fingerprint가 바뀌거나 성공한 뒤에는 새 UUID를 발급한다.
 */
export class ReportCreateAttempt {
  private readonly uuidFactory: UuidFactory
  private fingerprint: string | null = null
  private idempotencyKey: string | null = null

  constructor(uuidFactory: UuidFactory = createUuid) {
    this.uuidFactory = uuidFactory
  }

  keyFor(input: ReportCreateRequest): string {
    const nextFingerprint = requestFingerprint(input)
    if (
      this.idempotencyKey === null
      || this.fingerprint !== nextFingerprint
    ) {
      this.fingerprint = nextFingerprint
      this.idempotencyKey = this.uuidFactory()
    }
    return this.idempotencyKey
  }

  markCompleted(idempotencyKey: string): void {
    if (this.idempotencyKey !== idempotencyKey) {
      return
    }
    this.fingerprint = null
    this.idempotencyKey = null
  }

  reset(): void {
    this.fingerprint = null
    this.idempotencyKey = null
  }
}

function reportPath(reportId: string): string {
  return `${REPORT_BASE_PATH}/${encodeURIComponent(reportId)}`
}

function requireExpectedVersion(expectedVersionNo: number): number {
  if (
    !Number.isSafeInteger(expectedVersionNo)
    || expectedVersionNo < 1
  ) {
    throw new RangeError('expectedVersionNo는 1 이상의 정수여야 합니다.')
  }
  return expectedVersionNo
}

export function listReports(
  filters: ReportListFilters = {},
): Promise<ReportListResponse> {
  const query = new URLSearchParams({
    page: String(filters.page ?? 0),
    size: String(filters.size ?? 20),
    sort: filters.sort ?? 'createdAt,desc',
  })
  const search = filters.search?.trim()
  if (search !== undefined && search.length > 0) {
    query.set('search', search)
  }
  if (filters.statuses !== undefined && filters.statuses.length > 0) {
    query.set('statuses', [...new Set(filters.statuses)].join(','))
  }
  if (filters.periodFrom !== undefined) {
    query.set('periodFrom', filters.periodFrom)
  }
  if (filters.periodTo !== undefined) {
    query.set('periodTo', filters.periodTo)
  }
  if (filters.reportType !== undefined) {
    query.set('reportType', filters.reportType)
  }
  if (filters.generationMethod !== undefined) {
    query.set('generationMethod', filters.generationMethod)
  }

  return apiJson(`${REPORT_BASE_PATH}?${query}`)
}

export async function createReport(
  input: ReportCreateRequest,
  attempt: ReportCreateAttempt,
): Promise<ReportDetail> {
  const idempotencyKey = attempt.keyFor(input)
  const detail = await apiJson<ReportDetail>(REPORT_BASE_PATH, {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    body: jsonBody(input),
    ...REPLAY_SAFE_CHANGE,
  })
  attempt.markCompleted(idempotencyKey)
  return detail
}

export function getReport(reportId: string): Promise<ReportDetail> {
  return apiJson(reportPath(reportId))
}

export function updateReport(
  reportId: string,
  expectedVersionNo: number,
  changes: ReportUpdateChanges,
): Promise<ReportDetail> {
  const input: ReportUpdateRequest = {
    ...changes,
    expectedVersionNo: requireExpectedVersion(expectedVersionNo),
  }
  return apiJson(reportPath(reportId), {
    method: 'PATCH',
    body: jsonBody(input),
    ...REPLAY_SAFE_CHANGE,
  })
}

export async function deleteReport(
  reportId: string,
  expectedVersionNo: number,
): Promise<void> {
  const query = new URLSearchParams({
    expectedVersionNo: String(requireExpectedVersion(expectedVersionNo)),
  })
  await apiFetch(`${reportPath(reportId)}?${query}`, {
    method: 'DELETE',
    ...REPLAY_SAFE_CHANGE,
  })
}

export function listReportVersions(
  reportId: string,
  page = 0,
  size = 20,
): Promise<ReportVersionListResponse> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  })
  return apiJson(`${reportPath(reportId)}/versions?${query}`)
}

export function getReportVersion(
  reportId: string,
  versionNo: number,
): Promise<ReportVersion> {
  return apiJson(
    `${reportPath(reportId)}/versions/${requireExpectedVersion(versionNo)}`,
  )
}

export function submitReport(
  reportId: string,
  expectedVersionNo: number,
): Promise<ReportDetail> {
  const input: ReportTransitionRequest = {
    expectedVersionNo: requireExpectedVersion(expectedVersionNo),
  }
  return apiJson(`${reportPath(reportId)}/submit`, {
    method: 'POST',
    body: jsonBody(input),
    ...REPLAY_SAFE_CHANGE,
  })
}

export function approveReport(
  reportId: string,
  expectedVersionNo: number,
  reason?: string,
): Promise<ReportDetail> {
  const input: ReportDecisionRequest = {
    expectedVersionNo: requireExpectedVersion(expectedVersionNo),
    ...(reason === undefined ? {} : { reason }),
  }
  return apiJson(`${reportPath(reportId)}/approve`, {
    method: 'POST',
    body: jsonBody(input),
    ...REPLAY_SAFE_CHANGE,
  })
}

export function rejectReport(
  reportId: string,
  expectedVersionNo: number,
  reason: string,
): Promise<ReportDetail> {
  const input: ReportRejectionRequest = {
    expectedVersionNo: requireExpectedVersion(expectedVersionNo),
    reason,
  }
  return apiJson(`${reportPath(reportId)}/reject`, {
    method: 'POST',
    body: jsonBody(input),
    ...REPLAY_SAFE_CHANGE,
  })
}
