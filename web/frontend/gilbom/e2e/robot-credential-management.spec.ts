import { expect, test, type Route } from '@playwright/test'

const USER_ID = '84000000-0000-4000-8000-000000000001'
const PROVISIONING_ROBOT_ID = '84000000-0000-4000-8000-000000000002'
const ACTIVE_ROBOT_ID = '84000000-0000-4000-8000-000000000003'

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

test('Robot 관리에서 최초 Credential 발급과 회전을 수행한다', async ({
  page,
}) => {
  let registryReconciled = false
  const provisioningIdempotencyKeys: string[] = []
  const activeIdempotencyKeys: string[] = []

  await page.addInitScript(() => {
    class FakeEventSource {
      onopen: ((event: Event) => void) | null = null
      onerror: ((event: Event) => void) | null = null

      addEventListener() {}
      close() {}
    }

    class FakeLatLng {}
    class FakeMap {
      setCenter() {}
    }
    class FakeMarker {
      setMap() {}
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
          Map: FakeMap,
          Marker: FakeMarker,
          Size: class FakeSize {},
          Point: class FakePoint {},
          Position: { TOP_RIGHT: 1 },
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
        displayName: 'Robot Credential E2E 관리자',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: ['ROBOT_STATUS_VIEW', 'ROBOT_REGISTER'],
      })
      return
    }
    if (pathname === '/api/v1/auth/csrf-token') {
      await fulfillJson(route, {
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'robot-credential-e2e-csrf',
      })
      return
    }
    if (pathname === '/api/v1/robots/summary') {
      await fulfillJson(route, {
        totalRobots: 2,
        onlineRobots: 1,
        offlineRobots: 1,
        warningRobots: 0,
        criticalRobots: 0,
      })
      return
    }
    if (pathname === '/api/v1/robots/map') {
      await fulfillJson(route, { robots: [] })
      return
    }
    if (pathname === '/api/v1/robots') {
      await fulfillJson(route, {
        content: [{
          robotId: PROVISIONING_ROBOT_ID,
          mqttId: 'RBT-101',
          robotName: '최초 발급 대기 로봇',
          serialNumber: 'CREDENTIAL-E2E-101',
          operationalStatus: 'PROVISIONING',
          connectivityStatus: 'OFFLINE',
          lastReceivedAt: null,
          batteryPercent: null,
          charging: null,
          jetsonTempC: null,
          speedMps: null,
          robotMode: null,
          missionStatus: null,
          overallHealthStatus: 'UNKNOWN',
        }, {
          robotId: ACTIVE_ROBOT_ID,
          mqttId: 'RBT-102',
          robotName: '운영 중 로봇',
          serialNumber: 'CREDENTIAL-E2E-102',
          operationalStatus: 'ACTIVE',
          connectivityStatus: 'ONLINE',
          lastReceivedAt: '2026-07-30T13:00:00Z',
          batteryPercent: 82,
          charging: false,
          jetsonTempC: 51,
          speedMps: 0,
          robotMode: 'IDLE',
          missionStatus: null,
          overallHealthStatus: 'OK',
        }],
        page: 0,
        size: 100,
        totalElements: 2,
        totalPages: 1,
      })
      return
    }
    if (
      pathname
        === `/api/v1/robots/${PROVISIONING_ROBOT_ID}/credentials`
      && request.method() === 'POST'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(
        'robot-credential-e2e-csrf',
      )
      const idempotencyKey = request.headers()['idempotency-key']
      expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/u)
      provisioningIdempotencyKeys.push(idempotencyKey)

      if (!registryReconciled) {
        await fulfillJson(route, {
          code: 'ROBOT_CREDENTIAL_CONFLICT',
          message: 'MQTT Broker에 이미 같은 로봇 계정이 있습니다.',
        }, 409)
        return
      }

      await fulfillJson(route, {
        credentialId: '84000000-0000-4000-8000-000000000004',
        credentialType: 'MQTT_PASSWORD',
        mqtt: {
          brokerUri: 'ssl://mqtt.example.test:8883',
          username: 'RBT-101',
          password: 'provisioning-one-time-password',
          clientId: 'RBT-101',
        },
        expiresAt: null,
      }, 201)
      return
    }
    if (
      pathname
        === `/api/v1/robots/${PROVISIONING_ROBOT_ID}`
          + '/credentials/registry-reconcile'
      && request.method() === 'POST'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(
        'robot-credential-e2e-csrf',
      )
      expect(request.postDataJSON()).toEqual({
        reason: '로봇 관리 Credential 발급 전 Broker 잔존 계정 정리',
      })
      registryReconciled = true
      await fulfillJson(route, {
        robotId: PROVISIONING_ROBOT_ID,
        mqttId: 'RBT-101',
        registryStatus: 'IN_SYNC',
        driftDetected: false,
        activeCredentialId: null,
        activeCredentialVersion: null,
        checkedAt: '2026-07-30T13:01:00Z',
      })
      return
    }
    if (
      pathname === `/api/v1/robots/${ACTIVE_ROBOT_ID}/credentials`
      && request.method() === 'POST'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(
        'robot-credential-e2e-csrf',
      )
      const idempotencyKey = request.headers()['idempotency-key']
      expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/u)
      activeIdempotencyKeys.push(idempotencyKey)
      await fulfillJson(route, {
        credentialId: '84000000-0000-4000-8000-000000000005',
        credentialType: 'MQTT_PASSWORD',
        mqtt: {
          brokerUri: 'ssl://mqtt.example.test:8883',
          username: 'RBT-102',
          password: 'rotated-one-time-password',
          clientId: 'RBT-102',
        },
        expiresAt: null,
      }, 201)
      return
    }

    await fulfillJson(route, {
      code: 'E2E_ROUTE_NOT_FOUND',
      message: `처리하지 않은 E2E 경로: ${pathname}`,
    }, 404)
  })

  await page.goto('/robots')

  await expect(
    page.getByRole('heading', { name: 'Robot Credential 관리' }),
  ).toBeVisible()
  await expect(page.getByText(
    '임무를 생성할 때마다 바꾸지 않습니다.',
  )).toBeVisible()

  await page.getByRole('button', {
    name: '최초 발급·활성화',
    exact: true,
  }).click()
  const issueDialog = page.getByRole('dialog', {
    name: 'Robot 최초 발급·활성화',
  })
  await expect(issueDialog).toBeVisible()
  await issueDialog.getByRole('button', {
    name: '최초 발급·활성화',
    exact: true,
  }).click()
  await expect(issueDialog.getByRole('alert')).toContainText(
    'MQTT Broker에 이미 같은 로봇 계정이 있습니다.',
  )
  await issueDialog.getByRole('button', {
    name: '잔존 계정 정리 후 다시 발급',
  }).click()
  await expect(
    issueDialog.getByText('provisioning-one-time-password'),
  ).toBeVisible()
  expect(registryReconciled).toBe(true)
  expect(provisioningIdempotencyKeys).toHaveLength(2)
  expect(provisioningIdempotencyKeys[1]).toBe(
    provisioningIdempotencyKeys[0],
  )
  await issueDialog.getByRole('button', { name: '확인 후 닫기' }).click()

  await page.getByRole('button', {
    name: 'Credential 회전',
    exact: true,
  }).click()
  const rotateDialog = page.getByRole('dialog', {
    name: 'Robot Credential 회전',
  })
  await expect(rotateDialog).toContainText(
    '기존 비밀번호는 즉시 사용할 수 없게 됩니다.',
  )
  await rotateDialog.getByRole('button', {
    name: 'Credential 회전',
    exact: true,
  }).click()
  await expect(
    rotateDialog.getByText('rotated-one-time-password'),
  ).toBeVisible()
  expect(activeIdempotencyKeys).toHaveLength(1)
})
