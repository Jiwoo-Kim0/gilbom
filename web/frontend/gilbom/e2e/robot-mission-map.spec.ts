import { expect, test, type Route } from '@playwright/test'

const ROBOT_ID = '83000000-0000-4000-8000-000000000001'
const MISSION_ID = '83000000-0000-4000-8000-000000000002'
const AREA_ID = '83000000-0000-4000-8000-000000000003'

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

test('로봇 상세의 현재 임무 지도를 명령 관리 위에 표시한다', async ({
  page,
}) => {
  let mapRequestCount = 0

  await page.addInitScript(() => {
    class FakeEventSource {
      onopen: ((event: Event) => void) | null = null
      onerror: ((event: Event) => void) | null = null

      constructor() {
        window.setTimeout(() => this.onopen?.(new Event('open')), 0)
      }

      addEventListener() {}
      removeEventListener() {}
      close() {}
    }

    Object.defineProperty(window, 'EventSource', {
      configurable: true,
      writable: true,
      value: FakeEventSource,
    })
  })

  await page.route('https://oapi.map.naver.com/**', (route) => route.abort())
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
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
        userId: '83000000-0000-4000-8000-000000000004',
        displayName: '로봇 지도 E2E 관리자',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: [
          'MISSION_VIEW',
          'ROBOT_STATUS_VIEW',
          'ROBOT_COMMAND_VIEW',
          'ROBOT_COMMAND_EXECUTE',
        ],
      })
      return
    }
    if (pathname === `/api/v1/robots/${ROBOT_ID}`) {
      await fulfillJson(route, {
        robotId: ROBOT_ID,
        mqttId: 'robot-map-control-e2e',
        robotName: '지도 제어 로봇',
        serialNumber: 'MAP-CONTROL-E2E-001',
        operationalStatus: 'ACTIVE',
        connectivityStatus: 'ONLINE',
        connectionChangedAt: '2026-08-06T01:00:00Z',
        lastReceivedAt: '2026-08-06T01:15:00Z',
        robotMode: 'PATROLLING',
        stateReasonCode: null,
        missionStatus: 'RUNNING',
        batteryPercent: 82,
        batteryState: 'DISCHARGING',
        charging: false,
        batteryTemperatureC: 31,
        estimatedRuntimeSec: 5400,
        cameraHealth: 'OK',
        lidarHealth: 'OK',
        gpsHealth: 'OK',
        motorHealth: 'OK',
        storageHealth: 'OK',
        latitude: 37.005,
        longitude: 127.005,
        headingDeg: 45,
        moving: true,
        speedMps: 1.2,
        jetsonTempC: 48,
        overallHealthStatus: 'OK',
        telemetrySampledAt: '2026-08-06T01:15:00Z',
        telemetryReceivedAt: '2026-08-06T01:15:01Z',
        registeredAt: '2026-08-01T00:00:00Z',
        updatedAt: '2026-08-06T01:15:01Z',
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
    if (pathname === `/api/v1/robots/${ROBOT_ID}/telemetry-statistics`) {
      await fulfillJson(route, {
        robotId: ROBOT_ID,
        mqttId: 'robot-map-control-e2e',
        robotName: '지도 제어 로봇',
        from: url.searchParams.get('from'),
        to: url.searchParams.get('to'),
        sampleCount: 1,
        validBatterySampleCount: 1,
        averageBatteryPercent: 82,
        minimumBatteryPercent: 82,
        averageJetsonTemperatureC: 48,
        maximumJetsonTemperatureC: 48,
        averageSpeedMps: 1.2,
        maximumSpeedMps: 1.2,
        validGpsSampleCount: 1,
        invalidGpsSampleCount: 0,
        gpsValidRatePercent: 100,
        lastSampledAt: '2026-08-06T01:15:00Z',
      })
      return
    }
    if (pathname === `/api/v1/robots/${ROBOT_ID}/telemetry-series`) {
      await fulfillJson(route, {
        robotId: ROBOT_ID,
        mqttId: 'robot-map-control-e2e',
        robotName: '지도 제어 로봇',
        from: url.searchParams.get('from'),
        to: url.searchParams.get('to'),
        interval: url.searchParams.get('interval'),
        points: [],
      })
      return
    }
    if (pathname === '/api/v1/missions') {
      await fulfillJson(route, {
        content: [{
          missionId: MISSION_ID,
          missionCode: 'MIS-ROBOT-MAP-E2E',
          robotId: ROBOT_ID,
          mqttId: 'robot-map-control-e2e',
          robotName: '지도 제어 로봇',
          patrolAreaId: AREA_ID,
          patrolAreaCode: 'AREA-ROBOT-MAP',
          patrolAreaName: '로봇 지도 순찰 구역',
          patrolRouteId: '83000000-0000-4000-8000-000000000005',
          patrolRouteCode: 'AUTONOMOUS_DEFAULT',
          patrolRouteName: '자율 순찰 경로',
          patrolRouteVersion: 1,
          plannedDurationSec: 3600,
          elapsedSec: 900,
          remainingDurationSec: 2700,
          progressObservedAt: '2026-08-06T01:15:00Z',
          progressStale: false,
          status: 'RUNNING',
          scheduledStartAt: null,
          startedAt: '2026-08-06T01:00:00Z',
          endedAt: null,
          progressPercent: 25,
          estimatedCompletionAt: '2026-08-06T02:00:00Z',
          completionReason: null,
          resultDurationSec: null,
          resultIncidentCount: null,
          resultNormalDetectionCount: null,
          resultPayload: null,
          createdBy: '83000000-0000-4000-8000-000000000004',
          updatedBy: '83000000-0000-4000-8000-000000000004',
          createdAt: '2026-08-06T00:55:00Z',
          updatedAt: '2026-08-06T01:15:00Z',
        }],
        page: 0,
        size: 100,
        totalElements: 1,
        totalPages: 1,
      })
      return
    }
    if (pathname === `/api/v1/missions/${MISSION_ID}/map`) {
      mapRequestCount += 1
      await fulfillJson(route, {
        missionId: MISSION_ID,
        patrolAreaId: AREA_ID,
        patrolAreaBoundary: {
          type: 'Polygon',
          coordinates: [[
            [127, 37],
            [127.01, 37],
            [127.01, 37.01],
            [127, 37.01],
            [127, 37],
          ]],
        },
        trajectory: [{
          messageId: '83000000-0000-4000-8000-000000000006',
          sampledAt: '2026-08-06T01:14:00Z',
          receivedAt: '2026-08-06T01:14:01Z',
          latitude: 37.004,
          longitude: 127.004,
          headingDeg: 45,
          moving: true,
          speedMps: 1.2,
        }],
        currentPosition: {
          latitude: 37.005,
          longitude: 127.005,
          headingDeg: 45,
          moving: true,
          speedMps: 1.2,
          connectivityStatus: 'ONLINE',
          observedAt: '2026-08-06T01:15:00Z',
        },
      })
      return
    }

    await route.fulfill({ status: 404, body: pathname })
  })

  await page.goto(`/robots/${ROBOT_ID}`)

  const commandMissionMap = page.getByTestId('command-mission-map')
  await expect(commandMissionMap.getByRole('heading', {
    name: '임무 위치 지도',
  })).toBeVisible()
  await expect(commandMissionMap.getByText(
    '로봇 지도 순찰 구역 · MIS-ROBOT-MAP-E2E',
  )).toBeVisible()
  await expect(page.getByTestId('mission-map-fallback')).toBeVisible()
  expect(await page.getByTestId('mission-map-fallback').evaluate((map) => (
    map.getBoundingClientRect().height
  ))).toBe(572)
  await expect(page.getByRole('heading', { name: '명령 관리' })).toBeVisible()
  expect(mapRequestCount).toBeGreaterThan(0)
  expect(await commandMissionMap.evaluate((mapCard) => {
    const commandHeading = document.querySelector('.command-section-heading')
    return commandHeading !== null && Boolean(
      mapCard.compareDocumentPosition(commandHeading)
      & Node.DOCUMENT_POSITION_FOLLOWING,
    )
  })).toBe(true)
})
