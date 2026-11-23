import { expect, test, type Route } from '@playwright/test'

const ROBOT_ID = '81000000-0000-4000-8000-000000000001'
const AREA_ID = '81000000-0000-4000-8000-000000000002'
const ROUTE_ID = '81000000-0000-4000-8000-000000000003'
const MISSION_ID = '81000000-0000-4000-8000-000000000004'
const CREATED_MISSION_ID = '81000000-0000-4000-8000-000000000005'

const runningMission = {
  missionId: MISSION_ID,
  missionCode: 'MIS-TIME-001',
  robotId: ROBOT_ID,
  mqttId: 'robot-time-e2e',
  robotName: '시간 계약 로봇',
  patrolAreaId: AREA_ID,
  patrolAreaCode: 'AREA-TIME',
  patrolAreaName: '시간 기반 순찰 구역',
  patrolRouteId: ROUTE_ID,
  patrolRouteCode: 'AUTONOMOUS_DEFAULT',
  patrolRouteName: '자율 순찰 내부 경로',
  patrolRouteVersion: 1,
  plannedDurationSec: 3600,
  elapsedSec: 900,
  remainingDurationSec: 2700,
  progressObservedAt: '2026-07-30T00:15:00Z',
  progressStale: false,
  status: 'RUNNING',
  scheduledStartAt: null,
  startedAt: '2026-07-30T00:00:00Z',
  endedAt: null,
  progressPercent: 25,
  estimatedCompletionAt: '2026-07-30T01:00:00Z',
  completionReason: null,
  resultDurationSec: null,
  resultIncidentCount: null,
  resultNormalDetectionCount: null,
  resultPayload: null,
  createdBy: '81000000-0000-4000-8000-000000000006',
  updatedBy: '81000000-0000-4000-8000-000000000006',
  createdAt: '2026-07-30T00:00:00Z',
  updatedAt: '2026-07-30T00:15:00Z',
} as const

const createdMission = {
  ...runningMission,
  missionId: CREATED_MISSION_ID,
  missionCode: 'MIS-TIME-002',
  plannedDurationSec: 2700,
  elapsedSec: null,
  remainingDurationSec: 2700,
  progressObservedAt: null,
  progressStale: false,
  status: 'CREATED',
  startedAt: null,
  progressPercent: 0,
  estimatedCompletionAt: null,
  createdAt: '2026-07-30T00:20:00Z',
  updatedAt: '2026-07-30T00:20:00Z',
} as const

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

test('시간 기반 Mission을 경로 UI 없이 생성하고 진행 정보를 표시한다', async ({
  page,
}) => {
  let missionCreated = false
  let createPayload: Record<string, unknown> | null = null
  const requestedMissionScopes: string[] = []
  let robotCatalogRequestCount = 0

  await page.route('https://oapi.map.naver.com/**', (route) => route.abort())
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url

    if (pathname === '/api/v1/auth/session-state') {
      await fulfillJson(route, {
        authenticationState: 'AUTHENTICATED',
        challengeExpiresAt: null,
      })
      return
    }
    if (pathname === '/api/v1/auth/me') {
      await fulfillJson(route, {
        userId: '81000000-0000-4000-8000-000000000006',
        displayName: 'Mission E2E 관리자',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: [
          'MISSION_VIEW',
          'MISSION_MANAGE',
          'ROBOT_COMMAND_VIEW',
          'ROBOT_COMMAND_EXECUTE',
        ],
      })
      return
    }
    if (pathname === '/api/v1/auth/csrf-token') {
      await fulfillJson(route, {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'mission-e2e-csrf',
      })
      return
    }
    if (pathname === `/api/v1/robots/${ROBOT_ID}`) {
      await fulfillJson(route, {
        robotId: ROBOT_ID,
        mqttId: 'robot-time-e2e',
        robotName: '시간 계약 로봇',
        serialNumber: 'TIME-E2E-001',
        operationalStatus: 'ACTIVE',
        connectivityStatus: 'ONLINE',
        robotMode: 'PATROLLING',
        missionStatus: 'RUNNING',
        overallHealthStatus: 'OK',
        registeredAt: '2026-07-30T00:00:00Z',
        updatedAt: '2026-07-30T00:15:00Z',
      })
      return
    }
    if (pathname === `/api/v1/robots/${ROBOT_ID}/commands`) {
      await fulfillJson(route, {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      })
      return
    }
    if (pathname === '/api/v1/robots') {
      robotCatalogRequestCount += 1
      await fulfillJson(route, {
        content: [{
          robotId: ROBOT_ID,
          mqttId: 'robot-time-e2e',
          robotName: '시간 계약 로봇',
          serialNumber: 'TIME-E2E-001',
          operationalStatus: 'ACTIVE',
          connectivityStatus: 'ONLINE',
          missionStatus: null,
          missionAssignable: true,
          overallHealthStatus: 'NORMAL',
        }],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
      })
      return
    }
    if (pathname === '/api/v1/robots/map') {
      await fulfillJson(route, {
        robots: [{
          robotId: ROBOT_ID,
          mqttId: 'robot-time-e2e',
          robotName: '시간 계약 로봇',
          connectivityStatus: 'ONLINE',
          latitude: 37.005,
          longitude: 127.005,
          headingDeg: 45,
          missionStatus: null,
          observedAt: '2026-07-30T02:15:00Z',
        }],
      })
      return
    }
    if (pathname === '/api/v1/patrol-areas') {
      await fulfillJson(route, {
        areas: [{
          patrolAreaId: AREA_ID,
          areaCode: 'AREA-TIME',
          areaName: '시간 기반 순찰 구역',
          boundary: {
            type: 'Polygon',
            coordinates: [[[127, 37], [127.01, 37], [127.01, 37.01],
              [127, 37.01], [127, 37]]],
          },
          lifecycleStatus: 'ACTIVE',
          createdAt: '2026-07-30T00:00:00Z',
          updatedAt: '2026-07-30T00:00:00Z',
        }],
      })
      return
    }
    if (pathname === `/api/v1/patrol-areas/${AREA_ID}/routes`) {
      await fulfillJson(route, {
        routes: [{
          patrolRouteId: ROUTE_ID,
          patrolAreaId: AREA_ID,
          routeCode: 'AUTONOMOUS_DEFAULT',
          routeName: '자율 순찰 내부 경로',
          routeVersion: 1,
          routeGeometry: {
            type: 'LineString',
            coordinates: [[127.001, 37.001], [127.002, 37.002]],
          },
          lifecycleStatus: 'ACTIVE',
          createdAt: '2026-07-30T00:00:00Z',
          updatedAt: '2026-07-30T00:00:00Z',
        }],
      })
      return
    }
    if (pathname === '/api/v1/missions' && request.method() === 'POST') {
      createPayload = request.postDataJSON() as Record<string, unknown>
      expect(request.headers()['x-csrf-token']).toBe('mission-e2e-csrf')
      missionCreated = true
      await fulfillJson(route, createdMission, 201)
      return
    }
    if (pathname === '/api/v1/missions') {
      requestedMissionScopes.push(url.searchParams.get('scope') ?? '')
      const content = missionCreated
        ? [createdMission, runningMission]
        : [runningMission]
      await fulfillJson(route, {
        content,
        page: 0,
        size: 20,
        totalElements: content.length,
        totalPages: 1,
      })
      return
    }
    if (pathname.endsWith('/history')) {
      await fulfillJson(route, {
        missionId: pathname.includes(CREATED_MISSION_ID)
          ? CREATED_MISSION_ID
          : MISSION_ID,
        history: [],
      })
      return
    }
    if (pathname.endsWith('/map')) {
      const missionId = pathname.includes(CREATED_MISSION_ID)
        ? CREATED_MISSION_ID
        : MISSION_ID
      await fulfillJson(route, {
        missionId,
        patrolAreaId: AREA_ID,
        patrolAreaBoundary: {
          type: 'Polygon',
          coordinates: [[[127, 37], [127.01, 37], [127.01, 37.01],
            [127, 37.01], [127, 37]]],
        },
        trajectory: [],
        currentPosition: null,
      })
      return
    }
    if (pathname === `/api/v1/missions/${MISSION_ID}`) {
      await fulfillJson(route, runningMission)
      return
    }
    if (pathname === `/api/v1/missions/${CREATED_MISSION_ID}`) {
      await fulfillJson(route, createdMission)
      return
    }

    await route.fulfill({ status: 404, body: pathname })
  })

  await page.goto('/missions')

  await expect(page.getByRole('heading', {
    level: 2,
    name: '순찰 임무 관리',
  }))
    .toBeVisible()
  await expect(page.getByText(
    '순찰할 로봇에게 임무를 부여합니다.',
    { exact: true },
  )).toBeVisible()
  await expect(page.locator('#mission-filter-scope')).toHaveValue('ACTIVE')
  expect(requestedMissionScopes).toContain('ACTIVE')

  const missionFilter = page.locator('.mission-filter-grid')
  const missionFilterActions = missionFilter.locator('.mission-filter-actions')
  await expect(missionFilterActions).toHaveCSS('grid-column-start', '1')
  await expect(missionFilterActions).toHaveCSS('grid-column-end', '-1')
  await expect(missionFilterActions).toHaveCSS('justify-content', 'flex-end')
  const filterButtonSpacing = await missionFilterActions
    .getByRole('button')
    .evaluateAll((buttons) => buttons.map((button) => {
      const style = window.getComputedStyle(button)
      return {
        height: button.getBoundingClientRect().height,
        paddingLeft: style.paddingLeft,
        paddingRight: style.paddingRight,
      }
    }))
  expect(new Set(filterButtonSpacing.map(({ height }) => height)).size).toBe(1)
  expect(filterButtonSpacing.every(({ paddingLeft, paddingRight }) => (
    paddingLeft === paddingRight
  ))).toBe(true)
  expect(new Set(filterButtonSpacing.map(({ paddingLeft }) => paddingLeft)).size)
    .toBe(1)

  await page.locator('#mission-filter-scope').selectOption('TERMINAL')
  await page.getByRole('button', { name: '조회' }).click()
  await expect.poll(() => requestedMissionScopes.at(-1)).toBe('TERMINAL')

  await page.locator('#mission-filter-scope').selectOption('ACTIVE')
  await page.getByRole('button', { name: '조회' }).click()
  await expect.poll(() => requestedMissionScopes.at(-1)).toBe('ACTIVE')
  await expect(page.locator('.mission-master-detail')).toHaveCSS(
    'display',
    'block',
  )
  await page.getByRole('button', {
    name: 'MIS-TIME-001 상세 보기',
  }).click()
  const missionDetailDialog = page.getByRole('dialog', {
    name: 'MIS-TIME-001',
  })
  await expect(missionDetailDialog).toBeVisible()
  await expect(missionDetailDialog.locator('.mission-detail-map-primary'))
    .toBeVisible()
  await expect(missionDetailDialog.getByTestId('mission-command-actions'))
    .toBeVisible()
  const detailLayout = await missionDetailDialog.evaluate((dialog) => {
    const map = dialog.querySelector<HTMLElement>(
      '.mission-detail-map-primary',
    )
    const command = dialog.querySelector<HTMLElement>(
      '[data-testid="mission-command-actions"]',
    )
    const buttons = Array.from(dialog.querySelectorAll<HTMLElement>(
      '.mission-command-grid .command-action-button',
    ))
    const dialogRect = dialog.getBoundingClientRect()
    const mapRect = map?.getBoundingClientRect()
    const commandRect = command?.getBoundingClientRect()
    const style = window.getComputedStyle(dialog)
    return {
      paddingLeft: style.paddingLeft,
      paddingRight: style.paddingRight,
      mapLeft: mapRect ? mapRect.left - dialogRect.left : null,
      mapRight: mapRect ? dialogRect.right - mapRect.right : null,
      commandLeft: commandRect ? commandRect.left - dialogRect.left : null,
      commandRight: commandRect ? dialogRect.right - commandRect.right : null,
      buttonHeights: buttons.map((button) => button.offsetHeight),
      buttonWidths: buttons.map((button) => button.offsetWidth),
    }
  })
  expect(detailLayout.paddingLeft).toBe(detailLayout.paddingRight)
  expect(detailLayout.mapLeft).toBeCloseTo(detailLayout.mapRight ?? 0, 0)
  expect(detailLayout.commandLeft).toBeCloseTo(detailLayout.commandRight ?? 0, 0)
  expect(new Set(detailLayout.buttonHeights).size).toBe(1)
  expect(new Set(detailLayout.buttonWidths).size).toBe(1)
  expect(await missionDetailDialog.evaluate((dialog) => {
    const map = dialog.querySelector('.mission-detail-map-primary')
    const command = dialog.querySelector('[data-testid="mission-command-actions"]')
    const detailHeading = dialog.querySelector('.mission-detail-heading')
    if (!map || !command || !detailHeading) {
      return false
    }
    return Boolean(
      map.compareDocumentPosition(command) & Node.DOCUMENT_POSITION_FOLLOWING,
    ) && Boolean(
      command.compareDocumentPosition(detailHeading)
      & Node.DOCUMENT_POSITION_FOLLOWING,
    )
  })).toBe(true)
  await expect(page.getByText('25.0%').first()).toBeVisible()
  await expect(page.getByText('계획').last()).toBeVisible()
  await expect(page.getByText('1시간 0분', { exact: true })).toBeVisible()
  await expect(page.getByText('15분 0초', { exact: true })).toBeVisible()
  await expect(page.getByText('45분 0초', { exact: true })).toBeVisible()
  await expect(page.getByText('계획 경로')).toHaveCount(0)

  const completeButton = page
    .getByTestId('mission-command-actions')
    .getByRole('button', { name: '임무 완료' })
  await expect(completeButton).toBeEnabled()
  await completeButton.click()
  const completeModal = page.getByRole('dialog', { name: '임무 완료 확인' })
  const completeSafetyMessage = '로봇 주변에 위험 요소가 없고 완료 명령 후 안전하게 정지하는 절차를 확인했습니다.'
  await expect(completeModal).toBeVisible()
  await expect(completeModal.getByText(completeSafetyMessage, { exact: true }))
    .toHaveCount(1)
  await completeModal.getByRole('button', { name: '확인창 닫기' }).click()
  await missionDetailDialog.getByRole('button', {
    name: '임무 상세 창 닫기',
  }).click()
  await expect(missionDetailDialog).toBeHidden()

  await page.getByRole('button', { name: '새 임무' }).click()
  const createMissionDialog = page.getByRole('dialog', {
    name: '순찰 임무 생성',
  })
  await expect(createMissionDialog).toBeVisible()
  await expect(page.locator('body')).toHaveClass(/\bmodal-open\b/)
  const createMissionForm = createMissionDialog.locator('.mission-create-form')
  await expect(createMissionForm).toHaveCSS('justify-content', 'space-between')
  await expect(createMissionForm).toHaveCSS('align-items', 'start')
  const selectionFields = createMissionDialog.locator(
    '.mission-create-selection-fields',
  )
  await expect(selectionFields.locator('.admin-form-field')).toHaveCount(2)
  await expect(selectionFields).toHaveCSS('justify-content', 'space-between')
  await expect(selectionFields).toHaveCSS('align-items', 'start')
  await expect(selectionFields).toHaveCSS(
    'grid-template-columns',
    /\d+(?:\.\d+)?px \d+(?:\.\d+)?px/,
  )
  await expect(createMissionDialog.getByText(
    'Online상태의 Mission이 부여되지 않은 로봇만 선택이 가능합니다.',
  )).toBeVisible()
  await expect(createMissionDialog.getByText('활성 구역 선택')).toHaveCount(0)
  await expect(createMissionDialog.getByText(
    '등록된 ACTIVE 구역만 표시됩니다.',
  )).toHaveCount(0)
  await expect(createMissionDialog.getByText(
    '진행률과 예상 완료 시각 계산의 기준입니다.',
  )).toHaveCount(0)
  await expect(createMissionDialog.getByText(
    '선택 사항 · 즉시 생성 시 비워두세요.',
  )).toHaveCount(0)
  await expect.poll(() => robotCatalogRequestCount).toBeGreaterThan(1)
  await page.locator('#mission-create-robot').selectOption(ROBOT_ID)
  await page.locator('#mission-create-area').selectOption(AREA_ID)
  await page.locator('#mission-create-duration').fill('45')
  await expect(page.getByRole('button', { name: '임무 생성' })).toBeEnabled()
  await page.getByRole('button', { name: '임무 생성' }).click()
  await expect(createMissionDialog).toBeHidden()
  await expect(page.locator('body')).not.toHaveClass(/\bmodal-open\b/)

  await expect(
    page.getByRole('status').filter({
      hasText: 'MIS-TIME-002 임무를 생성했습니다.',
    }),
  ).toBeVisible()
  expect(createPayload).toEqual({
    robotId: ROBOT_ID,
    patrolAreaId: AREA_ID,
    patrolRouteId: ROUTE_ID,
    plannedDurationSec: 2700,
    scheduledStartAt: null,
  })
  await expect(page.getByText('MIS-TIME-002').first()).toBeVisible()
  await expect(page.getByText('계획 경로')).toHaveCount(0)
})
