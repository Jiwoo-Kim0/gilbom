import { createHmac } from 'node:crypto'
import { expect, test } from '@playwright/test'

function requiredEnvironment(name: string): string {
  const value = process.env[name]
  if (value === undefined || value.length === 0) {
    throw new Error(`${name} is required for the live Mission E2E`)
  }
  return value
}

function decodeBase32(value: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = ''
  for (const character of value.toUpperCase().replace(/=+$/u, '')) {
    const index = alphabet.indexOf(character)
    if (index < 0) {
      throw new Error('The live TOTP secret is not valid Base32')
    }
    bits += index.toString(2).padStart(5, '0')
  }

  const bytes: number[] = []
  for (let index = 0; index + 8 <= bits.length; index += 8) {
    bytes.push(Number.parseInt(bits.slice(index, index + 8), 2))
  }
  return Buffer.from(bytes)
}

function currentTotp(secret: string): string {
  const counter = Math.floor(Date.now() / 30_000)
  const counterBuffer = Buffer.alloc(8)
  counterBuffer.writeBigUInt64BE(BigInt(counter))
  const digest = createHmac('sha1', decodeBase32(secret))
    .update(counterBuffer)
    .digest()
  const offset = digest[digest.length - 1] & 0x0f
  const binary = digest.readUInt32BE(offset) & 0x7fffffff
  return String(binary % 1_000_000).padStart(6, '0')
}

test('실제 로그인과 PostgreSQL을 거쳐 시간 기반 Mission을 생성한다', async ({
  page,
}) => {
  const email = requiredEnvironment('MISSION_LIVE_EMAIL')
  const password = requiredEnvironment('MISSION_LIVE_PASSWORD')
  const totpSecret = requiredEnvironment('MISSION_LIVE_TOTP_SECRET')
  const robotId = requiredEnvironment('MISSION_LIVE_ROBOT_ID')
  const areaId = requiredEnvironment('MISSION_LIVE_AREA_ID')
  const routeId = requiredEnvironment('MISSION_LIVE_ROUTE_ID')

  await page.goto('/login')
  await page.locator('#login-email').fill(email)
  await page.locator('#login-password').fill(password)
  await page.locator('form.auth-form').locator('button[type="submit"]').click()
  await expect(page).toHaveURL(/\/auth\/mfa$/u)

  await page.locator('#mfa-code').fill(currentTotp(totpSecret))
  await page.locator('form.auth-form').locator('button[type="submit"]').click()
  await expect(page).toHaveURL(/\/defects$/u)

  await page.goto('/missions')
  await expect(page.locator('#mission-page-title')).toBeVisible()
  await page.locator('.mission-page-actions .primary-button').click()
  await page.locator('#mission-create-robot').selectOption(robotId)

  const routeResponse = page.waitForResponse((response) => (
    response.request().method() === 'GET'
    && response.url().includes(`/api/v1/patrol-areas/${areaId}/routes`)
  ))
  await page.locator('#mission-create-area').selectOption(areaId)
  expect((await routeResponse).status()).toBe(200)
  await page.locator('#mission-create-duration').fill('45')

  const createResponsePromise = page.waitForResponse((response) => (
    response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/missions'
  ))
  await page.locator('.mission-create-submit').click()
  const createResponse = await createResponsePromise
  expect(createResponse.status()).toBe(201)

  const requestBody = createResponse.request().postDataJSON()
  expect(requestBody).toEqual({
    robotId,
    patrolAreaId: areaId,
    patrolRouteId: routeId,
    plannedDurationSec: 2700,
    scheduledStartAt: null,
  })

  const createdMission = await createResponse.json()
  expect(createdMission).toMatchObject({
    robotId,
    patrolAreaId: areaId,
    patrolRouteId: routeId,
    plannedDurationSec: 2700,
    elapsedSec: null,
    remainingDurationSec: 2700,
    progressPercent: 0,
    status: 'CREATED',
  })
  expect(createdMission.missionCode).toMatch(/^MIS-/u)
  await expect(page.getByText(createdMission.missionCode).first()).toBeVisible()
})
