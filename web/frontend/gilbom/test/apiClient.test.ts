import assert from 'node:assert/strict'
import test from 'node:test'
import {
  ApiError,
  apiFetch,
  subscribeToUnauthorized,
} from '../src/shared/api/apiClient.ts'
import {
  clearCsrfToken,
  replaceCsrfToken,
} from '../src/shared/api/csrfTokenStore.ts'

const FORBIDDEN_RESPONSE = {
  status: 403,
  code: 'AUTH_FORBIDDEN',
  message: '요청 권한이 없습니다.',
  requestId: '00000000-0000-4000-8000-000000000001',
  details: [],
}

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json',
    },
  })
}

test('재실행 안전 요청은 403 후 CSRF를 갱신해 한 번만 재시도한다', async (context) => {
  const originalFetch = globalThis.fetch
  const reportTokens: Array<string | null> = []
  let reportRequestCount = 0
  let csrfRequestCount = 0

  context.after(() => {
    globalThis.fetch = originalFetch
    clearCsrfToken()
  })

  replaceCsrfToken('expired-token')
  globalThis.fetch = async (input, init) => {
    if (String(input) === '/api/v1/auth/csrf-token') {
      csrfRequestCount += 1
      return jsonResponse({
        headerName: 'X-CSRF-TOKEN',
        token: 'refreshed-token',
      }, 200)
    }

    reportRequestCount += 1
    reportTokens.push(new Headers(init?.headers).get('X-CSRF-TOKEN'))
    return reportRequestCount === 1
      ? jsonResponse(FORBIDDEN_RESPONSE, 403)
      : jsonResponse({ ok: true }, 200)
  }

  const response = await apiFetch('/api/v1/reports/report-id/submit', {
    method: 'POST',
    body: JSON.stringify({ expectedVersionNo: 1 }),
    csrfRetry: 'replay-safe',
  })

  assert.equal(response.status, 200)
  assert.equal(reportRequestCount, 2)
  assert.equal(csrfRequestCount, 1)
  assert.deepEqual(reportTokens, ['expired-token', 'refreshed-token'])
})

test('기본 변경 요청은 403이어도 자동 재시도하지 않는다', async (context) => {
  const originalFetch = globalThis.fetch
  let requestCount = 0

  context.after(() => {
    globalThis.fetch = originalFetch
    clearCsrfToken()
  })

  replaceCsrfToken('current-token')
  globalThis.fetch = async () => {
    requestCount += 1
    return jsonResponse(FORBIDDEN_RESPONSE, 403)
  }

  await assert.rejects(
    apiFetch('/api/v1/unsafe-change', {
      method: 'POST',
      body: '{}',
    }),
    (error: unknown) => (
      error instanceof ApiError
      && error.status === 403
      && error.category === 'FORBIDDEN'
    ),
  )
  assert.equal(requestCount, 1)
})

test('공통 상태 코드를 화면 분기용 오류 범주로 변환한다', () => {
  const cases = [
    [401, 'UNAUTHORIZED'],
    [403, 'FORBIDDEN'],
    [409, 'CONFLICT'],
    [422, 'VALIDATION'],
    [503, 'UNAVAILABLE'],
  ] as const

  for (const [status, expectedCategory] of cases) {
    const error = new ApiError({
      status,
      code: 'TEST',
      message: 'test',
    })
    assert.equal(error.category, expectedCategory)
  }
})

test('401 응답은 공통 세션 만료 listener에 알린다', async (context) => {
  const originalFetch = globalThis.fetch
  let unauthorizedCount = 0
  const unsubscribe = subscribeToUnauthorized(() => {
    unauthorizedCount += 1
  })

  context.after(() => {
    globalThis.fetch = originalFetch
    unsubscribe()
    clearCsrfToken()
  })

  globalThis.fetch = async () => jsonResponse({
    status: 401,
    code: 'AUTH_UNAUTHENTICATED',
    message: '인증이 필요합니다.',
    requestId: '00000000-0000-4000-8000-000000000002',
    details: [],
  }, 401)

  await assert.rejects(
    apiFetch('/api/v1/reports'),
    (error: unknown) => (
      error instanceof ApiError
      && error.category === 'UNAUTHORIZED'
    ),
  )
  assert.equal(unauthorizedCount, 1)
})
