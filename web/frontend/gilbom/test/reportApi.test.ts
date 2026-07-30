import assert from 'node:assert/strict'
import test from 'node:test'
import {
  approveReport,
  createReport,
  deleteReport,
  rejectReport,
  ReportCreateAttempt,
  submitReport,
  updateReport,
} from '../src/features/report/reportApi.ts'
import type {
  ReportCreateRequest,
} from '../src/features/report/reportTypes.ts'
import {
  clearCsrfToken,
  replaceCsrfToken,
} from '../src/shared/api/csrfTokenStore.ts'

const FIRST_UUID = '10000000-0000-4000-8000-000000000001'
const SECOND_UUID = '20000000-0000-4000-8000-000000000002'
const THIRD_UUID = '30000000-0000-4000-8000-000000000003'

const LLM_INPUT: ReportCreateRequest = {
  generationMethod: 'LLM',
  reportType: 'PERIODIC',
  periodFrom: '2026-07-01T00:00:00Z',
  periodTo: '2026-07-02T00:00:00Z',
  filterCriteria: {
    robotIds: ['40000000-0000-4000-8000-000000000004'],
  },
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json',
    },
  })
}

test('생성 입력이 같으면 키를 유지하고 변경·완료 후에는 새 키를 만든다', () => {
  const keys = [FIRST_UUID, SECOND_UUID, THIRD_UUID]
  const attempt = new ReportCreateAttempt(() => {
    const key = keys.shift()
    assert.notEqual(key, undefined)
    return key as string
  })

  const first = attempt.keyFor(LLM_INPUT)
  const reordered = attempt.keyFor({
    periodTo: LLM_INPUT.periodTo,
    periodFrom: LLM_INPUT.periodFrom,
    reportType: 'PERIODIC',
    generationMethod: 'LLM',
    filterCriteria: {
      robotIds: ['40000000-0000-4000-8000-000000000004'],
    },
  })
  assert.equal(first, FIRST_UUID)
  assert.equal(reordered, FIRST_UUID)

  const changed = attempt.keyFor({
    ...LLM_INPUT,
    periodTo: '2026-07-03T00:00:00Z',
  })
  assert.equal(changed, SECOND_UUID)

  attempt.markCompleted(FIRST_UUID)
  assert.equal(attempt.keyFor({
    ...LLM_INPUT,
    periodTo: '2026-07-03T00:00:00Z',
  }), SECOND_UUID)

  attempt.markCompleted(SECOND_UUID)
  assert.equal(attempt.keyFor({
    ...LLM_INPUT,
    periodTo: '2026-07-03T00:00:00Z',
  }), THIRD_UUID)
})

test('생성 실패 후 재호출은 동일한 Idempotency-Key를 전송한다', async (context) => {
  const originalFetch = globalThis.fetch
  const sentKeys: Array<string | null> = []
  const sentCsrfTokens: Array<string | null> = []
  let responseCount = 0

  context.after(() => {
    globalThis.fetch = originalFetch
    clearCsrfToken()
  })

  replaceCsrfToken('csrf-token')
  globalThis.fetch = async (_input, init) => {
    sentKeys.push(new Headers(init?.headers).get('Idempotency-Key'))
    sentCsrfTokens.push(new Headers(init?.headers).get('X-CSRF-TOKEN'))
    responseCount += 1
    if (responseCount === 1) {
      return jsonResponse({
        status: 503,
        code: 'LLM_UNAVAILABLE',
        message: 'LLM을 사용할 수 없습니다.',
        requestId: FIRST_UUID,
        details: [],
      }, 503)
    }
    return jsonResponse({})
  }

  const attempt = new ReportCreateAttempt(() => FIRST_UUID)
  await assert.rejects(createReport(LLM_INPUT, attempt))
  await createReport(LLM_INPUT, attempt)

  assert.deepEqual(sentKeys, [FIRST_UUID, FIRST_UUID])
  assert.deepEqual(sentCsrfTokens, ['csrf-token', 'csrf-token'])
})

test('모든 보고서 변경 요청에 CSRF와 expectedVersionNo를 전달한다', async (context) => {
  const originalFetch = globalThis.fetch
  const calls: Array<{
    url: string
    method: string
    csrf: string | null
    body: string | null
  }> = []

  context.after(() => {
    globalThis.fetch = originalFetch
    clearCsrfToken()
  })

  replaceCsrfToken('csrf-token')
  globalThis.fetch = async (input, init) => {
    calls.push({
      url: String(input),
      method: init?.method ?? 'GET',
      csrf: new Headers(init?.headers).get('X-CSRF-TOKEN'),
      body: typeof init?.body === 'string' ? init.body : null,
    })
    return init?.method === 'DELETE'
      ? new Response(null, { status: 204 })
      : jsonResponse({})
  }

  const reportId = 'report/id'
  await updateReport(reportId, 3, { title: '수정 제목' })
  await submitReport(reportId, 4)
  await approveReport(reportId, 5, '승인')
  await rejectReport(reportId, 6, '근거 보완 필요')
  await deleteReport(reportId, 7)

  assert.equal(calls.length, 5)
  for (const call of calls) {
    assert.equal(call.csrf, 'csrf-token')
  }
  assert.deepEqual(JSON.parse(calls[0].body ?? '{}'), {
    title: '수정 제목',
    expectedVersionNo: 3,
  })
  assert.deepEqual(JSON.parse(calls[1].body ?? '{}'), {
    expectedVersionNo: 4,
  })
  assert.deepEqual(JSON.parse(calls[2].body ?? '{}'), {
    expectedVersionNo: 5,
    reason: '승인',
  })
  assert.deepEqual(JSON.parse(calls[3].body ?? '{}'), {
    expectedVersionNo: 6,
    reason: '근거 보완 필요',
  })
  assert.equal(
    calls[4].url,
    '/api/v1/reports/report%2Fid?expectedVersionNo=7',
  )
})
