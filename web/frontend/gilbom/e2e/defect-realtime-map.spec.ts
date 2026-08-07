import { expect, test, type Page } from '@playwright/test'
import { defectAt, fulfillJson, USER_ID } from './support/defectPage'
import {
  installFakeMap,
  readMapCalls,
  readMapView,
  pushSseEvent,
} from './support/fakeNaverMap'

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

/** 서버 계약 순서는 `minLon,minLat,maxLon,maxLat`다. */
function inBbox(
  feature: ReturnType<typeof featureAt>,
  bbox: string,
): boolean {
  const [minLon, minLat, maxLon, maxLat] = bbox.split(',').map(Number)
  const [longitude, latitude] = feature.geometry.coordinates
  return longitude >= minLon && longitude <= maxLon
    && latitude >= minLat && latitude <= maxLat
}

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
      const all = detected ? [EXISTING, newDefect] : [EXISTING]
      return fulfillJson(route, {
        type: 'FeatureCollection',
        // 실제 서버처럼 bbox로 걸러 준다. 지도가 엉뚱한 자리에 멈추면 마커가 없다.
        features: bbox === '' ? all : all.filter((feature) => inBbox(feature, bbox)),
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
  startZoom = START_ZOOM,
): Promise<{
  newDefect: ReturnType<typeof featureAt>
  detect: () => void
}> {
  const newDefect = featureAt(NEW_DEFECT_INDEX, pointNorthOf(metersAway))
  await installFakeMap(page, {
    startZoom,
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

/** `revealZoom`이 맞추는 배율. 소스와 같은 값을 시험에서도 쓴다. */
const REVEAL_ZOOM = 17

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

    const [longitude, latitude] = newDefect.geometry.coordinates
    /*
     * 여기까지가 진짜 요구사항이다. 옮기고 확대하는 것으로는 부족하고, **그 화면에 그
     * 결함 마커가 실제로 올라와야** 한다. 뷰포트 bbox 재조회가 새 자리에서 돌아야
     * 마커가 생기므로, 이동이 중간에 끊기면 여기서 잡힌다.
     */
    await expect
      .poll(async () => {
        const { markers } = await readMapCalls(page)
        return markers.some((marker) =>
          Math.abs(marker.lat - latitude) < 1e-6
          && Math.abs(marker.lng - longitude) < 1e-6)
      }, { timeout: 5_000 })
      .toBe(true)

    const view = await readMapView(page)
    expect(latitude, '결함이 보이는 범위 안에 있어야 한다')
      .toBeGreaterThan(view.south)
    expect(latitude).toBeLessThan(view.north)
    expect(longitude).toBeGreaterThan(view.west)
    expect(longitude).toBeLessThan(view.east)

    const after = await readMapCalls(page)
    const moved = after.center[after.center.length - 1]
    console.log(`밖에 생긴 결함: 지도 중심 → ${moved.lat.toFixed(5)},`
      + ` ${moved.lng.toFixed(5)} (결함 ${latitude.toFixed(5)},`
      + ` ${longitude.toFixed(5)})`)
    expect(moved.lat).toBeCloseTo(latitude, 5)
    expect(moved.lng).toBeCloseTo(longitude, 5)
    /*
     * 옮기는 것만으로는 어디에 생겼는지 알 수 없어서, 알아볼 배율(17)까지 확대한다.
     * 확대는 옮긴 **뒤에** 일어나야 한다 — 먼저 확대하면 엉뚱한 자리를 파고든다.
     */
    expect((await readMapView(page)).zoom).toBe(REVEAL_ZOOM)
  })

  test('이미 더 깊이 보고 있었다면 배율을 줄이지 않는다', async ({ page }) => {
    /*
     * zoom 19에서 보고 있다가 화면 밖(500m)에 결함이 생긴 경우. 옮기기는 하지만
     * 사용자가 확대해 둔 것을 17로 되돌리면 안 된다.
     */
    const { newDefect, detect } = await openMapWithNewDefect(page, 500, 19)
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

    /*
     * 배율은 **결과**로 본다. 이동과 확대를 한 번에 하는 SDK 호출(`morph`)은 같은 값이라도
     * 배율 인수를 함께 넘기므로, "호출이 없었는가"로 보면 구현에 묶인 시험이 된다.
     */
    const view = await readMapView(page)
    console.log(`깊이 보던 중: 배율 ${view.zoom}`)
    expect(view.zoom, '19에서 17로 물러나면 안 된다').toBe(19)

    // 옮긴 자리에 마커도 올라와야 한다.
    const [longitude, latitude] = newDefect.geometry.coordinates
    await expect
      .poll(async () => {
        const { markers } = await readMapCalls(page)
        return markers.some((marker) =>
          Math.abs(marker.lat - latitude) < 1e-6
          && Math.abs(marker.lng - longitude) < 1e-6)
      }, { timeout: 5_000 })
      .toBe(true)
  })

  test('결과가 그대로면 지도가 깜빡이지 않는다', async ({ page }) => {
    /*
     * 실시간 알림이 오면 같은 조건으로 지도를 다시 읽는다. 대개 결과가 그대로인데,
     * 그때마다 전면을 덮는 로딩과 마커 재생성이 일어나면 사용자에게는 깜빡임으로 읽힌다.
     */
    const { newDefect } = await openMapWithNewDefect(page, 100)
    const before = await readMapCalls(page)

    /*
     * 전면 로딩은 순간이라 나중에 세면 놓친다. DOM이 바뀔 때마다 확인해서 **한 번이라도
     * 떴는지**를 남긴다.
     */
    await page.evaluate(() => {
      const flag = { seen: 0 }
      ;(window as unknown as { coveringSeen: typeof flag }).coveringSeen = flag
      new MutationObserver(() => {
        if (document.querySelector('.defect-map-loading') !== null) {
          flag.seen += 1
        }
      }).observe(document.body, { subtree: true, childList: true })
    })

    // 이미 있는 결함에 대한 알림을 세 번 보낸다(응답 내용은 그대로다).
    for (let round = 0; round < 3; round += 1) {
      await pushSseEvent(
        page,
        'defect.created',
        createdEvent(
          EXISTING.properties.defectId,
          EXISTING.properties.incidentCode,
        ),
      )
      await page.waitForTimeout(700)
    }

    const coveringSeen = await page.evaluate(() =>
      (window as unknown as { coveringSeen: { seen: number } })
        .coveringSeen.seen)
    expect(coveringSeen, '갱신 중에 지도를 덮는 로딩이 떠서는 안 된다').toBe(0)

    const after = await readMapCalls(page)
    console.log(`같은 결과 3회 갱신: 마커 생성 ${before.markersCreated}`
      + ` → ${after.markersCreated}, 화면 마커 ${after.markers.length}개`)
    expect(after.markersCreated, '같은 결과면 마커를 다시 만들지 않는다')
      .toBe(before.markersCreated)
    expect(after.markers.length, '마커는 그대로 남아 있다')
      .toBe(before.markers.length)
    // 새 결함이 아니었으니 지도도 움직이지 않는다.
    expect(after.center.length).toBe(before.center.length)
    expect(newDefect.properties.defectId).not.toBe(EXISTING.properties.defectId)
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
    expect((await readMapView(page)).zoom, '보이는 자리면 배율도 그대로다')
      .toBe(START_ZOOM)
  })
})
