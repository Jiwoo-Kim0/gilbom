import assert from 'node:assert/strict'
import test from 'node:test'
import {
  changePatrolAreaStatus,
  deleteMission,
  getMissions,
  updatePatrolArea,
  updateMission,
} from '../src/features/mission/missionApi.ts'
import {
  clearCsrfToken,
  replaceCsrfToken,
} from '../src/shared/api/csrfTokenStore.ts'

const MISSION_ID = '25000000-0000-4000-8000-000000000002'
const PATROL_AREA_ID = '25000000-0000-4000-8000-000000000003'

test('Mission 목록 범위와 상세 상태를 조회 query로 전달한다', async (context) => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''

  context.after(() => {
    globalThis.fetch = originalFetch
  })

  globalThis.fetch = async (input) => {
    requestedUrl = String(input)
    return new Response(JSON.stringify({
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  await getMissions(0, 20, {
    robotId: '',
    patrolAreaId: '',
    scope: 'TERMINAL',
    status: 'COMPLETED',
    startedFrom: '',
    startedTo: '',
  })

  assert.equal(
    requestedUrl,
    '/api/v1/missions?page=0&size=20&scope=TERMINAL&status=COMPLETED',
  )
})

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

test('순찰 구역 정보와 상태를 각각 계약 경로로 변경한다', async (context) => {
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

  replaceCsrfToken('patrol-area-csrf')
  globalThis.fetch = async (input, init) => {
    calls.push({
      url: String(input),
      method: init?.method ?? 'GET',
      csrfToken: new Headers(init?.headers).get('X-CSRF-TOKEN'),
      body: typeof init?.body === 'string' ? init.body : null,
    })
    return new Response(JSON.stringify({ patrolAreaId: PATROL_AREA_ID }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  await updatePatrolArea(PATROL_AREA_ID, {
    areaCode: 'AREA-UPDATED',
    areaName: '수정 구역',
  })
  await changePatrolAreaStatus(PATROL_AREA_ID, 'INACTIVE')

  assert.deepEqual(calls, [
    {
      url: `/api/v1/patrol-areas/${PATROL_AREA_ID}`,
      method: 'PATCH',
      csrfToken: 'patrol-area-csrf',
      body: JSON.stringify({
        areaCode: 'AREA-UPDATED',
        areaName: '수정 구역',
      }),
    },
    {
      url: `/api/v1/patrol-areas/${PATROL_AREA_ID}/status`,
      method: 'PATCH',
      csrfToken: 'patrol-area-csrf',
      body: JSON.stringify({ lifecycleStatus: 'INACTIVE' }),
    },
  ])
})
