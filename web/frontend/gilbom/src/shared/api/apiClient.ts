import {
  clearCsrfToken,
  requireCsrfToken,
} from './csrfTokenStore.ts'

const CSRF_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
const JSON_CONTENT_TYPE = 'application/json'

export type ApiErrorCategory =
  | 'NETWORK'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'VALIDATION'
  | 'UNAVAILABLE'
  | 'UNEXPECTED'

export type ValidationErrorDetail = {
  field: string
  constraint: string
  rejectedValue: string | null
  reason: string
}

type ErrorResponse = {
  status: number
  code: string
  message: string
  requestId: string
  details: ValidationErrorDetail[]
}

export type ApiRequestInit = RequestInit & {
  notifyUnauthorized?: boolean
  /**
   * CSRF 실패와 권한 부족이 모두 403/AUTH_FORBIDDEN이므로 기본값은 재시도하지
   * 않는다. 멱등 키나 낙관적 잠금으로 중복 실행이 방지되고 body를 다시 보낼 수
   * 있는 요청만 명시적으로 opt-in한다.
   */
  csrfRetry?: 'never' | 'replay-safe'
}

type UnauthorizedListener = () => void

const unauthorizedListeners = new Set<UnauthorizedListener>()

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly category: ApiErrorCategory
  readonly requestId: string | null
  readonly details: ValidationErrorDetail[]
  readonly retryAfterSeconds: number | null
  readonly csrfRejected: boolean

  constructor({
    status,
    code,
    message,
    requestId = null,
    details = [],
    retryAfterSeconds = null,
    csrfRejected = false,
  }: {
    status: number
    code: string
    message: string
    requestId?: string | null
    details?: ValidationErrorDetail[]
    retryAfterSeconds?: number | null
    csrfRejected?: boolean
  }) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.category = categorizeApiError(status)
    this.requestId = requestId
    this.details = details
    this.retryAfterSeconds = retryAfterSeconds
    this.csrfRejected = csrfRejected
  }
}

function categorizeApiError(status: number): ApiErrorCategory {
  switch (status) {
    case 0:
      return 'NETWORK'
    case 401:
      return 'UNAUTHORIZED'
    case 403:
      return 'FORBIDDEN'
    case 404:
      return 'NOT_FOUND'
    case 409:
      return 'CONFLICT'
    case 400:
    case 413:
    case 415:
    case 422:
      return 'VALIDATION'
    case 429:
    case 502:
    case 503:
    case 504:
      return 'UNAVAILABLE'
    default:
      return 'UNEXPECTED'
  }
}

export function subscribeToUnauthorized(
  listener: UnauthorizedListener,
): () => void {
  unauthorizedListeners.add(listener)
  return () => {
    unauthorizedListeners.delete(listener)
  }
}

function notifyUnauthorized(): void {
  for (const listener of unauthorizedListeners) {
    listener()
  }
}

function parseRetryAfter(value: string | null): number | null {
  if (value === null) {
    return null
  }

  const seconds = Number(value)
  if (Number.isFinite(seconds) && seconds >= 0) {
    return Math.ceil(seconds)
  }

  const retryAt = Date.parse(value)
  if (Number.isNaN(retryAt)) {
    return null
  }
  return Math.max(0, Math.ceil((retryAt - Date.now()) / 1000))
}

function isValidationDetail(value: unknown): value is ValidationErrorDetail {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const detail = value as Partial<ValidationErrorDetail>
  return (
    typeof detail.field === 'string'
    && typeof detail.constraint === 'string'
    && (typeof detail.rejectedValue === 'string' || detail.rejectedValue === null)
    && typeof detail.reason === 'string'
  )
}

async function createApiError(
  response: Response,
  csrfRejected: boolean,
): Promise<ApiError> {
  let payload: Partial<ErrorResponse> | null = null

  try {
    const contentType = response.headers.get('Content-Type') ?? ''
    if (contentType.includes(JSON_CONTENT_TYPE)) {
      payload = (await response.json()) as Partial<ErrorResponse>
    }
  } catch {
    payload = null
  }

  const details = Array.isArray(payload?.details)
    ? payload.details.filter(isValidationDetail)
    : []

  return new ApiError({
    status: response.status,
    code: typeof payload?.code === 'string' ? payload.code : 'COMMON_HTTP_ERROR',
    message: typeof payload?.message === 'string'
      ? payload.message
      : '요청을 처리하지 못했습니다.',
    requestId: typeof payload?.requestId === 'string'
      ? payload.requestId
      : response.headers.get('X-Request-Id'),
    details,
    retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')),
    csrfRejected,
  })
}

export async function apiFetch(
  input: RequestInfo | URL,
  init: ApiRequestInit = {},
): Promise<Response> {
  const {
    notifyUnauthorized: shouldNotifyUnauthorized = true,
    csrfRetry = 'never',
    ...requestInit
  } = init
  const requestMethod = (
    requestInit.method
    ?? (input instanceof Request ? input.method : 'GET')
  ).toUpperCase()
  const headers = new Headers(requestInit.headers)
  const requiresCsrf = CSRF_METHODS.has(requestMethod)

  if (requiresCsrf) {
    try {
      headers.set('X-CSRF-TOKEN', await requireCsrfToken())
    } catch {
      clearCsrfToken()
      throw new ApiError({
        status: 0,
        code: 'AUTH_CSRF_TOKEN_UNAVAILABLE',
        message: '요청 보안 정보를 준비하지 못했습니다. 잠시 후 다시 시도해주세요.',
      })
    }
  }

  const send = async (): Promise<Response> => {
    try {
      return await fetch(input, {
        ...requestInit,
        method: requestMethod,
        headers,
        credentials: 'include',
        cache: requestInit.cache ?? 'no-store',
      })
    } catch {
      throw new ApiError({
        status: 0,
        code: 'COMMON_NETWORK_ERROR',
        message: '서버에 연결할 수 없습니다. 네트워크 상태를 확인해주세요.',
      })
    }
  }

  let response = await send()

  const hasReplayableBody = (
    requestInit.body === undefined
    || requestInit.body === null
    || typeof requestInit.body === 'string'
  )
  const canRetryCsrf = (
    response.status === 403
    && requiresCsrf
    && csrfRetry === 'replay-safe'
    && hasReplayableBody
    && !(input instanceof Request)
  )

  if (canRetryCsrf) {
    clearCsrfToken()
    try {
      headers.set('X-CSRF-TOKEN', await requireCsrfToken())
    } catch {
      clearCsrfToken()
      throw new ApiError({
        status: 0,
        code: 'AUTH_CSRF_TOKEN_UNAVAILABLE',
        message: '요청 보안 정보를 갱신하지 못했습니다. 잠시 후 다시 시도해주세요.',
      })
    }
    response = await send()
  }

  if (response.status === 401) {
    clearCsrfToken()
    if (shouldNotifyUnauthorized) {
      notifyUnauthorized()
    }
  }

  // 재시도 뒤에도 403이면 실제 권한 부족일 수 있으므로 토큰을 폐기하고 최종
  // ApiError로 변환한다. 기본 모드의 변경 요청도 기존처럼 자동 재시도하지 않는다.
  const csrfRejected = response.status === 403 && requiresCsrf
  if (csrfRejected) {
    clearCsrfToken()
  }

  if (!response.ok) {
    throw await createApiError(response, csrfRejected)
  }

  return response
}

export async function apiJson<T>(
  input: RequestInfo | URL,
  init: ApiRequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', JSON_CONTENT_TYPE)
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', JSON_CONTENT_TYPE)
  }

  const response = await apiFetch(input, {
    ...init,
    headers,
  })
  return (await response.json()) as T
}

export function jsonBody(value: unknown): string {
  return JSON.stringify(value)
}

export function getApiErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    if (error instanceof Error && error.message.length > 0) {
      return error.message
    }
    return '요청 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.'
  }

  if (error.status === 403 && error.csrfRejected) {
    return '요청 보안 정보가 만료되었거나 권한이 없습니다. 다시 시도해주세요.'
  }
  if (error.status === 413) {
    return '입력한 데이터가 허용된 크기를 초과했습니다.'
  }
  if (error.status === 429) {
    if (error.retryAfterSeconds !== null) {
      return `요청이 너무 많습니다. ${error.retryAfterSeconds}초 후 다시 시도해주세요.`
    }
    return '요청이 너무 많습니다. 잠시 후 다시 시도해주세요.'
  }

  return error.message
}
