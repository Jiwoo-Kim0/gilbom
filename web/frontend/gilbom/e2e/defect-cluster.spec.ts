import { expect, test, type Page } from '@playwright/test'
import { defectAt, fulfillJson, USER_ID } from './support/defectPage'

/**
 * 묶음을 한 번 클릭했을 때의 확대. 실제 지도 SDK 대신 가짜를 심어 두 가지를 본다.
 *
 * <ul>
 *   <li>클릭한 묶음의 결함이 **전부 화면에 남는가** — 10건을 눌렀으면 확대된 화면에
 *       (묶음이든 낱개든) 10건이 들어 있어야 한다.
 *   <li>그러면서 **묶음이 갈리는가** — 같은 숫자가 다시 나오면 안 된다.
 * </ul>
 *
 * <p>가짜의 `fromCoordToOffset`은 일부러 **화면 픽셀의 3배**를 돌려준다. 확대 계산이 그
 * 단위에 기대고 있으면 여기서 드러난다.
 */

const BASE_LAT = 37.50123
const BASE_LNG = 127.04321
/*
 * 시작 배율. 낮게 잡아야 시험용 결함들이 확실히 한 묶음이 된다 — 격자는 화면 픽셀 기준이라
 * 배율이 높으면 애초에 갈려서 시작한다.
 */
const START_ZOOM = 12
/** 화면 픽셀과 다른 단위를 흉내낸다. */
const UNIT_SCALE = 3
/** 묶기 격자 한 칸. `DefectMap`의 `CLUSTER_CELL_PX`와 같아야 한다. */
const CELL_PX = 44

const LAT_PER_METER = 1 / 111_320
const LNG_PER_METER = 1 / (111_320 * Math.cos((BASE_LAT * Math.PI) / 180))

/** 기준점에서 북쪽 `north`m, 동쪽 `east`m 떨어진 좌표를 GeoJSON 순서로 만든다. */
function pointAt(north: number, east: number): [number, number] {
  return [BASE_LNG + east * LNG_PER_METER, BASE_LAT + north * LAT_PER_METER]
}

function featuresAt(coordinates: [number, number][]) {
  return coordinates.map((point, index) => ({
    type: 'Feature' as const,
    geometry: { type: 'Point' as const, coordinates: point },
    properties: {
      defectId: defectAt(index).defectId,
      incidentCode: defectAt(index).incidentCode,
      defectType: 'DAMAGED' as const,
      riskLevel: 'HIGH' as const,
      workflowStatus: 'NEW' as const,
      latestConfidence: 0.9,
    },
  }))
}

async function installFakeMap(page: Page): Promise<void> {
  await page.addInitScript(
    ({ startZoom, unitScale, baseLat, baseLng }) => {
      Object.defineProperty(window, 'EventSource', {
        writable: true,
        value: class {
          close() {}
          addEventListener() {}
          removeEventListener() {}
        },
      })
      const calls: {
        zoom: number[]
        center: { lat: number; lng: number }[]
        clickHandlers: (() => void)[]
      } = { zoom: [], center: [], clickHandlers: [] }
      ;(window as unknown as { mapCalls: typeof calls }).mapCalls = calls

      class LatLng {
        constructor(private readonly la: number, private readonly lo: number) {}
        lat() { return this.la }
        lng() { return this.lo }
      }
      class LatLngBounds {
        constructor(private readonly sw: LatLng, private readonly ne: LatLng) {}
        getSW() { return this.sw }
        getNE() { return this.ne }
      }

      let zoom = startZoom
      let center = { lat: baseLat, lng: baseLng }
      const metersPerDegreeLng = 111_320 * Math.cos((baseLat * Math.PI) / 180)
      /** 실제 지도와 같은 성질: 한 단계 확대하면 1px이 덮는 거리가 절반이 된다. */
      const metersPerPixelAt = (z: number) =>
        (156_543.033_92 * Math.cos((baseLat * Math.PI) / 180)) / 2 ** z
      const canvas = () =>
        document.querySelector<HTMLElement>('.defect-map-canvas')
      const mapWidth = () => canvas()?.clientWidth ?? 800
      const mapHeight = () => canvas()?.clientHeight ?? 500

      const fakeMap = {
        // 지금 배율에 맞는 범위. 화면 폭으로 나누면 1px이 몇 m인지 나온다.
        getBounds: () => {
          const halfSpanLng =
            (metersPerPixelAt(zoom) * mapWidth()) / 2 / metersPerDegreeLng
          const halfSpanLat =
            (metersPerPixelAt(zoom) * mapHeight()) / 2 / 111_320
          return new LatLngBounds(
            new LatLng(center.lat - halfSpanLat, center.lng - halfSpanLng),
            new LatLng(center.lat + halfSpanLat, center.lng + halfSpanLng),
          )
        },
        getProjection: () => ({
          // 화면 픽셀의 unitScale배 — 단위가 어긋난 상황을 만든다.
          fromCoordToOffset: (latLng: LatLng) => ({
            x: (latLng.lng() * metersPerDegreeLng * unitScale)
              / metersPerPixelAt(zoom),
            y: (latLng.lat() * 111_320 * unitScale) / metersPerPixelAt(zoom),
          }),
        }),
        getZoom: () => zoom,
        setZoom: (next: number) => {
          calls.zoom.push(next)
          zoom = next
        },
        setCenter: (next: LatLng) => {
          center = { lat: next.lat(), lng: next.lng() }
          calls.center.push(center)
        },
        fitBounds: () => {},
      }

      Object.defineProperty(window, 'naver', {
        writable: true,
        value: {
          maps: {
            Map: class { constructor() { return fakeMap } },
            Marker: class {
              setMap() {}
              setIcon() {}
              setZIndex() {}
              getPosition() { return new LatLng(baseLat, baseLng) }
            },
            LatLng,
            LatLngBounds,
            Point: class { constructor(public x: number, public y: number) {} },
            Size: class {
              constructor(public width: number, public height: number) {}
            },
            Event: {
              addListener: (
                target: unknown,
                type: string,
                handler: () => void,
              ) => {
                // 마커 클릭 핸들러만 모은다(지도 이벤트는 map 객체가 target이다).
                if (type === 'click' && target !== fakeMap) {
                  calls.clickHandlers.push(handler)
                }
                return {}
              },
              removeListener: () => {},
            },
            Position: { TOP_RIGHT: 'TOP_RIGHT' },
            ZoomControlStyle: { SMALL: 'SMALL' },
          },
        },
      })
    },
    {
      startZoom: START_ZOOM,
      unitScale: UNIT_SCALE,
      baseLat: BASE_LAT,
      baseLng: BASE_LNG,
    },
  )
}

async function stubMap(
  page: Page,
  features: ReturnType<typeof featuresAt>,
): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/api/v1/auth/session-state') {
      return fulfillJson(route, {
        authenticationState: 'AUTHENTICATED',
        challengeExpiresAt: null,
      })
    }
    if (pathname === '/api/v1/auth/me') {
      return fulfillJson(route, {
        userId: USER_ID,
        displayName: '묶음 점검',
        roles: [{ roleCode: 'OPERATOR', roleName: '운영자' }],
        permissions: ['DEFECT_STATUS_VIEW'],
      })
    }
    if (pathname === '/api/v1/defects/map') {
      return fulfillJson(route, { type: 'FeatureCollection', features })
    }
    if (pathname === '/api/v1/defects') {
      return fulfillJson(route, {
        content: [], page: 0, size: 20, totalElements: 0, totalPages: 0,
      })
    }
    /*
     * 묶음 목록은 결함마다 상세(위치 이름)와 사진 URL을 따로 받는다. 지도 GeoJSON에는
     * 결함 코드까지만 담겨 있기 때문이다.
     */
    const detail = /^\/api\/v1\/defects\/([\w-]+)(\/image-url)?$/.exec(pathname)
    if (detail !== null) {
      const [, defectId, imageUrl] = detail
      // `defectAt(i)`가 만든 id의 끝 12자리가 i+1이다.
      const index = Number(defectId.slice(-12)) - 1
      if (imageUrl === undefined) {
        return fulfillJson(route, {
          defect: defectAt(index),
          resolutionNote: null,
          riskScore: 50,
          riskRuleVersion: 'v1',
          riskCalculatedAt: '2026-08-01T00:00:00Z',
          mergedIntoDefectId: null,
          mergedDefectIds: [],
          createdAt: '2026-08-01T00:00:00Z',
          updatedAt: '2026-08-01T00:00:00Z',
        })
      }
      // 첫 건에만 사진이 있다. 나머지는 아직 연결되지 않은 정상 상태(404)다.
      return index === 0
        ? fulfillJson(route, {
            defectId,
            eventId: null,
            imageId: 'e2e-image',
            imageUrl: '/e2e-defect-photo.png',
            expiresAt: '2099-01-01T00:00:00Z',
            imageWidth: 1,
            imageHeight: 1,
            bboxNorm: null,
          })
        : fulfillJson(route, {}, 404)
    }
    return fulfillJson(route, {}, 404)
  })

  // 사진은 실제로 받아지는 URL이어야 `<img>`가 깨진 상태로 남지 않는다.
  await page.route('**/e2e-defect-photo.png', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8'
        + 'BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
        'base64',
      ),
    }),
  )
}

interface ClickOutcome {
  /** 확대 뒤 배율. */
  zoom: number
  /** 확대 뒤 화면에 남은 구성원 수. */
  visible: number
  /** 확대 뒤 다시 묶으면 몇 덩어리(낱개 포함)가 되는가. */
  groups: number
  /** 가장 큰 덩어리에 든 건수. */
  largestGroup: number
}

/**
 * 묶음 마커를 클릭하고, 그 결과를 **화면 픽셀로 다시 재서** 돌려준다.
 *
 * <p>가짜 SDK는 idle 이벤트를 쏘지 않으니 마커가 다시 그려지지는 않는다. 그래서 같은
 * 좌표들을 확대된 배율·중심으로 다시 계산해 "화면에 남았는가"와 "몇 덩어리인가"를 센다.
 */
async function clickCluster(
  page: Page,
  coordinates: [number, number][],
): Promise<ClickOutcome> {
  await expect
    .poll(() => page.evaluate(() =>
      (window as unknown as { mapCalls: { clickHandlers: unknown[] } })
        .mapCalls.clickHandlers.length))
    .toBeGreaterThan(0)

  const handlerCount = await page.evaluate(() =>
    (window as unknown as { mapCalls: { clickHandlers: unknown[] } })
      .mapCalls.clickHandlers.length)
  expect(handlerCount, '전부 한 묶음이어야 시험이 성립한다').toBe(1)

  return page.evaluate(
    ({ coordinates, cellPx, baseLat }) => {
      const calls = (window as unknown as {
        mapCalls: {
          zoom: number[]
          center: { lat: number; lng: number }[]
          clickHandlers: (() => void)[]
        }
      }).mapCalls
      calls.clickHandlers[0]?.()
      if (calls.zoom.length !== 1) {
        throw new Error(`클릭 한 번에 확대가 ${calls.zoom.length}번 일어났다`)
      }
      const zoom = calls.zoom[0]
      const center = calls.center[calls.center.length - 1]
      const element = document.querySelector<HTMLElement>('.defect-map-canvas')
      const width = element?.clientWidth ?? 800
      const height = element?.clientHeight ?? 500

      const metersPerPixel =
        (156_543.033_92 * Math.cos((baseLat * Math.PI) / 180)) / 2 ** zoom
      const metersPerDegreeLng = 111_320 * Math.cos((baseLat * Math.PI) / 180)
      const screenPoints = coordinates.map(([lng, lat]) => ({
        x: ((lng - center.lng) * metersPerDegreeLng) / metersPerPixel
          + width / 2,
        y: -((lat - center.lat) * 111_320) / metersPerPixel + height / 2,
      }))

      const visible = screenPoints.filter((point) =>
        point.x >= 0 && point.x <= width && point.y >= 0 && point.y <= height,
      ).length
      const sizeByCell = new Map<string, number>()
      for (const point of screenPoints) {
        const key = `${Math.floor(point.x / cellPx)}`
          + `:${Math.floor(point.y / cellPx)}`
        sizeByCell.set(key, (sizeByCell.get(key) ?? 0) + 1)
      }
      return {
        zoom,
        visible,
        groups: sizeByCell.size,
        largestGroup: Math.max(...sizeByCell.values()),
      }
    },
    { coordinates, cellPx: CELL_PX, baseLat: BASE_LAT },
  )
}

test.describe('결함 지도 묶음 클릭', () => {
  test('넓게 퍼진 10건을 눌러도 10건 모두 화면에 남는다', async ({ page }) => {
    /*
     * 사용자가 본 문제. 100m에 퍼진 10건 안에 2m 붙은 쌍이 하나 있다. 가장 가까운 쌍을
     * 갈라내려던 방식은 그 쌍 때문에 2m가 44px이 될 배율까지 뛰었고, 그 배율에서 화면은
     * 95m밖에 못 담아 **양 끝 결함이 화면 밖으로 나갔다.**
     */
    const coordinates: [number, number][] = [
      ...Array.from({ length: 8 }, (_, index): [number, number] =>
        pointAt(0, Math.round((index * 100) / 7))),
      pointAt(0, 50),
      pointAt(2, 50),
    ]
    await installFakeMap(page)
    await stubMap(page, featuresAt(coordinates))
    await page.goto('/defects?allStatus=1')
    await expect(page.locator('.defect-map-canvas-wrap')).toBeVisible()

    const outcome = await clickCluster(page, coordinates)
    console.log(`퍼진 10건: ${START_ZOOM} → ${outcome.zoom},`
      + ` 화면에 남은 건수 ${outcome.visible}, 덩어리 ${outcome.groups},`
      + ` 가장 큰 덩어리 ${outcome.largestGroup}`)

    expect(outcome.visible, '누른 묶음의 결함은 모두 화면에 남아야 한다')
      .toBe(coordinates.length)
    expect(outcome.groups, '묶음이 갈려야 한다').toBeGreaterThan(1)
    expect(outcome.largestGroup, '같은 수의 묶음이 다시 나오면 안 된다')
      .toBeLessThan(coordinates.length)
  })

  test('촘촘한 13건은 한 번에 전부 갈린다', async ({ page }) => {
    // 8m 격자로 놓은 13건. 화면에 담을 여유가 충분해 최대한 확대되고 전부 갈린다.
    const coordinates: [number, number][] = Array.from(
      { length: 13 },
      (_, index): [number, number] =>
        pointAt(Math.floor(index / 4) * 8, (index % 4) * 8),
    )
    await installFakeMap(page)
    await stubMap(page, featuresAt(coordinates))
    await page.goto('/defects?allStatus=1')
    await expect(page.locator('.defect-map-canvas-wrap')).toBeVisible()

    const outcome = await clickCluster(page, coordinates)
    console.log(`촘촘한 13건: ${START_ZOOM} → ${outcome.zoom},`
      + ` 화면에 남은 건수 ${outcome.visible}, 덩어리 ${outcome.groups}`)

    expect(outcome.visible).toBe(coordinates.length)
    expect(outcome.groups, '여유가 있으면 전부 갈린다').toBe(coordinates.length)
  })
})

/**
 * 셀 만한 묶음은 확대만 하지 않고 구성원 목록까지 편다. 확대만으로는 "이 동네에 몇 건"
 * 까지만 알 수 있고, 코드를 보려면 마커를 하나씩 눌러야 했다.
 */
test.describe('묶음 결함 목록', () => {
  /** 8m 격자 — 시작 배율에서는 한 묶음이고, 확대하면 갈린다. */
  function tightPoints(count: number): [number, number][] {
    return Array.from(
      { length: count },
      (_, index): [number, number] =>
        pointAt(Math.floor(index / 4) * 8, (index % 4) * 8),
    )
  }

  async function openCluster(
    page: Page,
    count: number,
  ): Promise<void> {
    await installFakeMap(page)
    await stubMap(page, featuresAt(tightPoints(count)))
    await page.goto('/defects?allStatus=1')
    await expect(page.locator('.defect-map-canvas-wrap')).toBeVisible()
    await expect
      .poll(() => page.evaluate(() =>
        (window as unknown as { mapCalls: { clickHandlers: unknown[] } })
          .mapCalls.clickHandlers.length))
      .toBe(1)
    await page.evaluate(() =>
      (window as unknown as { mapCalls: { clickHandlers: (() => void)[] } })
        .mapCalls.clickHandlers[0]?.())
  }

  test('10건 이하를 누르면 코드·사진·위치가 담긴 목록이 열린다', async ({ page }) => {
    await openCluster(page, 3)

    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    await expect(list).toContainText('이 위치의 결함 3건')
    const items = list.getByRole('listitem')
    await expect(items).toHaveCount(3)

    // 결함 코드는 지도 GeoJSON에 있어 바로 보인다.
    await expect(items.nth(0)).toContainText('INC-20260805-000001')
    // 위치 이름과 사진은 결함마다 따로 받는다.
    await expect(items.nth(0)).toContainText('역삼역 3번 출구 앞 보도')
    await expect(items.nth(0).locator('img')).toBeVisible()
    // 이름이 없는 결함은 구역 코드로 떨어진다.
    await expect(items.nth(2)).toContainText('ZONE-GN-0007')
    // 사진이 없는 결함은 자리표시가 아니라 "없음"으로 끝나야 한다.
    await expect(items.nth(1).getByLabel('사진 없음')).toBeVisible()
    await expect(items.nth(1).locator('img')).toHaveCount(0)
  })

  test('11건 이상이면 확대만 하고 목록은 열지 않는다', async ({ page }) => {
    await openCluster(page, 11)

    await expect(page.locator('.defect-cluster-list')).toHaveCount(0)
    // 확대는 그대로 일어난다.
    const zoomed = await page.evaluate(() =>
      (window as unknown as { mapCalls: { zoom: number[] } }).mapCalls.zoom)
    expect(zoomed).toHaveLength(1)
    expect(zoomed[0]).toBeGreaterThan(START_ZOOM)
  })

  test('목록에서 고르면 상세 카드가 뜨고 그 줄이 선택으로 남는다', async ({ page }) => {
    await openCluster(page, 3)

    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    const second = list.getByRole('listitem').nth(1)
    await second.getByRole('button').click()

    // 목록 표에서 고른 것과 같다 — 지도 위 상세 카드가 열린다.
    const card = page.locator('.defect-card')
    await expect(card).toBeVisible()
    await expect(card).toContainText('INC-20260805-000002')

    // 고른 줄은 표시가 남는다. 지도 마커의 선택 표시와 짝이다.
    await expect(second.locator('.defect-cluster-list-item'))
      .toHaveClass(/is-selected/)
    // 목록은 닫지 않는다. 형제 결함을 이어서 훑는 것이 이 목록의 쓸모다.
    await expect(list).toBeVisible()
  })

  test('닫으면 목록만 사라지고 지도는 그대로다', async ({ page }) => {
    await openCluster(page, 3)

    await page.getByRole('button', { name: '묶인 결함 목록 닫기' }).click()
    await expect(page.locator('.defect-cluster-list')).toHaveCount(0)
    await expect(page.locator('.defect-map-canvas-wrap')).toBeVisible()
  })
})
