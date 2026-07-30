import { expect, test, type Route } from '@playwright/test'

const USER_ID = '83000000-0000-4000-8000-000000000001'
const ROBOT_ID = '83000000-0000-4000-8000-000000000002'
const BUSY_ROBOT_ID = '83000000-0000-4000-8000-000000000006'
const PROVISIONING_ROBOT_ID = '83000000-0000-4000-8000-000000000007'
const AREA_ID = '83000000-0000-4000-8000-000000000003'
const ROUTE_ID = '83000000-0000-4000-8000-000000000004'
const MISSION_ID = '83000000-0000-4000-8000-000000000005'

const orderedBoundaryCoordinates = [
  [128.4160, 36.1070],
  [128.4170, 36.1070],
  [128.4170, 36.1080],
  [128.4160, 36.1080],
] as const

const clickedCoordinates = [
  orderedBoundaryCoordinates[2],
  orderedBoundaryCoordinates[0],
  orderedBoundaryCoordinates[3],
  orderedBoundaryCoordinates[1],
] as const

const createdArea = {
  patrolAreaId: AREA_ID,
  areaCode: 'DEMO-ZONE-01',
  areaName: '시연 순찰 구역',
  boundary: {
    type: 'Polygon',
    coordinates: [[
      ...orderedBoundaryCoordinates,
      orderedBoundaryCoordinates[0],
    ]],
  },
  lifecycleStatus: 'ACTIVE',
  createdAt: '2026-07-30T09:00:00Z',
  updatedAt: '2026-07-30T09:00:00Z',
} as const

const createdMission = {
  missionId: MISSION_ID,
  missionCode: 'MIS-PATROL-AREA-E2E',
  robotId: ROBOT_ID,
  mqttId: 'robot-patrol-area-e2e',
  robotName: '순찰 구역 검증 로봇',
  patrolAreaId: AREA_ID,
  patrolAreaCode: createdArea.areaCode,
  patrolAreaName: createdArea.areaName,
  patrolRouteId: ROUTE_ID,
  patrolRouteCode: 'AUTONOMOUS_DEFAULT',
  patrolRouteName: '자율 순찰 내부 경로',
  patrolRouteVersion: 1,
  plannedDurationSec: 3600,
  elapsedSec: null,
  remainingDurationSec: 3600,
  progressObservedAt: null,
  progressStale: false,
  status: 'CREATED',
  scheduledStartAt: null,
  startedAt: null,
  endedAt: null,
  progressPercent: 0,
  estimatedCompletionAt: null,
  completionReason: null,
  resultDurationSec: null,
  resultIncidentCount: null,
  resultNormalDetectionCount: null,
  resultPayload: null,
  createdBy: USER_ID,
  updatedBy: USER_ID,
  createdAt: '2026-07-30T09:01:00Z',
  updatedAt: '2026-07-30T09:01:00Z',
} as const

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

test('전용 페이지에서 순찰 구역을 등록하고 Mission 생성에서 선택한다', async ({
  page,
}) => {
  let areaCreated = false
  let missionCreated = false
  let routeRequested = false
  let credentialIssued = false
  let createPayload: Record<string, unknown> | null = null
  let missionCreatePayload: Record<string, unknown> | null = null

  await page.addInitScript((coordinates) => {
    type FakeMapInteractionOptions = {
      draggable?: boolean
      pinchZoom?: boolean
      scrollWheel?: boolean
      keyboardShortcuts?: boolean
      disableDoubleClickZoom?: boolean
      disableDoubleTapZoom?: boolean
      disableTwoFingerTapZoom?: boolean
      minZoom?: number
      maxZoom?: number
      zoom?: number
      zoomControl?: boolean
    }
    const previewState = {
      centers: [] as Array<[longitude: number, latitude: number]>,
      mapOptions: [] as FakeMapInteractionOptions[],
      setZoomCallCount: 0,
      fitBoundsCallCount: 0,
    }

    class FakeLatLng {
      constructor(
        private readonly latitude: number,
        private readonly longitude: number,
      ) {}

      lat() {
        return this.latitude
      }

      lng() {
        return this.longitude
      }
    }

    class FakePoint {
      constructor(
        readonly x: number,
        readonly y: number,
      ) {}
    }

    class FakeMap {
      readonly element: HTMLElement
      clickIndex = 0

      constructor(
        element: HTMLElement,
        options: FakeMapInteractionOptions,
      ) {
        this.element = element
        previewState.mapOptions.push({ ...options })
      }

      setCenter(position: FakeLatLng) {
        previewState.centers.push([position.lng(), position.lat()])
      }
      setZoom() {
        previewState.setZoomCallCount += 1
      }

      fitBounds() {
        previewState.fitBoundsCallCount += 1
      }

      getProjection() {
        return {
          fromCoordToOffset(position: FakeLatLng) {
            return new FakePoint(
              (position.lng() - 128.415) * 100_000,
              (36.109 - position.lat()) * 100_000,
            )
          },
        }
      }
      destroy() {}
    }

    class FakeOverlay {
      setMap() {}
    }

    class FakeEventSource {
      onopen: ((event: Event) => void) | null = null
      onerror: ((event: Event) => void) | null = null

      addEventListener() {}
      close() {}
    }

    type ClickHandle = {
      element: HTMLElement
      handler: (() => void) | null
    }

    Object.defineProperty(window, 'EventSource', {
      configurable: true,
      value: FakeEventSource,
    })
    Object.defineProperty(window, '__patrolAreaPreviewState', {
      configurable: true,
      value: previewState,
    })
    Object.defineProperty(window, 'naver', {
      configurable: true,
      value: {
        maps: {
          LatLng: FakeLatLng,
          LatLngBounds: class FakeLatLngBounds {},
          Map: FakeMap,
          Polygon: FakeOverlay,
          Polyline: FakeOverlay,
          Marker: FakeOverlay,
          Size: class FakeSize {},
          Point: FakePoint,
          Position: { TOP_RIGHT: 1 },
          Event: {
            addListener(
              map: FakeMap,
              eventName: string,
              listener: (event: { coord: FakeLatLng }) => void,
            ): ClickHandle {
              if (eventName !== 'click') {
                return { element: map.element, handler: null }
              }
              const handler = () => {
                const coordinate = coordinates[
                  Math.min(map.clickIndex, coordinates.length - 1)
                ]
                map.clickIndex += 1
                listener({
                  coord: new FakeLatLng(coordinate[1], coordinate[0]),
                })
              }
              map.element.addEventListener('click', handler)
              return { element: map.element, handler }
            },
            removeListener(handle: ClickHandle) {
              if (handle.handler !== null) {
                handle.element.removeEventListener('click', handle.handler)
              }
            },
          },
        },
      },
    })
  }, clickedCoordinates)

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname

    if (pathname === '/api/v1/auth/session-state') {
      await fulfillJson(route, {
        authenticationState: 'AUTHENTICATED',
        challengeExpiresAt: null,
      })
      return
    }
    if (pathname === '/api/v1/auth/me') {
      await fulfillJson(route, {
        userId: USER_ID,
        displayName: '순찰 구역 E2E 관리자',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: ['MISSION_VIEW', 'MISSION_MANAGE', 'ROBOT_REGISTER'],
      })
      return
    }
    if (pathname === '/api/v1/auth/csrf-token') {
      await fulfillJson(route, {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'patrol-area-e2e-csrf',
      })
      return
    }
    if (pathname === '/api/v1/robots') {
      await fulfillJson(route, {
        content: [{
          robotId: ROBOT_ID,
          mqttId: 'robot-patrol-area-e2e',
          robotName: '순찰 구역 검증 로봇',
          serialNumber: 'PATROL-AREA-E2E-001',
          operationalStatus: 'ACTIVE',
          connectivityStatus: 'ONLINE',
          missionStatus: null,
          overallHealthStatus: 'NORMAL',
        }, {
          robotId: BUSY_ROBOT_ID,
          mqttId: 'robot-busy-e2e',
          robotName: '임무 수행 중 로봇',
          serialNumber: 'BUSY-E2E-001',
          operationalStatus: 'ACTIVE',
          connectivityStatus: 'ONLINE',
          missionStatus: 'RUNNING',
          overallHealthStatus: 'NORMAL',
        }, {
          robotId: PROVISIONING_ROBOT_ID,
          mqttId: 'robot-provisioning-e2e',
          robotName: '활성화 대기 로봇',
          serialNumber: 'PROVISIONING-E2E-001',
          operationalStatus: credentialIssued ? 'ACTIVE' : 'PROVISIONING',
          connectivityStatus: 'OFFLINE',
          missionStatus: null,
          overallHealthStatus: 'UNKNOWN',
        }],
        page: 0,
        size: 100,
        totalElements: 3,
        totalPages: 1,
      })
      return
    }
    if (
      pathname
        === `/api/v1/robots/${PROVISIONING_ROBOT_ID}/credentials`
      && request.method() === 'POST'
    ) {
      expect(request.headers()['x-csrf-token']).toBe('patrol-area-e2e-csrf')
      expect(request.headers()['idempotency-key']).toMatch(
        /^[0-9a-f-]{36}$/u,
      )
      credentialIssued = true
      await fulfillJson(route, {
        credentialId: '83000000-0000-4000-8000-000000000008',
        credentialType: 'PASSWORD',
        connection: {
          brokerUri: 'mqtts://127.0.0.1:8883',
          username: 'robot-provisioning-e2e',
          password: 'one-time-password',
          clientId: 'robot-provisioning-e2e',
        },
        expiresAt: null,
      }, 201)
      return
    }
    if (pathname === '/api/v1/robots/map') {
      await fulfillJson(route, {
        robots: [{
          robotId: ROBOT_ID,
          mqttId: 'robot-patrol-area-e2e',
          robotName: '순찰 구역 검증 로봇',
          connectivityStatus: 'ONLINE',
          latitude: 36.1074,
          longitude: 128.4164,
          headingDeg: 90,
          missionStatus: null,
          observedAt: '2026-07-30T12:00:00Z',
        }],
      })
      return
    }
    if (
      pathname === '/api/v1/patrol-areas'
      && request.method() === 'POST'
    ) {
      createPayload = request.postDataJSON() as Record<string, unknown>
      expect(request.headers()['x-csrf-token']).toBe('patrol-area-e2e-csrf')
      areaCreated = true
      await fulfillJson(route, createdArea, 201)
      return
    }
    if (pathname === '/api/v1/patrol-areas') {
      await fulfillJson(route, {
        areas: areaCreated ? [createdArea] : [],
      })
      return
    }
    if (pathname === `/api/v1/patrol-areas/${AREA_ID}/routes`) {
      routeRequested = true
      await fulfillJson(route, {
        routes: [{
          patrolRouteId: ROUTE_ID,
          patrolAreaId: AREA_ID,
          routeCode: 'AUTONOMOUS_DEFAULT',
          routeName: '자율 순찰 내부 경로',
          routeVersion: 1,
          routeGeometry: {
            type: 'LineString',
            coordinates: [
              orderedBoundaryCoordinates[0],
              orderedBoundaryCoordinates[1],
            ],
          },
          lifecycleStatus: 'ACTIVE',
          createdAt: '2026-07-30T09:00:00Z',
          updatedAt: '2026-07-30T09:00:00Z',
        }],
      })
      return
    }
    if (
      pathname === '/api/v1/missions'
      && request.method() === 'POST'
    ) {
      missionCreatePayload = request.postDataJSON() as Record<string, unknown>
      expect(request.headers()['x-csrf-token']).toBe('patrol-area-e2e-csrf')
      missionCreated = true
      await fulfillJson(route, createdMission, 201)
      return
    }
    if (pathname === '/api/v1/missions') {
      const content = missionCreated ? [createdMission] : []
      await fulfillJson(route, {
        content,
        page: 0,
        size: 20,
        totalElements: content.length,
        totalPages: content.length === 0 ? 0 : 1,
      })
      return
    }
    if (pathname === `/api/v1/missions/${MISSION_ID}`) {
      await fulfillJson(route, createdMission)
      return
    }
    if (pathname === `/api/v1/missions/${MISSION_ID}/history`) {
      await fulfillJson(route, {
        missionId: MISSION_ID,
        history: [],
      })
      return
    }
    if (pathname === `/api/v1/missions/${MISSION_ID}/map`) {
      await fulfillJson(route, {
        missionId: MISSION_ID,
        patrolAreaId: AREA_ID,
        patrolAreaBoundary: createdArea.boundary,
        trajectory: [],
        currentPosition: null,
      })
      return
    }

    await route.fulfill({ status: 404, body: pathname })
  })

  await page.goto('/patrol-areas')
  await expect(
    page.getByRole('heading', { level: 2, name: '순찰 구역 관리' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '새 구역 등록' }).click()

  const editor = page.getByTestId('patrol-area-boundary-editor')
  await expect(editor).toBeVisible()
  const map = page.getByTestId('patrol-boundary-naver-map')
  await expect(map).toBeVisible()
  await expect.poll(() => page.evaluate(() => {
    const state = (
      window as typeof window & {
        __patrolAreaPreviewState: {
          mapOptions: Array<{
            pinchZoom?: boolean
            scrollWheel?: boolean
            keyboardShortcuts?: boolean
            disableDoubleClickZoom?: boolean
            disableDoubleTapZoom?: boolean
            disableTwoFingerTapZoom?: boolean
            minZoom?: number
            maxZoom?: number
            zoom?: number
            zoomControl?: boolean
          }>
        }
      }
    ).__patrolAreaPreviewState
    return state.mapOptions.at(0)
  })).toMatchObject({
    zoom: 17,
    minZoom: 17,
    maxZoom: 17,
    pinchZoom: false,
    scrollWheel: false,
    keyboardShortcuts: false,
    disableDoubleClickZoom: true,
    disableDoubleTapZoom: true,
    disableTwoFingerTapZoom: true,
    zoomControl: false,
  })

  await map.click({ position: { x: 80, y: 80 } })
  await map.click({ position: { x: 180, y: 80 } })
  await map.click({ position: { x: 180, y: 180 } })
  await map.click({ position: { x: 80, y: 180 } })
  await expect(
    editor.getByText('교차 없이 자동 정렬된 Polygon입니다.'),
  ).toBeVisible()
  await expect(editor.getByText('4/100개 점')).toBeVisible()
  const projectedOverlay = editor.getByTestId(
    'patrol-boundary-projected-overlay',
  )
  await expect(projectedOverlay.locator('polygon')).toHaveAttribute(
    'points',
    '100,200 200,200 200,100 100,100',
  )
  await expect(projectedOverlay.locator('polygon')).toHaveCSS(
    'fill-opacity',
    '0.1',
  )
  await expect(projectedOverlay.locator('circle')).toHaveCount(4)
  await expect.poll(() => page.evaluate(() => {
    const state = (
      window as typeof window & {
        __patrolAreaPreviewState: {
          centers: Array<[longitude: number, latitude: number]>
          setZoomCallCount: number
          fitBoundsCallCount: number
        }
      }
    ).__patrolAreaPreviewState
    return {
      centers: state.centers,
      setZoomCallCount: state.setZoomCallCount,
      fitBoundsCallCount: state.fitBoundsCallCount,
    }
  })).toEqual({
    centers: [],
    setZoomCallCount: 0,
    fitBoundsCallCount: 0,
  })

  await page.locator('#patrol-area-code').fill('DEMO-ZONE-01')
  await page.locator('#patrol-area-name').fill('시연 순찰 구역')
  await page.getByRole('button', { name: '순찰 구역 등록' }).click()

  await expect(
    page.getByRole('status').filter({
      hasText: '시연 순찰 구역 (DEMO-ZONE-01) 구역을 ACTIVE로 등록했습니다.',
    }),
  ).toBeVisible()
  expect(createPayload).toEqual({
    areaCode: 'DEMO-ZONE-01',
    areaName: '시연 순찰 구역',
    boundary: {
      type: 'Polygon',
      coordinates: [[
        ...orderedBoundaryCoordinates,
        orderedBoundaryCoordinates[0],
      ]],
    },
    lifecycleStatus: 'ACTIVE',
  })

  const registeredAreaRow = page.getByRole('row').filter({
    hasText: '시연 순찰 구역',
  })
  await expect(registeredAreaRow).toContainText('DEMO-ZONE-01')
  await expect(registeredAreaRow).toContainText('선택 가능')

  await page.getByRole('link', { name: '순찰 임무 관리' }).click()
  await page.getByRole('button', { name: '새 임무' }).click()
  await page.locator('#mission-create-area').selectOption(AREA_ID)
  await expect(page.locator('#mission-create-area')).toHaveValue(AREA_ID)
  await expect.poll(() => routeRequested).toBe(true)
  const areaPreview = page.getByTestId('mission-area-selection-preview')
  await expect(areaPreview).toBeVisible()
  await expect(areaPreview).toContainText('시연 순찰 구역 선택 구역')
  await expect(areaPreview).toContainText(
    '위도 36.1075000 · 경도 128.4165000',
  )
  await expect(
    page.getByTestId('mission-area-preview-naver-map'),
  ).toBeVisible()
  const projectedAreaPreview = page.getByTestId(
    'mission-area-preview-projected-overlay',
  )
  await expect(projectedAreaPreview.locator('path').first()).toHaveAttribute(
    'd',
    'M 100 200 L 200 200 L 200 100 L 100 100 L 100 200 Z',
  )
  await expect(projectedAreaPreview.locator('path').first()).toHaveCSS(
    'fill-opacity',
    '0.1',
  )
  await expect(projectedAreaPreview.locator('circle')).toHaveAttribute(
    'cx',
    '150',
  )
  await expect(projectedAreaPreview.locator('circle')).toHaveAttribute(
    'cy',
    '150',
  )
  await expect.poll(() => page.evaluate(() => {
    const state = (
      window as typeof window & {
        __patrolAreaPreviewState: {
          mapOptions: Array<Record<string, boolean | undefined>>
        }
      }
    ).__patrolAreaPreviewState
    return state.mapOptions.at(-1)
  })).toMatchObject({
    draggable: false,
    pinchZoom: false,
    scrollWheel: false,
    keyboardShortcuts: false,
    disableDoubleClickZoom: true,
    disableDoubleTapZoom: true,
    disableTwoFingerTapZoom: true,
    zoomControl: false,
  })
  await expect.poll(() => page.evaluate(() => {
    const state = (
      window as typeof window & {
        __patrolAreaPreviewState: {
          centers: Array<[longitude: number, latitude: number]>
        }
      }
    ).__patrolAreaPreviewState
    return state.centers
      .at(-1)
      ?.map((coordinate) => Number(coordinate.toFixed(7)))
  })).toEqual([
    128.4165,
    36.1075,
  ])

  await page.getByRole('button', {
    name: '활성화 대기 로봇 (robot-provisioning-e2e) 발급·활성화',
  }).click()
  await expect(
    page.getByRole('dialog', { name: 'Robot 발급·활성화' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '발급·활성화', exact: true }).click()
  await expect(page.getByText('one-time-password')).toBeVisible()
  await expect(page.locator('#mission-create-robot')).toHaveValue(
    PROVISIONING_ROBOT_ID,
  )
  await page.getByRole('button', { name: '확인 후 닫기' }).click()

  await page.locator('#mission-create-robot').selectOption(ROBOT_ID)
  await expect(
    page.locator(`#mission-create-robot option[value="${BUSY_ROBOT_ID}"]`),
  ).toHaveCount(0)
  const selectedRobotMarker = page.getByTestId('mission-area-preview-robot')
  await expect(selectedRobotMarker).toHaveAttribute('data-robot-id', ROBOT_ID)
  await expect(selectedRobotMarker).toHaveAttribute('data-mission-status', '')
  await expect(selectedRobotMarker.locator('circle')).toHaveAttribute(
    'cx',
    '140',
  )
  await expect(selectedRobotMarker.locator('circle')).toHaveAttribute(
    'cy',
    '160',
  )
  await expect(areaPreview).toContainText('임무 미수행')
  await expect(areaPreview).toContainText(
    '위도 36.1074000 · 경도 128.4164000',
  )
  await expect(page.getByRole('button', { name: '임무 생성' })).toBeEnabled()
  await page.getByRole('button', { name: '임무 생성' }).click()

  await expect(
    page.getByRole('status').filter({
      hasText: 'MIS-PATROL-AREA-E2E 임무를 생성했습니다.',
    }),
  ).toBeVisible()
  expect(missionCreatePayload).toEqual({
    robotId: ROBOT_ID,
    patrolAreaId: AREA_ID,
    patrolRouteId: ROUTE_ID,
    plannedDurationSec: 3600,
    scheduledStartAt: null,
  })
})
