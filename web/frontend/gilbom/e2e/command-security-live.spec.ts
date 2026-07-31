import {
  expect,
  test,
  type APIResponse,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'


const frontendUrl = (
  process.env.COMMAND_SECURITY_E2E_FRONTEND_URL
  ?? 'http://127.0.0.1:4173'
)
const robotId = process.env.COMMAND_SECURITY_E2E_ROBOT_ID
const deniedSession = process.env.COMMAND_SECURITY_E2E_DENIED_SESSION
const viewerSession = process.env.COMMAND_SECURITY_E2E_VIEWER_SESSION
const operatorSession = process.env.COMMAND_SECURITY_E2E_OPERATOR_SESSION

const configured = [
  robotId,
  deniedSession,
  viewerSession,
  operatorSession,
].every((value) => typeof value === 'string' && value.length > 0)

test.skip(
  !configured,
  'Run through scripts/command-security-e2e.py with an ephemeral backend.',
)

type ErrorResponse = {
  code: string
}

async function authenticatedContext(
  browser: Browser,
  sessionCookie: string,
): Promise<BrowserContext> {
  const context = await browser.newContext({
    baseURL: frontendUrl,
  })
  await context.addCookies([{
    name: 'GILBOM_SESSION',
    value: sessionCookie,
    domain: '127.0.0.1',
    path: '/',
    httpOnly: true,
    sameSite: 'Lax',
  }])
  return context
}

async function issueCsrf(page: Page): Promise<string> {
  const response = await page.request.get('/api/v1/auth/csrf-token')
  expect(response.status()).toBe(200)
  const body = await response.json() as { token: string }
  expect(body.token).toBeTruthy()
  return body.token
}

async function postEmergencyStop(
  page: Page,
  csrfToken?: string,
): Promise<APIResponse> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'Idempotency-Key': crypto.randomUUID(),
  }
  if (csrfToken !== undefined) {
    headers['X-CSRF-TOKEN'] = csrfToken
  }
  return page.request.post(
    `/api/v1/robots/${robotId}/commands`,
    {
      headers,
      data: {
        commandType: 'EMERGENCY_STOP',
        parameters: {
          reason: 'Command security E2E',
        },
      },
    },
  )
}

async function expectError(
  response: APIResponse,
  status: number,
  code: string,
): Promise<void> {
  expect(response.status()).toBe(status)
  const body = await response.json() as ErrorResponse
  expect(body.code).toBe(code)
}

test('권한·Robot Scope·CSRF·감사를 실제 Command 흐름에서 보호한다', async ({
  browser,
}, testInfo) => {
  test.setTimeout(90_000)

  const anonymous = await browser.newContext({ baseURL: frontendUrl })
  const anonymousPage = await anonymous.newPage()
  await expectError(
    await anonymousPage.request.get(
      `/api/v1/robots/${robotId}/commands`,
    ),
    401,
    'AUTH_UNAUTHENTICATED',
  )
  await anonymousPage.goto(`/robots/${robotId}`)
  await expect(anonymousPage).toHaveURL(/\/login$/)
  await anonymous.close()

  const denied = await authenticatedContext(browser, deniedSession!)
  const deniedPage = await denied.newPage()
  await deniedPage.goto(`/robots/${robotId}`)
  await expect(
    deniedPage.getByRole('heading', { name: '명령 관리' }),
  ).toBeVisible()
  await expect(
    deniedPage.getByText('조회 전용', { exact: true }),
  ).toBeVisible()
  await expect(
    deniedPage.getByText(
      '명령 실행 권한이 없어 조회 전용으로 표시됩니다.',
      { exact: true },
    ),
  ).toBeVisible()
  await expect(deniedPage.locator('.command-action-button')).toHaveCount(0)
  await expectError(
    await postEmergencyStop(deniedPage, await issueCsrf(deniedPage)),
    403,
    'AUTH_FORBIDDEN',
  )
  await denied.close()

  const viewer = await authenticatedContext(browser, viewerSession!)
  const viewerPage = await viewer.newPage()
  await viewerPage.goto(`/robots/${robotId}`)
  await expect(
    viewerPage.getByRole('heading', { name: '명령 관리' }),
  ).toBeVisible()
  await expect(
    viewerPage.getByText('명령 발행 가능', { exact: true }),
  ).toBeVisible()
  await expect(
    viewerPage.getByRole('button', {
      name: '임무 시작',
      exact: true,
    }),
  ).toBeEnabled()
  await expectError(
    await postEmergencyStop(viewerPage, await issueCsrf(viewerPage)),
    404,
    'ROBOT_NOT_FOUND',
  )
  await viewer.close()

  const operator = await authenticatedContext(browser, operatorSession!)
  const operatorPage = await operator.newPage()
  await operatorPage.goto(`/robots/${robotId}`)
  await expect(
    operatorPage.getByRole('heading', { name: '명령 관리' }),
  ).toBeVisible()
  await expect(
    operatorPage.getByTestId('command-stream-status'),
  ).toContainText('실시간 연결됨')

  const operatorCsrf = await issueCsrf(operatorPage)
  await expectError(
    await postEmergencyStop(operatorPage),
    403,
    'AUTH_FORBIDDEN',
  )
  await expectError(
    await postEmergencyStop(operatorPage, `${operatorCsrf}-invalid`),
    403,
    'AUTH_FORBIDDEN',
  )

  const startButton = operatorPage.getByRole('button', {
    name: '임무 시작',
    exact: true,
  })
  await expect(startButton).toBeEnabled()
  await startButton.click()
  const confirmDialog = operatorPage.getByRole('dialog', {
    name: '임무 시작 확인',
  })
  await expect(confirmDialog).toBeVisible()

  const commandResponsePromise = operatorPage.waitForResponse((response) => (
    response.request().method() === 'POST'
    && response.url().endsWith(`/api/v1/robots/${robotId}/commands`)
  ))
  await confirmDialog.getByRole('button', {
    name: '임무 시작 실행',
    exact: true,
  }).click()
  const commandResponse = await commandResponsePromise
  expect(commandResponse.status()).toBe(201)
  const command = await commandResponse.json() as {
    commandId: string
    publishAttemptCount: number
  }
  expect(command.commandId).toBeTruthy()
  expect(command.publishAttemptCount).toBe(1)

  const originalRequest = commandResponse.request()
  const requestHeaders = originalRequest.headers()
  const replayResponse = await operatorPage.request.post(
    `/api/v1/robots/${robotId}/commands`,
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': requestHeaders['idempotency-key'],
        'X-CSRF-TOKEN': requestHeaders['x-csrf-token'],
      },
      data: originalRequest.postDataJSON(),
    },
  )
  expect(replayResponse.status()).toBe(201)
  const replay = await replayResponse.json() as {
    commandId: string
    publishAttemptCount: number
  }
  expect(replay.commandId).toBe(command.commandId)
  expect(replay.publishAttemptCount).toBe(1)

  await expect(
    confirmDialog.locator('.command-created-result'),
  ).toContainText(command.commandId)
  await confirmDialog.getByRole('button', {
    name: '닫기',
    exact: true,
  }).click()
  await expect(
    operatorPage.locator(
      `[data-command-id="${command.commandId}"]`
      + '[data-command-status="COMPLETED"]',
    ),
  ).toBeVisible({ timeout: 15_000 })

  await operatorPage.screenshot({
    path: testInfo.outputPath('command-security-success.png'),
    fullPage: true,
  })
  await operator.close()
})
