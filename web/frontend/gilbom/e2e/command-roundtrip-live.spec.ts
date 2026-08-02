import { expect, test } from '@playwright/test'


const sessionCookie = process.env.COMMAND_E2E_SESSION_COOKIE
const robotId = process.env.COMMAND_E2E_ROBOT_ID

const configured = [sessionCookie, robotId].every((value) => (
  typeof value === 'string' && value.length > 0
))

test.skip(
  !configured,
  'Run through scripts/command-roundtrip-e2e.py with an ephemeral backend.',
)

type CommandStep = {
  commandType: string
  label: string
  reason?: string
  safetyConfirmation?: boolean
}

const COMMAND_STEPS: readonly CommandStep[] = [
  {
    commandType: 'START_MISSION',
    label: '임무 시작',
  },
  {
    commandType: 'PAUSE_MISSION',
    label: '일시정지',
    reason: '정상 왕복 일시정지',
  },
  {
    commandType: 'RESUME_MISSION',
    label: '임무 재개',
    safetyConfirmation: true,
  },
  {
    commandType: 'EMERGENCY_STOP',
    label: '긴급정지',
    reason: '정상 왕복 긴급정지',
  },
  {
    commandType: 'CLEAR_EMERGENCY_STOP',
    label: '긴급정지 해제',
    reason: '정상 왕복 안전 확인',
    safetyConfirmation: true,
  },
  {
    commandType: 'RESUME_MISSION',
    label: '임무 재개',
    safetyConfirmation: true,
  },
  {
    commandType: 'CANCEL_MISSION',
    label: '임무 종료',
    reason: '정상 왕복 임무 종료',
  },
]

test('긴급정지 복구를 포함한 일곱 명령이 정상 왕복한다', async ({
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
  await page.goto(`/robots/${robotId}`)

  await expect(page.getByRole('heading', { name: '명령 관리' })).toBeVisible()
  await expect(page.getByTestId('command-stream-status')).toContainText(
    '실시간 연결됨',
  )

  const commandIds: string[] = []

  for (const step of COMMAND_STEPS) {
    const commandButton = page.getByRole('button', {
      name: step.label,
      exact: true,
    })
    await expect(commandButton).toBeEnabled({ timeout: 15_000 })
    await commandButton.click()

    const dialog = page.getByRole('dialog', {
      name: `${step.label} 확인`,
    })
    await expect(dialog).toBeVisible()

    if (step.reason !== undefined) {
      await dialog.getByLabel('명령 사유').fill(step.reason)
    }
    if (step.safetyConfirmation === true) {
      await dialog.locator('input[type="checkbox"]').check()
    }

    const responsePromise = page.waitForResponse((response) => (
      response.request().method() === 'POST'
      && response.url().endsWith(`/api/v1/robots/${robotId}/commands`)
    ))
    await dialog.getByRole('button', {
      name: `${step.label} 실행`,
      exact: true,
    }).click()
    const response = await responsePromise
    expect(response.status()).toBe(201)
    const command = await response.json() as {
      commandId: string
      commandType: string
    }
    expect(command.commandType).toBe(step.commandType)
    commandIds.push(command.commandId)

    const created = dialog.locator('.command-created-result')
    await expect(created).toContainText(command.commandId)
    await dialog.getByRole('button', {
      name: '닫기',
      exact: true,
    }).click()

    const completedRow = page.locator(
      `[data-command-id="${command.commandId}"]`
      + `[data-command-type="${step.commandType}"]`
      + '[data-command-status="COMPLETED"]',
    )
    await expect(completedRow).toBeVisible({ timeout: 15_000 })
    await completedRow.click()

    const detail = page.getByRole('dialog', {
      name: step.label,
      exact: true,
    })
    await expect(detail).toContainText(command.commandId)
    await expect(
      detail.locator('[data-result-status="ACCEPTED"]'),
    ).toBeVisible({ timeout: 10_000 })
    await expect(
      detail.locator('[data-result-status="COMPLETED"]'),
    ).toBeVisible()
    await expect(detail.getByText('Robot 수신 ACK')).toBeVisible()
    await expect(detail.getByText('최종 Result')).toBeVisible()
    await detail.getByRole('button', {
      name: '명령 상세 닫기',
    }).click()
  }

  expect(new Set(commandIds).size).toBe(COMMAND_STEPS.length)
  await expect(
    page.getByTestId('command-history')
      .locator('[data-command-status="COMPLETED"]'),
  ).toHaveCount(COMMAND_STEPS.length)

  const finalRobot = await page.request.get(`/api/v1/robots/${robotId}`)
  expect(finalRobot.ok()).toBeTruthy()
  const finalState = await finalRobot.json() as {
    robotMode: string | null
    missionStatus: string | null
  }
  expect(finalState.robotMode).toBe('READY')
  expect(finalState.missionStatus).toBeNull()

  for (const commandId of commandIds) {
    const commandResponse = await page.request.get(
      `/api/v1/commands/${commandId}`,
    )
    expect(commandResponse.ok()).toBeTruthy()
    const command = await commandResponse.json() as {
      commandStatus: string
    }
    expect(command.commandStatus).toBe('COMPLETED')

    const resultsResponse = await page.request.get(
      `/api/v1/commands/${commandId}/results`,
    )
    expect(resultsResponse.ok()).toBeTruthy()
    const resultBody = await resultsResponse.json() as {
      results: Array<{ resultStatus: string }>
    }
    expect(resultBody.results.map((result) => result.resultStatus)).toEqual([
      'ACCEPTED',
      'COMPLETED',
    ])
  }

  await page.screenshot({
    path: testInfo.outputPath('command-roundtrip-completed.png'),
    fullPage: true,
  })
})
