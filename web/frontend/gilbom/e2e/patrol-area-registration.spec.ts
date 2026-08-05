import { expect, test, type Route } from '@playwright/test'

const USER_ID = '83000000-0000-4000-8000-000000000001'
const ROBOT_ID = '83000000-0000-4000-8000-000000000002'
const BUSY_ROBOT_ID = '83000000-0000-4000-8000-000000000006'
const AREA_ID = '83000000-0000-4000-8000-000000000003'
const ROUTE_ID = '83000000-0000-4000-8000-000000000004'
const MISSION_ID = '83000000-0000-4000-8000-000000000005'

const orderedBoundaryCoordinates = [
  [128.4160, 36.1070],
  [128.4170, 36.1070],
  [128.4170, 36.1080],
  [128.4160, 36.1080],
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
  let createPayload: Record<string, unknown> | null = null
  let missionCreatePayload: Record<string, unknown> | null = null
  const areaCodeAvailabilityRequests: string[] = []

  await page.addInitScript(() => {
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
      zoomControlOptions?: {
        position?: number
        style?: number
      }
    }
    type FakeMapListener = (event?: { coord: FakeLatLng }) => void
    type FakeMapEventHandle = {
      map: FakeMap
      eventName: string
      listener: FakeMapListener
      clickHandler: ((event: MouseEvent) => void) | null
    }
    const previewState = {
      centers: [] as Array<[longitude: number, latitude: number]>,
      mapOptions: [] as FakeMapInteractionOptions[],
      setZoomCallCount: 0,
      fitBoundsCallCount: 0,
      dragMapBy: null as ((x: number, y: number) => void) | null,
      fittedBounds: [] as Array<[
        southWest: [longitude: number, latitude: number],
        northEast: [longitude: number, latitude: number],
      ]>,
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

    class FakeLatLngBounds {
      constructor(
        readonly southWest: FakeLatLng,
        readonly northEast: FakeLatLng,
      ) {}
    }

    class FakeMap {
      readonly element: HTMLElement
      readonly overlayLayer = document.createElement('div')
      private readonly listeners = new Map<string, Set<FakeMapListener>>()
      private offsetX = 0
      private offsetY = 0

      constructor(
        element: HTMLElement,
        options: FakeMapInteractionOptions,
      ) {
        this.element = element
        this.overlayLayer.dataset.fakeMapPane = 'overlay'
        this.overlayLayer.style.position = 'absolute'
        this.overlayLayer.style.width = '100%'
        this.overlayLayer.style.height = '0px'
        this.overlayLayer.style.top = '0'
        this.overlayLayer.style.left = '0'
        this.element.appendChild(this.overlayLayer)
        previewState.mapOptions.push({ ...options })
        if (previewState.dragMapBy === null) {
          previewState.dragMapBy = (x, y) => this.dragBy(x, y)
        }
      }

      setCenter(position: FakeLatLng) {
        previewState.centers.push([position.lng(), position.lat()])
      }
      setZoom() {
        previewState.setZoomCallCount += 1
      }

      fitBounds(bounds: FakeLatLngBounds) {
        previewState.fitBoundsCallCount += 1
        previewState.fittedBounds.push([
          [bounds.southWest.lng(), bounds.southWest.lat()],
          [bounds.northEast.lng(), bounds.northEast.lat()],
        ])
      }

      getProjection() {
        return {
          fromCoordToOffset: (position: FakeLatLng) => {
            return new FakePoint(
              (position.lng() - 128.415) * 100_000 + this.offsetX,
              (36.109 - position.lat()) * 100_000 + this.offsetY,
            )
          },
        }
      }

      getPanes() {
        return { overlayLayer: this.overlayLayer }
      }

      coordinateAt(x: number, y: number) {
        return new FakeLatLng(
          36.109 - (y - this.offsetY) / 100_000,
          128.415 + (x - this.offsetX) / 100_000,
        )
      }

      addListener(eventName: string, listener: FakeMapListener) {
        const listeners = this.listeners.get(eventName) ?? new Set()
        listeners.add(listener)
        this.listeners.set(eventName, listeners)
      }

      removeListener(eventName: string, listener: FakeMapListener) {
        this.listeners.get(eventName)?.delete(listener)
      }

      dragBy(x: number, y: number) {
        this.offsetX += x
        this.offsetY += y
        ;['drag', 'panning', 'bounds_changed', 'dragend', 'idle']
          .forEach((eventName) => {
            this.listeners.get(eventName)?.forEach((listener) => listener())
          })
      }

      destroy() {
        this.overlayLayer.remove()
      }
    }

    class FakeOverlay {
      setMap() {}
    }

    class FakeOverlayView {
      private map: FakeMap | null = null

      setMap(map: FakeMap | null) {
        if (this.map !== null) {
          this.onRemove()
        }
        this.map = map
        if (this.map !== null) {
          this.onAdd()
          this.draw()
        }
      }

      getMap() {
        return this.map
      }

      getPanes() {
        if (this.map === null) {
          throw new Error('Overlay is not attached to a map')
        }
        return this.map.getPanes()
      }

      getProjection() {
        if (this.map === null) {
          throw new Error('Overlay is not attached to a map')
        }
        return this.map.getProjection()
      }

      onAdd() {}
      draw() {}
      onRemove() {}
    }

    class FakeEventSource {
      onopen: ((event: Event) => void) | null = null
      onerror: ((event: Event) => void) | null = null

      addEventListener() {}
      close() {}
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
          LatLngBounds: FakeLatLngBounds,
          Map: FakeMap,
          OverlayView: FakeOverlayView,
          Polygon: FakeOverlay,
          Polyline: FakeOverlay,
          Marker: FakeOverlay,
          Size: class FakeSize {},
          Point: FakePoint,
          Position: { TOP_RIGHT: 1 },
          ZoomControlStyle: { SMALL: 1 },
          Event: {
            addListener(
              map: FakeMap,
              eventName: string,
              listener: FakeMapListener,
            ): FakeMapEventHandle {
              map.addListener(eventName, listener)
              let clickHandler: ((event: MouseEvent) => void) | null = null
              if (eventName === 'click') {
                clickHandler = (event) => {
                  listener({
                    coord: map.coordinateAt(
                      event.offsetX,
                      event.offsetY,
                    ),
                  })
                }
                map.element.addEventListener('click', clickHandler)
              }
              return { map, eventName, listener, clickHandler }
            },
            removeListener(handle: FakeMapEventHandle) {
              handle.map.removeListener(handle.eventName, handle.listener)
              if (handle.clickHandler !== null) {
                handle.map.element.removeEventListener(
                  'click',
                  handle.clickHandler,
                )
              }
            },
          },
        },
      },
    })
  })

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
        permissions: ['MISSION_VIEW', 'MISSION_MANAGE'],
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
          missionAssignable: true,
          overallHealthStatus: 'NORMAL',
        }, {
          robotId: BUSY_ROBOT_ID,
          mqttId: 'robot-busy-e2e',
          robotName: '임무 수행 중 로봇',
          serialNumber: 'BUSY-E2E-001',
          operationalStatus: 'ACTIVE',
          connectivityStatus: 'ONLINE',
          missionStatus: 'RUNNING',
          missionAssignable: false,
          overallHealthStatus: 'NORMAL',
        }],
        page: 0,
        size: 100,
        totalElements: 2,
        totalPages: 1,
      })
      return
    }
    if (pathname === '/api/v1/robots/map') {
      await fulfillJson(route, {
        robots: [{
          robotId: ROBOT_ID,
          mqttId: 'robot-patrol-area-e2e',
          robotName: '순찰 구역 검증 로봇',
          connectivityStatus: 'ONLINE',
          latitude: 37.5013,
          longitude: 127.0396,
          headingDeg: 90,
          missionStatus: null,
          observedAt: '2026-07-30T12:00:00Z',
        }],
      })
      return
    }
    if (
      pathname === '/api/v1/patrol-areas/code-availability'
      && request.method() === 'GET'
    ) {
      const requestedAreaCode = new URL(request.url())
        .searchParams.get('areaCode') ?? ''
      const normalizedAreaCode = requestedAreaCode.trim()
      areaCodeAvailabilityRequests.push(normalizedAreaCode)
      await fulfillJson(route, {
        areaCode: normalizedAreaCode,
        available: normalizedAreaCode !== '똥방구 설사',
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

  const registrationDialog = page.getByRole('dialog', {
    name: '순찰 구역 등록',
  })
  await expect(registrationDialog).toBeVisible()
  await expect(
    registrationDialog.getByText('중심 좌표 직접 입력'),
  ).toHaveCount(0)
  await expect(
    registrationDialog.getByText(
      '지도에서 경계점을 3개 이상 선택해 실제 순찰 구역을 등록합니다.',
    ),
  ).toHaveCount(0)
  await expect(
    registrationDialog.getByRole('button', { name: '마지막 점 취소' }),
  ).toBeVisible()
  await expect(
    registrationDialog.getByRole('button', { name: '전체 초기화' }),
  ).toBeVisible()
  await expect(
    registrationDialog.getByRole('button', { name: '취소', exact: true }),
  ).toHaveCount(0)

  const formFields = registrationDialog.locator('.mission-area-form-fields')
  await expect(formFields.locator('.admin-form-field')).toHaveCount(2)
  await expect(formFields).toHaveCSS(
    'grid-template-columns',
    /\d+(?:\.\d+)?px \d+(?:\.\d+)?px/,
  )

  const editor = page.getByTestId('patrol-area-boundary-editor')
  await expect(editor).toBeVisible()
  const editorToolbar = editor.locator('.mission-area-editor-toolbar')
  await expect(editorToolbar).toBeVisible()
  await expect(editorToolbar.getByRole('button')).toHaveCount(3)
  await expect(editorToolbar).toHaveCSS('justify-content', 'flex-end')
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
            zoomControlOptions?: {
              position?: number
              style?: number
            }
          }>
        }
      }
    ).__patrolAreaPreviewState
    return state.mapOptions.at(0)
  })).toMatchObject({
    zoom: 17,
    minZoom: 10,
    maxZoom: 21,
    pinchZoom: false,
    scrollWheel: false,
    keyboardShortcuts: false,
    disableDoubleClickZoom: true,
    disableDoubleTapZoom: true,
    disableTwoFingerTapZoom: true,
    zoomControl: true,
    zoomControlOptions: {
      position: 1,
      style: 1,
    },
  })

  await page.evaluate(() => {
    const state = (
      window as typeof window & {
        __patrolAreaPreviewState: {
          dragMapBy: ((x: number, y: number) => void) | null
        }
      }
    ).__patrolAreaPreviewState
    state.dragMapBy?.(40, 30)
  })

  await map.click({ position: { x: 240, y: 130 } })
  const projectedOverlay = editor.getByTestId(
    'patrol-boundary-projected-overlay',
  )
  await expect(projectedOverlay.locator('xpath=..')).toHaveAttribute(
    'data-fake-map-pane',
    'overlay',
  )
  await expect(projectedOverlay).toHaveCSS('height', '360px')
  const firstClickedPoint = projectedOverlay.locator(
    '[data-longitude="128.417"][data-latitude="36.108"]',
  )
  await expect(firstClickedPoint).toHaveCount(1)
  await expect(firstClickedPoint.locator('circle')).toHaveAttribute('cx', '240')
  await expect(firstClickedPoint.locator('circle')).toHaveAttribute('cy', '130')

  await map.click({ position: { x: 140, y: 230 } })
  await map.click({ position: { x: 140, y: 130 } })
  await map.click({ position: { x: 240, y: 230 } })
  await expect(
    editor.getByText('교차 없이 자동 정렬된 Polygon입니다.'),
  ).toBeVisible()
  await expect(editor.getByText('4/100개 점')).toBeVisible()
  await expect(projectedOverlay.locator('polygon')).toHaveAttribute(
    'points',
    '140,230 240,230 240,130 140,130',
  )
  await expect(projectedOverlay.locator('polygon')).toHaveCSS(
    'fill-opacity',
    '0.1',
  )
  await expect(projectedOverlay.locator('circle')).toHaveCount(4)
  await page.evaluate(() => {
    const state = (
      window as typeof window & {
        __patrolAreaPreviewState: {
          dragMapBy: ((x: number, y: number) => void) | null
        }
      }
    ).__patrolAreaPreviewState
    state.dragMapBy?.(20, -10)
  })
  await expect(projectedOverlay.locator('polygon')).toHaveAttribute(
    'points',
    '160,220 260,220 260,120 160,120',
  )
  await expect(firstClickedPoint.locator('circle')).toHaveAttribute('cx', '260')
  await expect(firstClickedPoint.locator('circle')).toHaveAttribute('cy', '120')
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

  const areaCodeInput = page.locator('#patrol-area-code')
  const createAreaButton = page.getByRole('button', {
    name: '순찰 구역 등록',
  })
  await areaCodeInput.fill('똥방구 설사')
  await page.getByRole('button', { name: '중복 확인' }).click()
  await expect(
    page.getByRole('alert').filter({
      hasText: '이미 등록된 구역 코드입니다.',
    }),
  ).toBeVisible()
  await expect(areaCodeInput).toHaveAttribute('aria-invalid', 'true')
  await expect(createAreaButton).toBeDisabled()

  await areaCodeInput.fill('DEMO-ZONE-01')
  await expect(
    page.getByText('이미 등록된 구역 코드입니다.'),
  ).toHaveCount(0)
  await page.getByRole('button', { name: '중복 확인' }).click()
  await expect(
    page.getByRole('status').filter({
      hasText: '사용 가능한 구역 코드입니다.',
    }),
  ).toBeVisible()
  await expect(createAreaButton).toBeEnabled()
  await page.locator('#patrol-area-name').fill('시연 순찰 구역')
  await createAreaButton.click()

  await expect(registrationDialog).toBeHidden()

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
  expect(areaCodeAvailabilityRequests).toEqual([
    '똥방구 설사',
    'DEMO-ZONE-01',
  ])

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

  await page.locator('#mission-create-robot').selectOption(ROBOT_ID)
  await expect(
    page.locator(`#mission-create-robot option[value="${BUSY_ROBOT_ID}"]`),
  ).toHaveCount(0)
  const selectedRobotMarker = page.getByTestId('mission-area-preview-robot')
  await expect(selectedRobotMarker).toHaveAttribute('data-robot-id', ROBOT_ID)
  await expect(selectedRobotMarker).toHaveAttribute('data-mission-status', '')
  await expect(selectedRobotMarker.locator('circle')).toHaveAttribute(
    'cx',
    '-137540',
  )
  await expect(selectedRobotMarker.locator('circle')).toHaveAttribute(
    'cy',
    '-139230',
  )
  await expect(areaPreview).toContainText('임무 미수행')
  await expect(areaPreview).toContainText(
    '위도 37.5013000 · 경도 127.0396000',
  )
  await expect.poll(() => page.evaluate(() => {
    const state = (
      window as typeof window & {
        __patrolAreaPreviewState: {
          fittedBounds: Array<[
            [longitude: number, latitude: number],
            [longitude: number, latitude: number],
          ]>
        }
      }
    ).__patrolAreaPreviewState
    return state.fittedBounds.at(-1)?.map((position) => (
      position.map((coordinate) => Number(coordinate.toFixed(7)))
    ))
  })).toEqual([
    [128.416, 36.107],
    [128.417, 36.108],
  ])
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
