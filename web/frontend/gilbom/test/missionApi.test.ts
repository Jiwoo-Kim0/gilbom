import assert from 'node:assert/strict'
import test from 'node:test'
import {
  deleteMission,
  updateMission,
} from '../src/features/mission/missionApi.ts'
import {
  clearCsrfToken,
  replaceCsrfToken,
} from '../src/shared/api/csrfTokenStore.ts'

const MISSION_ID = '25000000-0000-4000-8000-000000000002'

test('Mission 상태 변경과 삭제가 계약 경로·CSRF로 요청된다', async (context) => {
  const originalFetch = globalThis.fetch
  const calls: Array<{
    url: string
    method: string
    csrfToken: string | null
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
      csrfToken: new Headers(init?.headers).get('X-CSRF-TOKEN'),
      body: typeof init?.body === 'string' ? init.body : null,
    })
    if (init?.method === 'DELETE') {
      return new Response(null, { status: 204 })
    }
    return new Response(JSON.stringify({ status: 'READY' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  await updateMission(MISSION_ID, { status: 'READY' })
  await deleteMission(MISSION_ID)

  assert.deepEqual(calls, [
    {
      url: `/api/v1/missions/${MISSION_ID}`,
      method: 'PATCH',
      csrfToken: 'csrf-token',
      body: JSON.stringify({ status: 'READY' }),
    },
    {
      url: `/api/v1/missions/${MISSION_ID}`,
      method: 'DELETE',
      csrfToken: 'csrf-token',
      body: null,
    },
  ])
})
