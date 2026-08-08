import { expect, test, type Page } from '@playwright/test'
import { defectAt, fulfillJson, USER_ID } from './support/defectPage'
import { installFakeMap, readMapCalls, zoomMapBy } from './support/fakeNaverMap'

/**
 * 축소하면 지도 위에 펼쳐둔 것(묶음 목록·상세 카드)을 닫는다.
 *
 * <p>축소는 "넓게 보고 싶다"는 뜻인데 카드가 지도의 절반을 덮고 있으면 정작 넓힌 화면이
 * 가려진다. 확대는 반대로 들여다보는 중이니 그대로 둔다.
 */

const BASE_LAT = 37.50123
const BASE_LNG = 127.04321
const START_ZOOM = 15

/** 한 묶음이 되도록 촘촘히 놓는다(8m 간격). */
function tightPoints(count: number): [number, number][] {
  return Array.from({ length: count }, (_, index) => [
    BASE_LNG,
    BASE_LAT + (index * 8) / 111_320,
  ] as [number, number])
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

async function stubDefects(
  page: Page,
  points: [number, number][],
): Promise<void> {
  const features = points.map((point, index) => feature(index, point))
  const content = points.map((point, index) => ({
    ...defectAt(index),
    location: { longitude: point[0], latitude: point[1] },
  }))
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
        displayName: '축소 닫기 점검',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: ['DEFECT_STATUS_VIEW', 'DEFECT_STATUS_UPDATE'],
      })
    }
    if (pathname === '/api/v1/defects/map') {
      return fulfillJson(route, { type: 'FeatureCollection', features })
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
    // 상세 카드가 부르는 종단은 비워 둔다. 카드가 떠 있는지만 보면 된다.
    return fulfillJson(route, {}, 404)
  })
}

async function openDefectPage(
  page: Page,
  points: [number, number][],
): Promise<void> {
  await installFakeMap(page, {
    startZoom: START_ZOOM,
    baseLat: BASE_LAT,
    baseLng: BASE_LNG,
  })
  await stubDefects(page, points)
  await page.goto('/defects?allStatus=1')
  await expect(page.locator('.defect-table-body-row')).toHaveCount(points.length)
}

/** 묶음 마커를 클릭한다. 가짜 지도가 모아둔 클릭 핸들러를 직접 부른다. */
async function clickClusterMarker(page: Page): Promise<void> {
  await expect.poll(async () => (await readMapCalls(page)).clickHandlers)
    .toBeGreaterThan(0)
  await page.evaluate(() => {
    const { clickHandlers } = (window as unknown as {
      mapCalls: { clickHandlers: (() => void)[] }
    }).mapCalls
    clickHandlers[clickHandlers.length - 1]?.()
  })
}

test.describe('축소하면 지도 위 카드를 닫는다', () => {
  test('상세 카드가 닫힌다', async ({ page }) => {
    await openDefectPage(page, tightPoints(2))

    await page.locator('.defect-table-body-row').first().click()
    await expect(page.locator('.defect-card')).toBeVisible()

    await zoomMapBy(page, -1)
    await expect(page.locator('.defect-card')).toHaveCount(0)
  })

  test('확대할 때는 카드를 그대로 둔다', async ({ page }) => {
    // 들여다보는 중이니 카드도 필요하다. 축소만 닫는 신호다.
    await openDefectPage(page, tightPoints(2))

    await page.locator('.defect-table-body-row').first().click()
    await expect(page.locator('.defect-card')).toBeVisible()

    await zoomMapBy(page, 1)
    await expect(page.locator('.defect-card')).toBeVisible()
  })

  test('묶음 목록이 닫힌다', async ({ page }) => {
    await openDefectPage(page, tightPoints(3))
    await clickClusterMarker(page)

    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    await expect(list).toBeVisible()

    await zoomMapBy(page, -1)
    await expect(list).toHaveCount(0)
  })

  test('첫 조회에 맞추는 확대·축소로는 닫지 않는다', async ({ page }) => {
    /*
     * `fitBounds`는 우리가 부르는 것이고 배율이 내려갈 수도 있다. 그때 카드를 닫으면
     * 조건을 바꿨을 뿐인데 보던 카드가 사라진다.
     */
    await openDefectPage(page, tightPoints(2))
    await page.locator('.defect-table-body-row').first().click()
    await expect(page.locator('.defect-card')).toBeVisible()

    // 검색어를 넣으면 필터가 바뀌어 지도가 다시 맞춰진다(fitBounds).
    await page.locator('.defect-search-bar input').fill('INC')
    await page.locator('.defect-search-bar input').press('Enter')

    await expect(page.locator('.defect-card')).toBeVisible()
  })
})
