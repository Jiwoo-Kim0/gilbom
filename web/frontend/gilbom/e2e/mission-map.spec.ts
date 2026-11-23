import { expect, test, type Route } from '@playwright/test'

const USER_ID = '82000000-0000-4000-8000-000000000001'
const ROBOT_ID = '82000000-0000-4000-8000-000000000002'
const AREA_ID = '82000000-0000-4000-8000-000000000003'
const ROUTE_ID = '82000000-0000-4000-8000-000000000004'
const MISSION_ID = '82000000-0000-4000-8000-000000000005'
const DEFECT_ID = '82000000-0000-4000-8000-000000000006'

const mission = {
  missionId: MISSION_ID,
  missionCode: 'MIS-MAP-E2E',
  robotId: ROBOT_ID,
  mqttId: 'robot-map-e2e',
  robotName: '지도 검증 로봇',
  patrolAreaId: AREA_ID,
  patrolAreaCode: 'AREA-MAP',
  patrolAreaName: '지도 검증 순찰 구역',
  patrolRouteId: ROUTE_ID,
  patrolRouteCode: 'AUTONOMOUS_DEFAULT',
  patrolRouteName: '내부 호환 경로',
  patrolRouteVersion: 1,
  plannedDurationSec: 1800,
  elapsedSec: 600,
  remainingDurationSec: 1200,
  progressObservedAt: '2026-07-30T02:10:00Z',
  progressStale: false,
  status: 'RUNNING',
  scheduledStartAt: null,
  startedAt: '2026-07-30T02:00:00Z',
  endedAt: null,
  progressPercent: 33.33,
  estimatedCompletionAt: '2026-07-30T02:30:00Z',
  completionReason: null,
  resultDurationSec: null,
  resultIncidentCount: null,
  resultNormalDetectionCount: null,
  resultPayload: null,
  createdBy: USER_ID,
  updatedBy: USER_ID,
  createdAt: '2026-07-30T01:55:00Z',
  updatedAt: '2026-07-30T02:10:00Z',
} as const

const boundary = {
  type: 'Polygon',
  coordinates: [[
    [127.0, 37.0],
    [127.01, 37.0],
    [127.01, 37.01],
    [127.0, 37.01],
    [127.0, 37.0],
  ]],
} as const

function trajectoryPoint(
  index: number,
  longitude: number,
  latitude: number,
) {
  return {
    messageId: `82000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    sampledAt: `2026-07-30T02:0${index}:00Z`,
    receivedAt: `2026-07-30T02:0${index}:00.100Z`,
    latitude,
    longitude,
    headingDeg: 45,
    moving: true,
    speedMps: 1.1,
  }
}

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

test('Mission 구역·실제 궤적·현재 위치를 그리고 SSE 갱신을 반영한다', async ({
  page,
}) => {
  let snapshotVersion = 1
  let missionDefectAvailable = false

  await page.addInitScript(() => {
    type EventListener = (event: MessageEvent<string>) => void
    const overlayState = {
      mapConstructions: 0,
      mapDestructions: 0,
      fitBoundsCalls: 0,
      nativePolygons: 0,
      nativePolylines: 0,
      nativeMarkers: 0,
      mapAttachedOverlays: 0,
      overlayDraws: 0,
      trajectoryPointCount: 0,
      trajectoryPathUpdates: 0,
      markerLatitude: null as number | null,
      markerLongitude: null as number | null,
      markerPositionUpdates: 0,
      fittedSouthWestLatitude: null as number | null,
      fittedSouthWestLongitude: null as number | null,
      fittedNorthEastLatitude: null as number | null,
      fittedNorthEastLongitude: null as number | null,
      zoomControlEnabled: false,
      zoomControlStyle: null as number | null,
    }
    const mapListeners = new Map<string, Set<() => void>>()
    let projectionOffsetX = 0
    let projectionOffsetY = 0
    let projectionScale = 10_000

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
      constructor(
        readonly element: HTMLElement,
        options: {
          zoomControl?: boolean
          zoomControlOptions?: { style?: number }
        },
      ) {
        overlayState.mapConstructions += 1
        overlayState.zoomControlEnabled = options.zoomControl === true
        overlayState.zoomControlStyle = options.zoomControlOptions?.style ?? null
        window.setTimeout(() => {
          mapListeners.get('init')?.forEach((listener) => listener())
        }, 0)
      }

      setCenter() {}
      setZoom() {}
      fitBounds(bounds: FakeLatLngBounds) {
        overlayState.fitBoundsCalls += 1
        overlayState.fittedSouthWestLatitude = bounds.southWest.lat()
        overlayState.fittedSouthWestLongitude = bounds.southWest.lng()
        overlayState.fittedNorthEastLatitude = bounds.northEast.lat()
        overlayState.fittedNorthEastLongitude = bounds.northEast.lng()
        window.setTimeout(() => {
          mapListeners.get('idle')?.forEach((listener) => listener())
        }, 0)
      }
      getProjection() {
        return {
          fromCoordToOffset(position: FakeLatLng) {
            return new FakePoint(
              (position.lng() - 126.99) * projectionScale + projectionOffsetX,
              (37.02 - position.lat()) * projectionScale + projectionOffsetY,
            )
          },
        }
      }
      destroy() {
        overlayState.mapDestructions += 1
      }
    }

    class FakePolygon {
      constructor() {
        overlayState.nativePolygons += 1
      }
      setMap() {}
    }

    class FakePolyline {
      constructor(options: { path: FakeLatLng[] }) {
        overlayState.nativePolylines += 1
        overlayState.trajectoryPointCount = options.path.length
      }
      setMap() {}
      setPath(path: FakeLatLng[]) {
        overlayState.trajectoryPointCount = path.length
        overlayState.trajectoryPathUpdates += 1
      }
    }

    class FakeMarker {
      constructor(options: { position: FakeLatLng }) {
        overlayState.nativeMarkers += 1
        overlayState.markerLatitude = options.position.lat()
        overlayState.markerLongitude = options.position.lng()
      }
      setMap() {}
      setPosition(position: FakeLatLng) {
        overlayState.markerLatitude = position.lat()
        overlayState.markerLongitude = position.lng()
        overlayState.markerPositionUpdates += 1
      }
      setIcon() {}
      setTitle() {}
    }

    class FakeLatLngBounds {
      constructor(
        readonly southWest: FakeLatLng,
        readonly northEast: FakeLatLng,
      ) {}
    }

    class FakeOverlayView {
      private map: FakeMap | null = null

      constructor() {
        overlayState.mapAttachedOverlays += 1
      }

      setMap(map: FakeMap | null) {
        if (this.map !== null) {
          this.onRemove()
        }
        this.map = map
        if (map !== null) {
          this.onAdd()
          overlayState.overlayDraws += 1
          this.draw()
        }
      }
      getMap() {
        return this.map
      }
      getPanes() {
        if (this.map === null) {
          throw new Error('Overlay has no map')
        }
        return { overlayLayer: this.map.element }
      }
      getProjection() {
        if (this.map === null) {
          throw new Error('Overlay has no map')
        }
        return this.map.getProjection()
      }
      onAdd() {}
      draw() {}
      onRemove() {}
    }

    class FakeEventSource {
      static instances = new Set<FakeEventSource>()

      onopen: ((event: Event) => void) | null = null
      onerror: ((event: Event) => void) | null = null
      private listeners = new Map<string, Set<EventListener>>()

      constructor() {
        FakeEventSource.instances.add(this)
        window.setTimeout(() => {
          this.onopen?.(new Event('open'))
        }, 0)
      }

      addEventListener(type: string, listener: EventListener) {
        const listeners = this.listeners.get(type) ?? new Set<EventListener>()
        listeners.add(listener)
        this.listeners.set(type, listeners)
      }

      close() {
        FakeEventSource.instances.delete(this)
      }

      emit(type: string, payload: unknown) {
        const event = new MessageEvent('message', {
          data: JSON.stringify(payload),
        })
        this.listeners.get(type)?.forEach((listener) => listener(event))
      }
    }

    Object.defineProperty(window, 'EventSource', {
      configurable: true,
      value: FakeEventSource,
    })
    Object.defineProperty(window, 'naver', {
      configurable: true,
      value: {
        maps: {
          LatLng: FakeLatLng,
          LatLngBounds: FakeLatLngBounds,
          Map: FakeMap,
          OverlayView: FakeOverlayView,
          Polygon: FakePolygon,
          Polyline: FakePolyline,
          Marker: FakeMarker,
          Size: class FakeSize {},
          Point: FakePoint,
          Position: { TOP_RIGHT: 1 },
          ZoomControlStyle: { SMALL: 2 },
          Event: {
            addListener(
              _map: FakeMap,
              eventName: string,
              listener: () => void,
            ) {
              const listeners = mapListeners.get(eventName) ?? new Set()
              listeners.add(listener)
              mapListeners.set(eventName, listeners)
              return { eventName, listener }
            },
            removeListener(listener: {
              eventName: string
              listener: () => void
            }) {
              mapListeners.get(listener.eventName)?.delete(listener.listener)
            },
          },
        },
      },
    })
    Object.defineProperty(window, '__missionMapOverlayState', {
      configurable: true,
      value: overlayState,
    })
    Object.defineProperty(window, '__moveMissionMap', {
      configurable: true,
      value: (offsetX: number, offsetY: number) => {
        projectionOffsetX += offsetX
        projectionOffsetY += offsetY
        mapListeners.get('drag')?.forEach((listener) => listener())
        mapListeners.get('panning')?.forEach((listener) => listener())
        mapListeners.get('bounds_changed')?.forEach((listener) => listener())
      },
    })
    Object.defineProperty(window, '__zoomMissionMap', {
      configurable: true,
      value: (scale: number) => {
        projectionScale = scale
        mapListeners.get('zooming')?.forEach((listener) => listener())
        mapListeners.get('bounds_changed')?.forEach((listener) => listener())
      },
    })
    Object.defineProperty(window, '__emitMissionRobotEvent', {
      configurable: true,
      value: (type: string, payload: unknown) => {
        FakeEventSource.instances.forEach((source) => source.emit(type, payload))
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
        displayName: 'Mission Map E2E 관리자',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: ['MISSION_VIEW', 'DEFECT_STATUS_VIEW'],
      })
      return
    }
    if (pathname === '/api/v1/robots') {
      await fulfillJson(route, {
        content: [{
          robotId: ROBOT_ID,
          mqttId: 'robot-map-e2e',
          robotName: '지도 검증 로봇',
          serialNumber: 'MAP-E2E-001',
          operationalStatus: 'ACTIVE',
          connectivityStatus: 'ONLINE',
          overallHealthStatus: 'OK',
        }],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
      })
      return
    }
    if (pathname === '/api/v1/patrol-areas') {
      await fulfillJson(route, {
        areas: [{
          patrolAreaId: AREA_ID,
          areaCode: 'AREA-MAP',
          areaName: '지도 검증 순찰 구역',
          boundary,
          lifecycleStatus: 'ACTIVE',
          createdAt: '2026-07-30T00:00:00Z',
          updatedAt: '2026-07-30T00:00:00Z',
        }],
      })
      return
    }
    if (pathname === '/api/v1/missions') {
      await fulfillJson(route, {
        content: [mission],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      })
      return
    }
    if (pathname === `/api/v1/missions/${MISSION_ID}`) {
      await fulfillJson(route, mission)
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
      const trajectory = [
        trajectoryPoint(1, 127.002, 37.002),
        trajectoryPoint(2, 127.004, 37.004),
        trajectoryPoint(3, 127.006, 37.006),
      ]
      if (snapshotVersion === 2) {
        trajectory.push(trajectoryPoint(4, 127.012, 37.012))
      }
      await fulfillJson(route, {
        missionId: MISSION_ID,
        patrolAreaId: AREA_ID,
        patrolAreaBoundary: boundary,
        trajectory,
        currentPosition: snapshotVersion === 1
          ? {
            latitude: 37.006,
            longitude: 127.006,
            headingDeg: 45,
            moving: true,
            speedMps: 1.1,
            connectivityStatus: 'ONLINE',
            observedAt: '2026-07-30T02:03:00Z',
          }
          : {
            latitude: 37.02,
            longitude: 127.02,
            headingDeg: 90,
            moving: true,
            speedMps: 1.2,
            connectivityStatus: 'ONLINE',
            observedAt: '2026-07-30T02:04:00Z',
          },
      })
      return
    }
    if (pathname === `/api/v1/missions/${MISSION_ID}/defects`) {
      await fulfillJson(route, {
        missionId: MISSION_ID,
        defects: missionDefectAvailable
          ? [{
            defectId: DEFECT_ID,
            incidentCode: 'INC-20260806-000001',
            defectType: 'DAMAGED',
            riskLevel: 'HIGH',
            workflowStatus: 'NEW',
            longitude: 127.0065,
            latitude: 37.0065,
            latestConfidence: 0.91,
          }]
          : [],
      })
      return
    }
    if (pathname === `/api/v1/defects/${DEFECT_ID}`) {
      await fulfillJson(route, {
        defect: {
          defectId: DEFECT_ID,
          incidentCode: 'INC-20260806-000001',
          assetType: 'BRAILLE_BLOCK',
          blockType: 'LINEAR',
          defectType: 'DAMAGED',
          riskLevel: 'HIGH',
          riskScore: 72,
          workflowStatus: 'NEW',
          resolution: null,
          location: { longitude: 127.0065, latitude: 37.0065 },
          locationAccuracyM: 1.2,
          zoneId: 'A-01',
          patrolAreaId: AREA_ID,
          patrolRouteId: ROUTE_ID,
          locationLabel: '지도 검증 지점',
          representativeImageUrl: null,
          firstSeenAt: '2026-08-06T01:00:00Z',
          lastSeenAt: '2026-08-06T01:00:00Z',
          observationCount: 1,
          latestConfidence: 0.91,
          reviewRequired: false,
        },
        resolutionNote: null,
        riskScore: 72,
        riskRuleVersion: 'DAMAGE_RATIO_V1',
        riskCalculatedAt: '2026-08-06T01:00:00Z',
        mergedIntoDefectId: null,
        mergedDefectIds: [],
        createdAt: '2026-08-06T01:00:00Z',
        updatedAt: '2026-08-06T01:00:00Z',
      })
      return
    }
    if (pathname === `/api/v1/defects/${DEFECT_ID}/image-url`) {
      await fulfillJson(route, {
        code: 'DEFECT_IMAGE_NOT_FOUND',
        message: '이미지가 없습니다.',
      }, 404)
      return
    }

    await route.fulfill({ status: 404, body: pathname })
  })

  await page.goto('/missions')

  await page.getByRole('button', {
    name: 'MIS-MAP-E2E 상세 보기',
  }).click()
  await expect(page.getByRole('dialog', {
    name: 'MIS-MAP-E2E',
  })).toBeVisible()
  await expect(page.getByRole('link', {
    name: '로봇 상세 페이지로 이동: 지도 검증 로봇',
  })).toHaveAttribute('href', `/robots/${ROBOT_ID}`)

  await expect(page.getByRole('heading', {
    name: '임무 위치 지도',
  })).toBeVisible()
  await expect(page.getByTestId('mission-naver-map')).toBeVisible()
  await expect(page.getByTestId('mission-map-fallback')).toHaveCount(0)
  await expect(page.getByTestId('mission-map-projected-overlay')).toHaveCount(0)
  const nativeOverlay = page.getByTestId('mission-map-native-overlay')
  await expect(nativeOverlay).toBeVisible()
  await expect(nativeOverlay).toHaveAttribute('overflow', 'visible')
  await expect(nativeOverlay).toHaveCSS('overflow', 'visible')
  const areaShape = page.getByTestId('mission-area-shape')
  const trajectory = page.getByTestId('mission-trajectory')
  const currentMarker = page.getByTestId('mission-current-position')
  const currentCar = page.getByTestId('mission-current-car')
  await expect(areaShape).toHaveCount(1)
  await expect(trajectory).toHaveAttribute('data-point-count', '3')
  await expect(currentMarker).toHaveAttribute('data-latitude', '37.006')
  await expect(currentMarker).toHaveAttribute('data-longitude', '127.006')
  await expect(currentMarker).toHaveAttribute('data-heading', '45')
  await expect(currentCar).toHaveCount(1)
  await expect.poll(() => page.evaluate(() => (
    window as Window & {
      __missionMapOverlayState?: {
        mapConstructions: number
        mapDestructions: number
        fitBoundsCalls: number
        nativePolygons: number
        nativePolylines: number
        nativeMarkers: number
        mapAttachedOverlays: number
        fittedSouthWestLatitude: number | null
        fittedSouthWestLongitude: number | null
        fittedNorthEastLatitude: number | null
        fittedNorthEastLongitude: number | null
        zoomControlEnabled: boolean
        zoomControlStyle: number | null
      }
    }
  ).__missionMapOverlayState)).toMatchObject({
    mapConstructions: 1,
    mapDestructions: 0,
    fitBoundsCalls: 1,
    nativePolygons: 0,
    nativePolylines: 0,
    nativeMarkers: 0,
    mapAttachedOverlays: 1,
    fittedSouthWestLatitude: 37,
    fittedSouthWestLongitude: 127,
    fittedNorthEastLatitude: 37.01,
    fittedNorthEastLongitude: 127.01,
    zoomControlEnabled: true,
    zoomControlStyle: 2,
  })
  await expect(page.getByText('실제 궤적 (3)')).toBeVisible()
  await expect(page.getByText('현재 위치가 지정된 순찰 구역 밖입니다.'))
    .toHaveCount(0)
  await expect(page.getByText('계획 경로')).toHaveCount(0)

  const initialAreaPath = await areaShape.getAttribute('d')
  const initialMarkerTransform = await currentMarker.getAttribute('transform')
  await page.evaluate(() => {
    const moveMissionMap = (
      window as Window & {
        __moveMissionMap?: (offsetX: number, offsetY: number) => void
      }
    ).__moveMissionMap
    moveMissionMap?.(35, 20)
  })
  await expect(areaShape).not.toHaveAttribute('d', initialAreaPath ?? '')
  await expect(currentMarker).not.toHaveAttribute(
    'transform',
    initialMarkerTransform ?? '',
  )

  const draggedAreaPath = await areaShape.getAttribute('d')
  const draggedMarkerTransform = await currentMarker.getAttribute('transform')
  await page.evaluate(() => {
    const zoomMissionMap = (
      window as Window & {
        __zoomMissionMap?: (scale: number) => void
      }
    ).__zoomMissionMap
    zoomMissionMap?.(15_000)
  })
  await expect(areaShape).not.toHaveAttribute('d', draggedAreaPath ?? '')
  await expect(currentMarker).not.toHaveAttribute(
    'transform',
    draggedMarkerTransform ?? '',
  )

  snapshotVersion = 2
  await page.evaluate(() => {
    const emit = (
      window as Window & {
        __emitMissionRobotEvent?: (type: string, payload: unknown) => void
      }
    ).__emitMissionRobotEvent
    emit?.('ROBOT_TELEMETRY_UPDATED', {
      eventType: 'ROBOT_TELEMETRY_UPDATED',
      robotId: '82000000-0000-4000-8000-000000000002',
      mqttId: 'robot-map-e2e',
      observedAt: '2026-07-30T02:04:00Z',
    })
  })

  await expect(currentMarker).toHaveAttribute('data-latitude', '37.02')
  await expect(currentMarker).toHaveAttribute('data-longitude', '127.02')
  await expect(currentMarker).toHaveAttribute('data-heading', '90')
  await expect(trajectory).toHaveAttribute('data-point-count', '4')
  await expect.poll(() => page.evaluate(() => (
    window as Window & {
      __missionMapOverlayState?: {
        mapConstructions: number
        mapDestructions: number
        fitBoundsCalls: number
        nativePolygons: number
        nativePolylines: number
        nativeMarkers: number
        mapAttachedOverlays: number
        fittedSouthWestLatitude: number | null
        fittedSouthWestLongitude: number | null
        fittedNorthEastLatitude: number | null
        fittedNorthEastLongitude: number | null
        zoomControlEnabled: boolean
        zoomControlStyle: number | null
      }
    }
  ).__missionMapOverlayState)).toMatchObject({
    mapConstructions: 1,
    mapDestructions: 0,
    fitBoundsCalls: 1,
    nativePolygons: 0,
    nativePolylines: 0,
    nativeMarkers: 0,
    mapAttachedOverlays: 1,
    fittedSouthWestLatitude: 37,
    fittedSouthWestLongitude: 127,
    fittedNorthEastLatitude: 37.01,
    fittedNorthEastLongitude: 127.01,
    zoomControlEnabled: true,
    zoomControlStyle: 2,
  })
  await expect(page.getByText('실제 궤적 (4)')).toBeVisible()
  await expect(page.getByText('현재 위치가 지정된 순찰 구역 밖입니다.'))
    .toBeVisible()

  missionDefectAvailable = true
  await page.evaluate(({ defectId, areaId }) => {
    const emit = (
      window as Window & {
        __emitMissionRobotEvent?: (type: string, payload: unknown) => void
      }
    ).__emitMissionRobotEvent
    emit?.('defect.created', {
      eventType: 'defect.created',
      defectId,
      patrolAreaId: areaId,
    })
  }, { defectId: DEFECT_ID, areaId: AREA_ID })

  await expect(page.getByText('결함 (1)')).toBeVisible()
  await expect(page.getByRole('complementary', { name: '결함 상세' }))
    .toBeVisible()
  await expect(page.getByText('INC-20260806-000001')).toBeVisible()
  await expect(page.getByRole('heading', { name: '파손' })).toBeVisible()
  await expect.poll(() => page.evaluate(() => (
    window as Window & {
      __missionMapOverlayState?: { nativeMarkers: number }
    }
  ).__missionMapOverlayState?.nativeMarkers ?? 0)).toBeGreaterThan(0)
})
