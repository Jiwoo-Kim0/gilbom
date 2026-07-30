import assert from 'node:assert/strict'
import test from 'node:test'
import {
  CommandCreateAttempt,
  createRobotCommand,
  getRobotCommand,
  getRobotCommandResults,
  listRobotCommands,
} from '../src/features/command/commandApi.ts'
import type { CommandCreateRequest } from '../src/features/command/commandTypes.ts'
import {
  clearCsrfToken,
  replaceCsrfToken,
} from '../src/shared/api/csrfTokenStore.ts'

const ROBOT_ID = '10000000-0000-4000-8000-000000000001'
const OTHER_ROBOT_ID = '20000000-0000-4000-8000-000000000002'
const COMMAND_ID = '30000000-0000-4000-8000-000000000003'
const FIRST_KEY = '40000000-0000-4000-8000-000000000004'
const SECOND_KEY = '50000000-0000-4000-8000-000000000005'
const THIRD_KEY = '60000000-0000-4000-8000-000000000006'

const PAUSE_COMMAND: CommandCreateRequest = {
  commandType: 'PAUSE_MISSION',
  parameters: {
    reason: '운영자 점검',
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

test('같은 사용자 시도는 키를 유지하고 대상·입력·성공 경계에서 새 키를 만든다', () => {
  const keys = [FIRST_KEY, SECOND_KEY, THIRD_KEY]
  const attempt = new CommandCreateAttempt(() => {
    const key = keys.shift()
    assert.notEqual(key, undefined)
    return key as string
  })

  const first = attempt.keyFor(ROBOT_ID, PAUSE_COMMAND)
  assert.equal(attempt.keyFor(ROBOT_ID, {
    parameters: { reason: '운영자 점검' },
    commandType: 'PAUSE_MISSION',
  }), first)

  const changedTarget = attempt.keyFor(OTHER_ROBOT_ID, PAUSE_COMMAND)
  assert.equal(changedTarget, SECOND_KEY)

  attempt.markCompleted(changedTarget)
  assert.equal(attempt.keyFor(OTHER_ROBOT_ID, PAUSE_COMMAND), THIRD_KEY)
})

test('명령 생성 실패 뒤 재호출은 같은 Idempotency-Key와 CSRF를 전송한다', async (context) => {
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
        code: 'COMMAND_PUBLISH_UNAVAILABLE',
        message: '명령 발행을 처리하지 못했습니다.',
        requestId: COMMAND_ID,
        details: [],
      }, 503)
    }
    return jsonResponse({})
  }

  const attempt = new CommandCreateAttempt(() => FIRST_KEY)
  await assert.rejects(
    createRobotCommand(ROBOT_ID, PAUSE_COMMAND, attempt),
  )
  await createRobotCommand(ROBOT_ID, PAUSE_COMMAND, attempt)

  assert.deepEqual(sentKeys, [FIRST_KEY, FIRST_KEY])
  assert.deepEqual(sentCsrfTokens, ['csrf-token', 'csrf-token'])
})

test('Command REST Client가 네 Endpoint와 쿼리를 계약 경로로 호출한다', async (context) => {
  const originalFetch = globalThis.fetch
  const calls: Array<{
    url: string
    method: string
    idempotencyKey: string | null
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
      idempotencyKey: new Headers(init?.headers).get('Idempotency-Key'),
    })
    return jsonResponse({})
  }

  await createRobotCommand(
    ROBOT_ID,
    PAUSE_COMMAND,
    new CommandCreateAttempt(() => FIRST_KEY),
  )
  await listRobotCommands(ROBOT_ID, 2, 50)
  await getRobotCommand(COMMAND_ID)
  await getRobotCommandResults(COMMAND_ID)

  assert.deepEqual(calls, [
    {
      url: `/api/v1/robots/${ROBOT_ID}/commands`,
      method: 'POST',
      idempotencyKey: FIRST_KEY,
    },
    {
      url: `/api/v1/robots/${ROBOT_ID}/commands?page=2&size=50`,
      method: 'GET',
      idempotencyKey: null,
    },
    {
      url: `/api/v1/commands/${COMMAND_ID}`,
      method: 'GET',
      idempotencyKey: null,
    },
    {
      url: `/api/v1/commands/${COMMAND_ID}/results`,
      method: 'GET',
      idempotencyKey: null,
    },
  ])
})
