import {
  expect,
  test,
  type Page,
} from '@playwright/test'

const sessionCookie = process.env.COMMAND_E2E_SESSION_COOKIE
const robotId = process.env.COMMAND_E2E_ROBOT_ID
const publishFailedCommandId = (
  process.env.COMMAND_E2E_PUBLISH_FAILED_COMMAND_ID
)

const configured = [
  sessionCookie,
  robotId,
  publishFailedCommandId,
].every((value) => (
  typeof value === 'string' && value.length > 0
))

test.skip(
  !configured,
  'Run through scripts/command-abnormal-e2e.py with an ephemeral backend.',
)

type CommandExecution = {
  commandType: string
  label: string
  expectedStatus: 'COMPLETED' | 'FAILED' | 'REJECTED' | 'EXPIRED'
  expectedStatusLabel: string
  reason?: string
  verifyIdempotentReplay?: boolean
}

async function executeCommand(
  page: Page,
  execution: CommandExecution,
): Promise<string> {
  const commandButton = page.getByRole('button', {
    name: execution.label,
    exact: true,
  })
  await expect(commandButton).toBeEnabled({ timeout: 15_000 })
  await commandButton.click()

  const confirmDialog = page.getByRole('dialog', {
    name: `${execution.label} 확인`,
  })
  await expect(confirmDialog).toBeVisible()
  if (execution.reason !== undefined) {
    await confirmDialog.getByLabel('명령 사유').fill(execution.reason)
  }

  const responsePromise = page.waitForResponse((response) => (
    response.request().method() === 'POST'
    && response.url().endsWith(`/api/v1/robots/${robotId}/commands`)
  ))
  await confirmDialog.getByRole('button', {
    name: `${execution.label} 실행`,
    exact: true,
  }).click()
  const response = await responsePromise
  expect(response.status()).toBe(201)
  const command = await response.json() as {
    commandId: string
    commandType: string
  }
  expect(command.commandType).toBe(execution.commandType)

  if (execution.verifyIdempotentReplay === true) {
    const originalRequest = response.request()
    const originalHeaders = originalRequest.headers()
    const idempotencyKey = originalHeaders['idempotency-key']
    const csrfToken = originalHeaders['x-csrf-token']
    expect(idempotencyKey).toBeTruthy()
    expect(csrfToken).toBeTruthy()
    const replayResponse = await page.request.post(
      `/api/v1/robots/${robotId}/commands`,
      {
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': idempotencyKey,
          'X-CSRF-TOKEN': csrfToken,
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
  }

  await expect(
    confirmDialog.locator('.command-created-result'),
  ).toContainText(command.commandId)
  await confirmDialog.getByRole('button', {
    name: '닫기',
    exact: true,
  }).click()

  const terminalRow = page.locator(
    `[data-command-id="${command.commandId}"]`
    + `[data-command-type="${execution.commandType}"]`
    + `[data-command-status="${execution.expectedStatus}"]`,
  )
  await expect(terminalRow).toBeVisible({
    timeout: execution.expectedStatus === 'EXPIRED' ? 50_000 : 15_000,
  })
  await terminalRow.click()

  const detail = page.getByRole('dialog', {
    name: execution.label,
    exact: true,
  })
  await expect(detail).toContainText(command.commandId)
  await expect(
    detail.locator('.command-detail-state').getByText(
      execution.expectedStatusLabel,
      { exact: true },
    ),
  ).toBeVisible()

  if (execution.expectedStatus === 'EXPIRED') {
    await expect(detail).toContainText('실행 여부가 불명확')
    await expect(detail).toContainText(
      '아직 Robot ACK 또는 최종 Result가 없습니다.',
    )
    await expect(detail.locator('[data-result-status]')).toHaveCount(0)
  } else {
    await expect(
      detail.locator('[data-result-status="ACCEPTED"]'),
    ).toBeVisible({ timeout: 10_000 })
    await expect(
      detail.locator(
        `[data-result-status="${execution.expectedStatus}"]`,
      ),
    ).toBeVisible()
  }

  await detail.getByRole('button', {
    name: '명령 상세 닫기',
  }).click()
  return command.commandId
}

test('실패·거절·발행 실패·Timeout과 늦은 Result를 구분한다', async ({
  context,
  page,
}, testInfo) => {
  test.setTimeout(100_000)

  await context.addCookies([{
    name: 'GILBOM_SESSION',
    value: sessionCookie!,
    domain: '127.0.0.1',
    path: '/',
    httpOnly: true,
    sameSite: 'Lax',
  }])
  await page.goto(`/robots/${robotId}`)

  await expect(page.getByRole('heading', { name: '명령 관리' })).toBeVisible()
  await expect(page.getByTestId('command-stream-status')).toContainText(
    '실시간 연결됨',
  )

  const publishFailedRow = page.locator(
    `[data-command-id="${publishFailedCommandId}"]`
    + '[data-command-status="PUBLISH_FAILED"]',
  )
  await expect(publishFailedRow).toBeVisible()
  await publishFailedRow.click()
  const publishFailedDetail = page.getByRole('dialog', {
    name: '임무 시작',
    exact: true,
  })
  await expect(
    publishFailedDetail.getByText('Broker 발행 실패', { exact: true }),
  ).toBeVisible()
  await expect(publishFailedDetail).toContainText('Broker 연결')
  await publishFailedDetail.getByRole('button', {
    name: '명령 상세 닫기',
  }).click()

  const completedCommandId = await executeCommand(page, {
    commandType: 'START_MISSION',
    label: '임무 시작',
    expectedStatus: 'COMPLETED',
    expectedStatusLabel: '실행 완료',
  })
  const failedCommandId = await executeCommand(page, {
    commandType: 'PAUSE_MISSION',
    label: '일시정지',
    reason: 'E2E 강제 실패',
    expectedStatus: 'FAILED',
    expectedStatusLabel: '실행 실패',
    verifyIdempotentReplay: true,
  })
  const rejectedCommandId = await executeCommand(page, {
    commandType: 'EMERGENCY_STOP',
    label: '긴급정지',
    reason: 'E2E 안전 조건 거절',
    expectedStatus: 'REJECTED',
    expectedStatusLabel: '실행 거절',
  })
  const expiredCommandId = await executeCommand(page, {
    commandType: 'CANCEL_MISSION',
    label: '임무 종료',
    reason: 'E2E Result Timeout',
    expectedStatus: 'EXPIRED',
    expectedStatusLabel: '응답 Timeout',
  })

  await page.waitForTimeout(4_000)
  const expiredResponse = await page.request.get(
    `/api/v1/commands/${expiredCommandId}`,
  )
  expect(expiredResponse.ok()).toBeTruthy()
  const expiredCommand = await expiredResponse.json() as {
    commandStatus: string
  }
  expect(expiredCommand.commandStatus).toBe('EXPIRED')

  const commandIds = [
    publishFailedCommandId!,
    completedCommandId,
    failedCommandId,
    rejectedCommandId,
    expiredCommandId,
  ]
  expect(new Set(commandIds).size).toBe(commandIds.length)

  await page.screenshot({
    path: testInfo.outputPath('command-abnormal-states.png'),
    fullPage: true,
  })
})
