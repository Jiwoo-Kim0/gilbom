import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'


const sessionCookie = process.env.MISSION_E2E_SESSION_COOKIE
const robotId = process.env.MISSION_E2E_ROBOT_ID
const missionId = process.env.MISSION_E2E_MISSION_ID
const missionCode = process.env.MISSION_E2E_MISSION_CODE
const areaCode = process.env.MISSION_E2E_AREA_CODE

const configured = [
  sessionCookie,
  robotId,
  missionId,
  missionCode,
  areaCode,
].every((value) => typeof value === 'string' && value.length > 0)

test.skip(
  !configured,
  'Run through scripts/mission-mock-e2e.py with an ephemeral backend.',
)

test('Mission Mock 전체 흐름이 TLS MQTT부터 React까지 이어진다', async ({
  context,
  page,
}, testInfo) => {
  await context.addCookies([{
    name: 'GILBOM_SESSION',
    value: sessionCookie!,
    domain: '127.0.0.1',
    path: '/',
    httpOnly: true,
    sameSite: 'Lax',
  }])
  await page.route('https://oapi.map.naver.com/**', (route) => route.abort())
  await page.goto('/missions')

  await page.getByRole('button', {
    name: `${missionCode} 상세 보기`,
  }).click()

  const detail = page.getByTestId('mission-detail')
  await expect(detail.getByText(missionCode!, { exact: true })).toBeVisible()
  await expect(
    detail.locator('.mission-status[data-status="CREATED"]'),
  ).toBeVisible()

  const csrfResponse = await page.request.get('/api/v1/auth/csrf-token')
  expect(csrfResponse.ok()).toBeTruthy()
  const csrf = await csrfResponse.json() as {
    headerName: string
    token: string
  }

  async function publishCommand(
    commandType: string,
    parameters: Record<string, string>,
  ): Promise<string> {
    const response = await page.request.post(
      `/api/v1/robots/${robotId}/commands`,
      {
        data: { commandType, parameters },
        headers: {
          [csrf.headerName]: csrf.token,
          'Idempotency-Key': randomUUID(),
        },
      },
    )
    expect(response.status()).toBe(201)
    const payload = await response.json() as { commandId: string }
    return payload.commandId
  }

  async function missionSnapshot(): Promise<{
    status: string
    elapsedSec: number | null
    progressPercent: number
    completionReason: string | null
  }> {
    const response = await page.request.get(`/api/v1/missions/${missionId}`)
    expect(response.ok()).toBeTruthy()
    return response.json()
  }

  async function waitForCommandCompletion(commandId: string) {
    await expect.poll(async () => {
      const response = await page.request.get(
        `/api/v1/commands/${commandId}`,
      )
      if (!response.ok()) {
        return `HTTP_${response.status()}`
      }
      const payload = await response.json() as { commandStatus: string }
      return payload.commandStatus
    }, { timeout: 10_000 }).toBe('COMPLETED')
  }

  const startCommandId = await publishCommand('START_MISSION', {
    missionId: missionCode!,
    zoneId: areaCode!,
  })
  await waitForCommandCompletion(startCommandId)
  await expect(
    detail.locator('.mission-status[data-status="RUNNING"]'),
  ).toBeVisible({ timeout: 10_000 })
  await expect.poll(async () => {
    const snapshot = await missionSnapshot()
    return snapshot.status === 'RUNNING'
      && (snapshot.elapsedSec ?? 0) >= 1
      && snapshot.progressPercent > 0
      && snapshot.progressPercent <= 99.99
  }, { timeout: 10_000 }).toBeTruthy()

  const pauseCommandId = await publishCommand('PAUSE_MISSION', {
    reason: 'mission mock e2e pause',
  })
  await waitForCommandCompletion(pauseCommandId)
  await expect(
    detail.locator('.mission-status[data-status="PAUSED"]'),
  ).toBeVisible({ timeout: 10_000 })
  const paused = await missionSnapshot()
  expect(paused.status).toBe('PAUSED')
  const pausedElapsed = paused.elapsedSec ?? 0
  await expect.poll(async () => {
    const snapshot = await missionSnapshot()
    return snapshot.status === 'PAUSED'
      ? (snapshot.elapsedSec ?? 0)
      : -1
  }, { timeout: 10_000 }).toBeGreaterThan(pausedElapsed)

  const resumeCommandId = await publishCommand('RESUME_MISSION', {})
  await waitForCommandCompletion(resumeCommandId)
  await expect(
    detail.locator('.mission-status[data-status="RUNNING"]'),
  ).toBeVisible({ timeout: 10_000 })

  await expect(
    detail.locator('.mission-status[data-status="COMPLETED"]'),
  ).toBeVisible({ timeout: 20_000 })
  await expect(
    page.getByTestId('mission-detail-progress').locator('strong').first(),
  ).toHaveText('100.0%')

  const completed = await missionSnapshot()
  expect(completed.status).toBe('COMPLETED')
  expect(completed.progressPercent).toBe(100)
  expect(completed.completionReason).toBe(
    'MOCK_PLANNED_DURATION_REACHED',
  )

  const historyResponse = await page.request.get(
    `/api/v1/missions/${missionId}/history`,
  )
  expect(historyResponse.ok()).toBeTruthy()
  const history = await historyResponse.json() as {
    history: Array<{ afterStatus: string; transitionSource: string }>
  }
  expect(history.history.map((item) => item.afterStatus)).toEqual([
    'CREATED',
    'RUNNING',
    'PAUSED',
    'RUNNING',
    'COMPLETED',
  ])
  expect(history.history[0].transitionSource).toBe('ADMIN')
  expect(history.history.slice(1).every(
    (item) => item.transitionSource === 'MQTT',
  )).toBeTruthy()
  await expect(
    page.getByTestId('mission-detail-history')
      .locator('.mission-history-item'),
  ).toHaveCount(5)

  const mapResponse = await page.request.get(
    `/api/v1/missions/${missionId}/map?limit=5000`,
  )
  expect(mapResponse.ok()).toBeTruthy()
  const map = await mapResponse.json() as {
    patrolAreaBoundary: { type: string }
    trajectory: unknown[]
    currentPosition: unknown | null
  }
  expect(['Polygon', 'MultiPolygon']).toContain(map.patrolAreaBoundary.type)
  expect(map.trajectory.length).toBeGreaterThan(0)
  expect(map.currentPosition).not.toBeNull()

  await page.screenshot({
    path: testInfo.outputPath('mission-mock-completed.png'),
    fullPage: true,
  })
})
