import { expect, test, type Page } from '@playwright/test'
import { defectAt, fulfillJson, USER_ID } from './support/defectPage'
import { installFakeMap, readMapCalls, pushSseEvent } from './support/fakeNaverMap'

/**
 * 새로 탐지된 결함으로 지도가 옮겨가는 규칙.
 *
 * <p>**화면 밖일 때만** 옮긴다. 보고 있는 자리에 이미 들어와 있으면 지도가 움직이지
 * 않아야 한다 — 보던 위치를 이유 없이 빼앗기 때문이다.
 */

const BASE_LAT = 37.50123
const BASE_LNG = 127.04321
/*
 * 시작 배율. 16이면 화면이 1km 남짓을 덮는다 — "3km 밖"과 "100m 안"을 한 화면 기준으로
 * 갈라 볼 수 있다.
 */
const START_ZOOM = 16

const LAT_PER_METER = 1 / 111_320

/** 기준점에서 북쪽 `north`m 떨어진 좌표를 GeoJSON 순서로 만든다. */
function pointNorthOf(north: number): [number, number] {
  return [BASE_LNG, BASE_LAT + north * LAT_PER_METER]
}

function featureAt(index: number, coordinates: [number, number]) {
  return {
    type: 'Feature' as const,
    geometry: { type: 'Point' as const, coordinates },
    properties: {
      defectId: defectAt(index).defectId,
      incidentCode: defectAt(index).incidentCode,
      defectType: 'DAMAGED' as const,
      riskLevel: 'HIGH' as const,
      workflowStatus: 'NEW' as const,
      latestConfidence: 0.9,
    },
  }
}

const EXISTING = featureAt(0, pointNorthOf(0))
const NEW_DEFECT_INDEX = 1

/**
 * 지도·목록 조회를 막는다.
 *
 * <p>`newDefect`는 **뷰포트 bbox 없이 들어온 조회에만** 실려 나간다. 새 결함을 찾는
 * 조회가 bbox를 걸지 않는다는 것이 이 기능의 전제이고, bbox를 건 평소 조회는 화면 안의
 * 것만 돌려주는 실제 서버 동작과 같다.
 */
async function stubDefectMap(
  page: Page,
  newDefect: ReturnType<typeof featureAt>,
): Promise<{ mapQueries: string[]; reveal: () => void }> {
  const mapQueries: string[] = []
  // 새 결함은 "탐지된 뒤"에만 응답에 실린다. 실제 시간 순서와 같게 둔다.
  let detected = false
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const pathname = url.pathname
    if (pathname === '/api/v1/auth/session-state') {
      return fulfillJson(route, {
        authenticationState: 'AUTHENTICATED',
        challengeExpiresAt: null,
      })
    }
    if (pathname === '/api/v1/auth/me') {
      return fulfillJson(route, {
        userId: USER_ID,
        displayName: '실시간 지도 점검',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: ['DEFECT_STATUS_VIEW'],
      })
    }
    if (pathname === '/api/v1/defects/map') {
      const bbox = url.searchParams.get('bbox') ?? ''
      mapQueries.push(bbox)
      return fulfillJson(route, {
        type: 'FeatureCollection',
        features: detected && bbox === ''
          ? [EXISTING, newDefect]
          : [EXISTING],
      })
    }
    if (pathname === '/api/v1/defects') {
      return fulfillJson(route, {
        content: [], page: 0, size: 20, totalElements: 0, totalPages: 0,
      })
    }
    return fulfillJson(route, {}, 404)
  })
  return { mapQueries, reveal: () => { detected = true } }
}

/** 서버 `DefectSseEventResponse` 모양으로 새 결함 알림을 만든다. */
function createdEvent(defectId: string, incidentCode: string) {
  return {
    eventType: 'defect.created',
    defectId,
    incidentCode,
    patrolAreaId: null,
    defectType: 'DAMAGED',
    riskLevel: 'HIGH',
    workflowStatus: 'NEW',
    latestConfidence: 0.9,
    beforeStatus: null,
    afterStatus: 'NEW',
    mergedIntoDefectId: null,
    changedFields: null,
    occurredAt: '2026-08-07T02:00:00Z',
  }
}

async function openMapWithNewDefect(
  page: Page,
  metersAway: number,
): Promise<{
  newDefect: ReturnType<typeof featureAt>
  detect: () => void
}> {
  const newDefect = featureAt(NEW_DEFECT_INDEX, pointNorthOf(metersAway))
  await installFakeMap(page, {
    startZoom: START_ZOOM,
    baseLat: BASE_LAT,
    baseLng: BASE_LNG,
  })
  const { reveal } = await stubDefectMap(page, newDefect)
  await page.goto('/defects?allStatus=1')
  await expect(page.locator('.defect-map-canvas-wrap')).toBeVisible()
  // 지도가 처음 조회를 마칠 때까지 기다린다. 그 전에 이벤트를 밀면 판단 기준이 없다.
  await expect.poll(async () => (await readMapCalls(page)).clickHandlers)
    .toBeGreaterThan(0)
  return { newDefect, detect: reveal }
}

test.describe('새 결함이 들어왔을 때 지도 이동', () => {
  test('화면 밖에 생긴 결함이면 그 위치로 옮긴다', async ({ page }) => {
    // 3km 북쪽 — zoom 16 화면(1km 남짓)에서는 보이지 않는다.
    const { newDefect, detect } = await openMapWithNewDefect(page, 3_000)
    const before = await readMapCalls(page)
    detect()

    await pushSseEvent(
      page,
      'defect.created',
      createdEvent(
        newDefect.properties.defectId,
        newDefect.properties.incidentCode,
      ),
    )

    await expect
      .poll(async () => (await readMapCalls(page)).center.length)
      .toBeGreaterThan(before.center.length)

    const after = await readMapCalls(page)
    const moved = after.center[after.center.length - 1]
    const [longitude, latitude] = newDefect.geometry.coordinates
    console.log(`밖에 생긴 결함: 지도 중심 → ${moved.lat.toFixed(5)},`
      + ` ${moved.lng.toFixed(5)} (결함 ${latitude.toFixed(5)},`
      + ` ${longitude.toFixed(5)})`)
    expect(moved.lat).toBeCloseTo(latitude, 5)
    expect(moved.lng).toBeCloseTo(longitude, 5)
    // 옮기기만 한다. 배율은 사용자가 보던 그대로 둔다.
    expect(after.zoom).toEqual(before.zoom)
  })

  test('이미 보이는 자리에 생긴 결함이면 움직이지 않는다', async ({ page }) => {
    // 100m 북쪽 — 화면 가운데 근처라 그대로 보인다.
    const { newDefect, detect } = await openMapWithNewDefect(page, 100)
    const before = await readMapCalls(page)
    detect()

    await pushSseEvent(
      page,
      'defect.created',
      createdEvent(
        newDefect.properties.defectId,
        newDefect.properties.incidentCode,
      ),
    )

    /*
     * "움직이지 않았다"는 기다려도 확인할 수 없으니, 이벤트가 실제로 처리될 시간을
     * 준다. 실시간 훅이 400ms 디바운스 뒤 재조회를 요청하므로 그 재조회가 지도까지
     * 도달한 것을 보고 나서 센다.
     */
    await expect
      .poll(async () => (await readMapCalls(page)).clickHandlers,
        { timeout: 3_000 })
      .toBeGreaterThan(before.clickHandlers)

    const after = await readMapCalls(page)
    console.log(`안에 생긴 결함: 지도 중심 이동 ${after.center.length}회`)
    expect(after.center.length, '보이는 자리면 지도가 움직이지 않는다')
      .toBe(before.center.length)
  })
})
