import { expect, test, type Page } from '@playwright/test'
import { defectAt, fulfillJson, USER_ID } from './support/defectPage'
import {
  installFakeMap,
  readMapCalls,
  readMapView,
} from './support/fakeNaverMap'

/**
 * 목록에서 결함을 고르면 그 마커로 옮기면서 **묶이지 않을 만큼** 확대한다.
 *
 * <p>고른 결함은 원래도 묶음에서 빠져 따로 그려진다(`DefectMap`의 "선택한 결함은 묶음에
 * 숨기지 않는다"). 문제는 넓게 보고 있을 때다 — 이웃 묶음 마커가 같은 자리에 겹쳐 앉아
 * 어느 것이 고른 것인지 알 수 없다. 그래서 "옮겼는가"가 아니라 **가장 가까운 두 마커가
 * 격자 한 칸(44px)만큼 벌어졌는가**를 본다.
 */

const BASE_LAT = 37.50123
const BASE_LNG = 127.04321
/** 넓게 보고 있는 상태에서 시작한다. 이 배율에서 8m 이웃은 반드시 묶인다. */
const START_ZOOM = 14
/** 묶기 격자 한 칸. `DefectMap`의 `CLUSTER_CELL_PX`와 같아야 한다. */
const CELL_PX = 44

const LAT_PER_METER = 1 / 111_320

function pointNorthOf(north: number): [number, number] {
  return [BASE_LNG, BASE_LAT + north * LAT_PER_METER]
}

function listItem(index: number, coordinates: [number, number]) {
  return {
    ...defectAt(index),
    location: { longitude: coordinates[0], latitude: coordinates[1] },
  }
}

function feature(index: number, coordinates: [number, number]) {
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

/** 서버 계약 순서는 `minLon,minLat,maxLon,maxLat`다. */
function inBbox(point: [number, number], bbox: string): boolean {
  const [minLon, minLat, maxLon, maxLat] = bbox.split(',').map(Number)
  return point[0] >= minLon && point[0] <= maxLon
    && point[1] >= minLat && point[1] <= maxLat
}

/**
 * 목록·지도 조회를 막는다. 지도 조회는 실제 서버처럼 **bbox로 걸러** 준다 — 확대가
 * 모자라 묶인 채로 남는지를 이 필터가 드러낸다.
 */
async function stubDefects(
  page: Page,
  coordinates: [number, number][],
): Promise<void> {
  const features = coordinates.map((point, index) => feature(index, point))
  const content = coordinates.map((point, index) => listItem(index, point))
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
        displayName: '포커스 확대 점검',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: ['DEFECT_STATUS_VIEW'],
      })
    }
    if (pathname === '/api/v1/defects/map') {
      const bbox = url.searchParams.get('bbox') ?? ''
      return fulfillJson(route, {
        type: 'FeatureCollection',
        features: bbox === ''
          ? features
          : features.filter((item) =>
              inBbox(item.geometry.coordinates, bbox)),
      })
    }
    if (pathname === '/api/v1/defects') {
      return fulfillJson(route, {
        content,
        page: 0,
        size: 20,
        totalElements: content.length,
        totalPages: 1,
      })
    }
    // 상세 카드가 부르는 종단은 비워 둔다. 이 스펙이 보는 것은 지도뿐이다.
    return fulfillJson(route, {}, 404)
  })
}

async function openList(
  page: Page,
  coordinates: [number, number][],
): Promise<void> {
  await installFakeMap(page, {
    startZoom: START_ZOOM,
    baseLat: BASE_LAT,
    baseLng: BASE_LNG,
  })
  await stubDefects(page, coordinates)
  await page.goto('/defects?allStatus=1')
  await expect(page.locator('.defect-table-body-row'))
    .toHaveCount(coordinates.length)
}

/** 고른 좌표에 마커가 있는지. 고른 결함은 묶음에서 빼고 따로 그린다. */
async function hasOwnMarker(
  page: Page,
  [longitude, latitude]: [number, number],
): Promise<boolean> {
  const { markers } = await readMapCalls(page)
  return markers.some((marker) =>
    Math.abs(marker.lat - latitude) < 1e-9
    && Math.abs(marker.lng - longitude) < 1e-9)
}

/**
 * 지금 화면에서 가장 가까운 두 마커 사이 거리(px).
 *
 * <p>**이것이 요구사항이다.** 고른 결함은 원래도 묶음에서 빠져 따로 그려지지만, 넓게 보고
 * 있으면 이웃 묶음 마커가 같은 자리에 겹쳐 앉아 어느 것이 고른 것인지 알 수 없다. 격자 한
 * 칸(44px)만큼 벌어져야 갈라져 보인다.
 */
async function nearestMarkerGapPx(page: Page): Promise<number> {
  const [{ markers }, view] = await Promise.all([
    readMapCalls(page),
    readMapView(page),
  ])
  if (markers.length < 2) {
    return Number.POSITIVE_INFINITY
  }
  const metersPerPixel = (156_543.033_92
    * Math.cos((BASE_LAT * Math.PI) / 180)) / 2 ** view.zoom
  const metersPerDegreeLng = 111_320 * Math.cos((BASE_LAT * Math.PI) / 180)
  let nearest = Number.POSITIVE_INFINITY
  for (let i = 0; i < markers.length; i += 1) {
    for (let j = i + 1; j < markers.length; j += 1) {
      // 격자가 정사각형이라 축 방향 최대값으로 본다(묶기와 같은 기준).
      const northSouth = Math.abs(markers[i].lat - markers[j].lat) * 111_320
      const eastWest = Math.abs(markers[i].lng - markers[j].lng)
        * metersPerDegreeLng
      nearest = Math.min(
        nearest,
        Math.max(northSouth, eastWest) / metersPerPixel,
      )
    }
  }
  return nearest
}

test.describe('목록에서 고른 결함으로 확대', () => {
  test('8m 옆에 이웃이 있어도 낱개로 보일 만큼 확대한다', async ({ page }) => {
    /*
     * zoom 14에서 1px은 약 7.6m다. 8m 이웃은 1px 남짓 떨어져 있어 반드시 묶인다.
     * 갈리려면 격자 한 칸(44px)만큼 벌어져야 하므로 여섯 단계쯤 들어가야 한다.
     */
    const target = pointNorthOf(0)
    const coordinates: [number, number][] = [target, pointNorthOf(8)]
    await openList(page, coordinates)

    // 처음에는 둘이 한 묶음이다 — 이 시험이 성립하는 전제다.
    await expect.poll(async () => (await readMapCalls(page)).markers.length)
      .toBe(1)

    await page.locator('.defect-table-body-row').first().click()

    /*
     * 옮기고 → 확대 → bbox 재조회 → 마커 재생성까지 기다린다. 확대가 모자라면 두 마커가
     * 겹친 채 남아 이 값이 44px를 넘지 못한다.
     */
    await expect
      .poll(() => nearestMarkerGapPx(page), { timeout: 5_000 })
      .toBeGreaterThanOrEqual(CELL_PX)

    const view = await readMapView(page)
    const { markers } = await readMapCalls(page)
    console.log(`고른 결함: 배율 ${START_ZOOM} → ${view.zoom},`
      + ` 마커 ${markers.length}개,`
      + ` 가장 가까운 간격 ${Math.round(await nearestMarkerGapPx(page))}px`)
    expect(view.zoom, '넓게 보던 배율에 머물러선 안 된다')
      .toBeGreaterThan(START_ZOOM)
    expect(markers.length, '이웃과 갈려 둘 다 낱개로 보인다').toBe(2)
    expect(await hasOwnMarker(page, target), '고른 결함 자리에 마커가 있다')
      .toBe(true)
  })

  test('이웃이 없으면 배율을 건드리지 않는다', async ({ page }) => {
    // 묶일 일이 없으니 확대할 이유도 없다. 옮기기만 한다.
    const target = pointNorthOf(0)
    await openList(page, [target])

    await page.locator('.defect-table-body-row').first().click()
    await expect.poll(() => hasOwnMarker(page, target)).toBe(true)

    const view = await readMapView(page)
    console.log(`이웃 없음: 배율 ${view.zoom}`)
    expect(view.zoom, '혼자면 확대하지 않는다').toBe(START_ZOOM)
  })
})
