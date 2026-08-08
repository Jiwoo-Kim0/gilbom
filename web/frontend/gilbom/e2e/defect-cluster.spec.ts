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

async function installFakeMap(
  page: Page,
  startZoom = START_ZOOM,
): Promise<void> {
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
        /**
         * `panTo`(애니메이션)로 옮긴 자리만 따로 센다. 확대까지 함께 하는 경로는
         * 애니메이션을 쓸 수 없어 `setCenter`로 옮기므로, "옮겨졌나"는 `center`로 본다.
         */
        panned: { lat: number; lng: number }[]
        clickHandlers: (() => void)[]
      } = { zoom: [], center: [], panned: [], clickHandlers: [] }
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
        panTo: (next: LatLng) => {
          center = { lat: next.lat(), lng: next.lng() }
          calls.panned.push(center)
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
      startZoom,
      unitScale: UNIT_SCALE,
      baseLat: BASE_LAT,
      baseLng: BASE_LNG,
    },
  )
}

interface StubMapOptions {
  /** 지도 아래 목록 표에 실을 결함. 표를 쓰지 않는 시험은 비워둔다. */
  listContent?: ReturnType<typeof defectAt>[]
  /** 묶음 목록 조회에 이 상태로 답한다. 401 회귀 시험이 쓴다. */
  clusterDetailsStatus?: number
  /** 묶음 목록 조회가 들어올 때마다 query string을 담는다. 요청 수를 세는 데 쓴다. */
  clusterDetailsQueries?: string[]
  /** 결함 한 건짜리 조회(상세·이미지)가 들어올 때마다 경로를 담는다. */
  memberQueries?: string[]
}

async function stubMap(
  page: Page,
  features: ReturnType<typeof featuresAt>,
  options: StubMapOptions = {},
): Promise<void> {
  const {
    listContent = [],
    clusterDetailsStatus = 200,
    clusterDetailsQueries,
    memberQueries,
  } = options

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
        content: listContent,
        page: 0,
        size: 20,
        totalElements: listContent.length,
        totalPages: listContent.length === 0 ? 0 : 1,
      })
    }
    /*
     * 묶음 목록은 지도 GeoJSON에 없는 값(위치 이름·사진)을 **한 번에** 받는다. 예전에는
     * 결함마다 상세와 이미지 URL을 따로 불러 10건이면 20개가 한꺼번에 나갔고, 그중
     * 하나만 401이어도 로그인이 끊겼다.
     */
    if (pathname === '/api/v1/defects/cluster-details') {
      clusterDetailsQueries?.push(url.search)
      if (clusterDetailsStatus !== 200) {
        return fulfillJson(route, {
          status: clusterDetailsStatus,
          code: 'AUTH_UNAUTHENTICATED',
          message: '인증이 필요합니다.',
          requestId: '00000000-0000-4000-8000-000000000009',
          details: [],
        }, clusterDetailsStatus)
      }
      const requested = (url.searchParams.get('defectIds') ?? '')
        .split(',')
        .filter((value) => value.length > 0)
      return fulfillJson(route, {
        items: requested.map((defectId) => {
          // `defectAt(i)`가 만든 id의 끝 12자리가 i+1이다.
          const index = Number(defectId.slice(-12)) - 1
          const defect = defectAt(index)
          return {
            defectId,
            incidentCode: defect.incidentCode,
            locationLabel: defect.locationLabel,
            zoneId: defect.zoneId,
            // 첫 건에만 사진이 있다. 나머지는 아직 연결되지 않은 정상 상태다.
            imageUrl: index === 0 ? '/e2e-defect-photo.png' : null,
            imageUrlExpiresAt: index === 0 ? '2099-01-01T00:00:00Z' : null,
          }
        }),
      })
    }

    const detail = /^\/api\/v1\/defects\/([\w-]+)$/.exec(pathname)
    if (detail !== null) {
      const [, defectId] = detail
      memberQueries?.push(pathname)
      const index = Number(defectId.slice(-12)) - 1
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
    // 상세 카드의 사진은 여전히 단건 발급을 쓴다.
    const image = /^\/api\/v1\/defects\/([\w-]+)\/image-url$/.exec(pathname)
    if (image !== null) {
      const [, defectId] = image
      memberQueries?.push(pathname)
      const index = Number(defectId.slice(-12)) - 1
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
/** 8m 격자 — 시작 배율에서는 한 묶음이고, 확대하면 갈린다. */
function tightPoints(count: number): [number, number][] {
  return Array.from(
    { length: count },
    (_, index): [number, number] =>
      pointAt(Math.floor(index / 4) * 8, (index % 4) * 8),
  )
}

/** 한 묶음으로 모인 `count`건을 띄우고 그 묶음을 클릭한다. */
async function openCluster(
  page: Page,
  count: number,
  options: StubMapOptions = {},
): Promise<void> {
  await installFakeMap(page)
  await stubMap(page, featuresAt(tightPoints(count)), options)
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

/**
 * 카드 안 좌표를 재기 전에 **부드러운 스크롤이 끝나기를** 기다린다. 행을 누르면
 * 페이지가 지도까지 스크롤하는데, 그 도중에는 카드 헤더가 고정 상단 바 밑에 들어가
 * 마우스 조작이 상단 바로 간다.
 */
async function waitForCardSettled(page: Page): Promise<void> {
  await expect(page.locator('.defect-card')).toBeVisible()
  /*
   * **멈출 때까지** 기다린다. "상단 바 아래로 내려왔나"만 보면 아직 미끄러지는 중에
   * 통과해, 그 뒤에 재는 좌표가 어긋난다(끌기 시험이 그것 때문에 이따금 실패했다).
   * 두 번 연달아 같은 값이면 멈춘 것으로 본다.
   */
  let previous = Number.NaN
  await expect
    .poll(
      async () => {
        const now = await page.evaluate(() => {
          const header = document
            .querySelector('.defect-card-header')!
            .getBoundingClientRect()
          const topbar = document
            .querySelector('.topbar')!
            .getBoundingClientRect()
          return Math.round(header.top - topbar.bottom)
        })
        const settled = now > 0 && now === previous
        previous = now
        return settled
      },
      { timeout: 5000, intervals: [100, 100, 150, 200, 300, 500] },
    )
    .toBe(true)
}

/**
 * 지도 영역 안에서의 카드 위치. 뷰포트 좌표로 재면 페이지 스크롤이 섞여, 다른 결함을
 * 골랐을 때(다시 스크롤한다) 자리를 지켰는지 알 수 없다.
 */
function cardOffset(page: Page): Promise<{ x: number; y: number }> {
  return page.evaluate(() => {
    const card = document.querySelector('.defect-card')!.getBoundingClientRect()
    const wrap = document
      .querySelector('.defect-map-canvas-wrap')!
      .getBoundingClientRect()
    return { x: card.left - wrap.left, y: card.top - wrap.top }
  })
}

/** 목록 표 첫 행을 눌러 지도 위 상세 카드를 띄운다. */
async function openDetailCard(page: Page): Promise<void> {
  await installFakeMap(page)
  await stubMap(page, featuresAt(tightPoints(2)), {
    listContent: [defectAt(0), defectAt(1)],
  })
  await page.goto('/defects?allStatus=1')
  await expect(page.locator('.defect-table-body-row')).toHaveCount(2)
  await page.locator('.defect-table-body-row').first().click()
  await waitForCardSettled(page)
}

/** `panTo`로 옮겨간 마지막 자리. */
/**
 * 지도가 마지막으로 옮겨간 자리.
 *
 * <p>목록에서 고르면 그 마커가 묶이지 않을 배율까지 함께 확대한다. 이동과 확대는 한 번에
 * 해야 해서(따로 부르면 이동 애니메이션이 확대에 끊긴다) `panTo`가 아니라 `setCenter`로
 * 옮긴다. 그래서 `panned`가 아니라 `center`를 본다.
 *
 * <p>확대할 이웃 정보를 지도가 한 번 더 조회해서 얻으므로 클릭 직후에는 아직 옮겨지지
 * 않았을 수 있다. 부르는 쪽에서 기다려야 한다.
 */
function movedTo(page: Page): Promise<{ lat: number; lng: number } | null> {
  return page.evaluate(() => {
    const { center } = (window as unknown as {
      mapCalls: { center: { lat: number; lng: number }[] }
    }).mapCalls
    return center.at(-1) ?? null
  })
}

test.describe('묶음 결함 목록', () => {
  test('10건 이하를 누르면 코드·사진·위치가 담긴 목록이 열린다', async ({ page }) => {
    await openCluster(page, 3)

    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    await expect(list).toContainText('화면의 결함 3건')
    const items = list.getByRole('listitem')
    await expect(items).toHaveCount(3)

    /*
     * 줄 순서는 누른 자리에서 가까운 순이라 결함 번호와 일치하지 않는다. 코드로 집는다.
     */
    const item = (code: string) => items.filter({ hasText: code })
    // 위치 이름과 사진은 결함마다 따로 받는다.
    await expect(item('INC-20260805-000001'))
      .toContainText('역삼역 3번 출구 앞 보도')
    await expect(item('INC-20260805-000001').locator('img')).toBeVisible()
    // 이름이 없는 결함은 구역 코드로 떨어진다.
    await expect(item('INC-20260805-000003')).toContainText('ZONE-GN-0007')
    // 사진이 없는 결함은 자리표시가 아니라 "없음"으로 끝나야 한다.
    await expect(item('INC-20260805-000002').getByLabel('사진 없음')).toBeVisible()
    await expect(item('INC-20260805-000002').locator('img')).toHaveCount(0)
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
    // 줄 순서는 누른 자리에서 가까운 순이라 코드로 집는다.
    const chosen = list
      .getByRole('listitem')
      .filter({ hasText: 'INC-20260805-000002' })
    await chosen.getByRole('button').click()

    // 목록 표에서 고른 것과 같다 — 지도 위 상세 카드가 열린다.
    const card = page.locator('.defect-card')
    await expect(card).toBeVisible()
    await expect(card).toContainText('INC-20260805-000002')

    // 고른 줄은 표시가 남는다. 지도 마커의 선택 표시와 짝이다.
    await expect(chosen.locator('.defect-cluster-list-item'))
      .toHaveClass(/is-selected/)
    // 목록은 닫지 않는다. 형제 결함을 이어서 훑는 것이 이 목록의 쓸모다.
    await expect(list).toBeVisible()
  })

  test('누른 묶음뿐 아니라 확대된 화면에 보이는 결함이 다 들어간다', async ({ page }) => {
    /*
     * 같은 자리에 둘(=한 묶음) + 4m 떨어진 하나. 최대 배율에서 시작하므로 눌러도 더
     * 확대되지 않아 셋이 모두 화면에 남는다 — 목록에는 셋이 다 들어가야 한다.
     * 예전에는 누른 묶음의 구성원(둘)만 담아 마커 셋에 목록 두 줄이 됐다.
     */
    await installFakeMap(page, 21)
    await stubMap(page, featuresAt([pointAt(0, 0), pointAt(0, 0), pointAt(0, 4)]))
    await page.goto('/defects?allStatus=1')
    await expect(page.locator('.defect-map-canvas-wrap')).toBeVisible()
    // 같은 자리의 둘이 한 묶음, 4m 떨어진 하나가 낱개다.
    await expect
      .poll(() => page.evaluate(() =>
        (window as unknown as { mapCalls: { clickHandlers: unknown[] } })
          .mapCalls.clickHandlers.length))
      .toBe(2)
    await page.evaluate(() =>
      (window as unknown as { mapCalls: { clickHandlers: (() => void)[] } })
        .mapCalls.clickHandlers[0]?.())

    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    await expect(list).toContainText('화면의 결함 3건')
    await expect(list.getByRole('listitem')).toHaveCount(3)
    // 누른 자리에서 가까운 순이라 같은 자리의 둘이 앞에 온다.
    await expect(list.getByRole('listitem').nth(2))
      .toContainText('INC-20260805-000003')
  })

  test('목록과 상세 카드는 지도 모서리에 붙는다', async ({ page }) => {
    await openCluster(page, 3)
    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    await list.getByRole('listitem').first().getByRole('button').click()
    await expect(page.locator('.defect-card')).toBeVisible()

    /*
     * 16px씩 띄워두면 지도의 둥근 모서리와 상자의 둥근 모서리가 나란히 보여 두 겹으로
     * 겹친 것처럼 읽힌다. 붙이면 반지름이 같아 두 곡선이 포개진다.
     */
    const gaps = await page.evaluate(() => {
      const wrap = document
        .querySelector('.defect-map-canvas-wrap')!
        .getBoundingClientRect()
      const panel = document
        .querySelector('.defect-cluster-list')!
        .getBoundingClientRect()
      const card = document
        .querySelector('.defect-card')!
        .getBoundingClientRect()
      const radius = Number.parseFloat(
        getComputedStyle(document.querySelector('.defect-card')!)
          .borderTopRightRadius,
      )
      const mapRadius = Number.parseFloat(
        getComputedStyle(document.querySelector('.defect-map-canvas-wrap')!)
          .borderTopRightRadius,
      )
      return {
        panelTop: Math.round(panel.top - wrap.top),
        panelLeft: Math.round(panel.left - wrap.left),
        cardTop: Math.round(card.top - wrap.top),
        cardRight: Math.round(wrap.right - card.right),
        radius,
        mapRadius,
      }
    })

    expect(gaps.panelTop, '목록이 지도 위변에 붙는다').toBe(0)
    expect(gaps.panelLeft).toBe(0)
    expect(gaps.cardTop, '카드가 지도 위변에 붙는다').toBe(0)
    expect(gaps.cardRight).toBe(0)
    // 반지름이 같아야 두 곡선이 포개져 한 겹으로 보인다.
    expect(gaps.radius).toBe(gaps.mapRadius)
  })

  test('10건짜리 묶음도 구성원마다 조회하지 않는다', async ({ page }) => {
    const clusterDetailsQueries: string[] = []
    const memberQueries: string[] = []
    await openCluster(page, 10, { clusterDetailsQueries, memberQueries })

    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    await expect(list.getByRole('listitem')).toHaveCount(10)
    // 사진 자리표시가 다 걷힐 때까지 기다린다 — 조회가 끝난 시점이다.
    await expect(page.locator('.defect-cluster-list-photo-loading'))
      .toHaveCount(0)

    /*
     * 예전에는 결함마다 상세와 이미지 URL을 따로 불러 **20개**가 한꺼번에 나갔다.
     * 그 폭증이 401 한 건을 만날 확률을 그만큼 키웠다. 이제 구성원 단건 조회는 0이다.
     */
    expect(memberQueries, '구성원마다 따로 묻지 않는다').toEqual([])

    /*
     * 요청 수는 세지 않는다 — 개발 빌드의 StrictMode가 effect를 두 번 돌려 첫 요청은
     * 곧바로 취소된다. 중요한 것은 **구성원 수만큼 늘어나지 않는다**는 것이다.
     */
    expect(clusterDetailsQueries.length).toBeLessThanOrEqual(2)
    for (const query of clusterDetailsQueries) {
      const requested = new URLSearchParams(query).get('defectIds')?.split(',')
      expect(requested, '구성원 전부를 한 번에 묻는다').toHaveLength(10)
    }
  })

  test('목록 조회가 401이어도 로그인 화면으로 튕기지 않는다', async ({ page }) => {
    /*
     * 사용자가 겪은 증상. 지도 묶음을 누르면 로그인 창으로 튕겼다. 로그아웃 버튼을 누른
     * 적이 없는데도, 보조 조회 하나의 401이 전역 처리를 타고 인증 상태를 통째로 지웠다.
     *
     * <p>세션 조회(`/auth/session-state`)는 AUTHENTICATED로 답한다 — 세션은 멀쩡하고
     * 이 요청만 실패한 상황이다.
     */
    await openCluster(page, 3, { clusterDetailsStatus: 401 })

    // 목록은 열려 있고, 값을 못 받은 자리는 좌표·사진 없음으로 접힌다.
    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    await expect(list).toBeVisible()
    await expect(page.locator('.defect-cluster-list-photo-loading'))
      .toHaveCount(0)
    await expect(list.getByLabel('사진 없음')).toHaveCount(3)

    // 그리고 무엇보다 — 로그인 화면으로 가지 않는다.
    await expect(page).toHaveURL(/\/defects/)
    await expect(page.locator('.defect-map-canvas-wrap')).toBeVisible()
  })

  test('닫으면 목록만 사라지고 지도는 그대로다', async ({ page }) => {
    await openCluster(page, 3)

    await page.getByRole('button', { name: '묶인 결함 목록 닫기' }).click()
    await expect(page.locator('.defect-cluster-list')).toHaveCount(0)
    await expect(page.locator('.defect-map-canvas-wrap')).toBeVisible()
  })

  test('상세 카드가 목록보다 앞에 온다', async ({ page }) => {
    await openCluster(page, 3)
    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    await list.getByRole('listitem').first().getByRole('button').click()
    const card = page.locator('.defect-card')
    await expect(card).toBeVisible()

    /*
     * 카드를 목록 위로 끌어다 놓는다. 기본 자리(오른쪽)에서는 둘이 겹치지 않아 순서를
     * 볼 수 없다.
     */
    const header = page.locator('.defect-card-header')
    const listBox = (await list.boundingBox())!
    const headerBox = (await header.boundingBox())!
    await page.mouse.move(
      headerBox.x + headerBox.width / 2,
      headerBox.y + headerBox.height / 2,
    )
    await page.mouse.down()
    await page.mouse.move(
      listBox.x + listBox.width / 2,
      headerBox.y + headerBox.height / 2,
      { steps: 8 },
    )
    await page.mouse.up()

    const layers = await page.evaluate(() => {
      const card = document.querySelector('.defect-card')!
      const panel = document.querySelector('.defect-cluster-list')!
      const cardBox = card.getBoundingClientRect()
      const panelBox = panel.getBoundingClientRect()
      // 두 상자가 겹치는 자리를 잡아 그 지점에서 무엇이 위인지 본다.
      const x = (Math.max(cardBox.left, panelBox.left)
        + Math.min(cardBox.right, panelBox.right)) / 2
      const y = (Math.max(cardBox.top, panelBox.top)
        + Math.min(cardBox.bottom, panelBox.bottom)) / 2
      const top = document.elementFromPoint(x, y)
      return {
        overlaps:
          cardBox.left < panelBox.right && panelBox.left < cardBox.right,
        cardOnTop: top !== null && card.contains(top),
        cardZ: Number(getComputedStyle(card).zIndex),
        panelZ: Number(getComputedStyle(panel).zIndex),
      }
    })

    expect(layers.overlaps, '겹쳐야 순서를 볼 수 있다').toBe(true)
    expect(layers.cardOnTop, '겹친 자리에서 카드가 위여야 한다').toBe(true)
    // 층 번호로도 못박는다. 카드에 z-index가 없던 시절에는 목록(6)이 이겼다.
    expect(layers.cardZ).toBeGreaterThan(layers.panelZ)
  })
})

/**
 * 목록에서 결함을 고르면 그 마커가 지도 가운데로 온다. 카드만 떠도 마커가 화면 밖이면
 * 어디 일인지 알 수 없다.
 *
 * <p>`reducedMotion`을 고정한다 — 움직임을 줄이는 설정에서는 `panTo`가 아니라
 * `setCenter`로 떨어지도록 만들어 뒀기 때문이다.
 */
test.describe('고른 결함 가운데 정렬', () => {
  test.use({ reducedMotion: 'no-preference' })

  test('묶음 목록에서 고르면 그 마커가 가운데로 온다', async ({ page }) => {
    const points = tightPoints(3)
    await openCluster(page, 3)

    const list = page.getByRole('region', { name: '묶인 결함 목록' })
    await list.getByRole('listitem').nth(2).getByRole('button').click()

    const [longitude, latitude] = points[2]
    await expect.poll(() => movedTo(page)).not.toBeNull()
    const moved = await movedTo(page)
    expect(moved!.lat).toBeCloseTo(latitude, 6)
    expect(moved!.lng).toBeCloseTo(longitude, 6)
  })

  test('아래 목록 표에서 골라도 그 마커가 가운데로 온다', async ({ page }) => {
    const rows = [defectAt(0), defectAt(1), defectAt(2)]
    await installFakeMap(page)
    await stubMap(page, featuresAt(tightPoints(3)), { listContent: rows })
    await page.goto('/defects?allStatus=1')
    await expect(page.locator('.defect-table-body-row')).toHaveCount(3)

    await page.locator('.defect-table-body-row').nth(1).click()

    // 표 행의 좌표로 옮긴다. 지도 GeoJSON과 같은 결함의 같은 값이다.
    await expect.poll(() => movedTo(page)).not.toBeNull()
    const moved = await movedTo(page)
    expect(moved!.lat).toBeCloseTo(rows[1].location.latitude, 6)
    expect(moved!.lng).toBeCloseTo(rows[1].location.longitude, 6)
  })

  test('같은 행을 다시 눌러도 가운데로 되돌아온다', async ({ page }) => {
    const rows = [defectAt(0), defectAt(1)]
    await installFakeMap(page)
    await stubMap(page, featuresAt(tightPoints(2)), { listContent: rows })
    await page.goto('/defects?allStatus=1')
    await expect(page.locator('.defect-table-body-row')).toHaveCount(2)

    const row = page.locator('.defect-table-body-row').nth(0)
    await row.click()
    await row.click()

    /*
     * 선택이 그대로라 좌표만 보고 있으면 두 번째 클릭은 아무 일도 하지 않는다.
     * 요청마다 token을 올리는 이유가 이것이다.
     */
    await expect
      .poll(() => page.evaluate(() =>
        (window as unknown as { mapCalls: { center: unknown[] } })
          .mapCalls.center.length))
      .toBe(2)
  })
})

/**
 * 지도 위 상세 카드를 끌어 옮기기. 위치는 CSP 때문에 inline style이 아니라 CSS class로
 * 표현된다(`defectCardDrag.ts`). 그래서 "클래스가 붙었나"와 "실제로 움직였나"를 함께 본다.
 */
test.describe('상세 카드 끌어 옮기기', () => {
  /** 카드 헤더의 빈 곳(제목 옆)을 잡는다. 버튼에서 시작한 끌기는 무시되기 때문이다. */
  async function dragCardBy(
    page: Page,
    deltaX: number,
    deltaY: number,
  ): Promise<void> {
    const header = page.locator('.defect-card-header')
    const box = (await header.boundingBox())!
    const startX = box.x + box.width / 2
    const startY = box.y + box.height / 2
    await page.mouse.move(startX, startY)
    await page.mouse.down()
    // 여러 단계로 나눠야 pointermove가 실제로 여러 번 들어온다.
    await page.mouse.move(startX + deltaX, startY + deltaY, { steps: 8 })
    await page.mouse.up()
  }

  test('헤더를 끌면 카드가 그만큼 움직인다', async ({ page }) => {
    await openDetailCard(page)
    const before = await cardOffset(page)

    await dragCardBy(page, -300, 120)

    await expect(page.locator('.defect-card')).toHaveClass(/is-dragged/)
    const after = await cardOffset(page)
    // 1%p 간격으로 맞춰지므로 컨테이너의 1%(≈10px)까지는 어긋날 수 있다.
    expect(after.x - before.x).toBeGreaterThan(-315)
    expect(after.x - before.x).toBeLessThan(-285)
    expect(after.y - before.y).toBeGreaterThan(105)
    expect(after.y - before.y).toBeLessThan(135)
  })

  test('컨테이너 밖으로는 나가지 않는다', async ({ page }) => {
    await openDetailCard(page)

    // 지도 폭보다 훨씬 큰 값으로 끌어본다.
    await dragCardBy(page, -4000, 4000)

    const { cardBox, wrapBox } = await page.evaluate(() => ({
      cardBox: document.querySelector('.defect-card')!.getBoundingClientRect(),
      wrapBox: document
        .querySelector('.defect-map-canvas-wrap')!
        .getBoundingClientRect(),
    }))
    expect(cardBox.left, '왼쪽으로 잘려나가지 않는다')
      .toBeGreaterThanOrEqual(wrapBox.left)
    expect(cardBox.top).toBeGreaterThanOrEqual(wrapBox.top)
    expect(cardBox.bottom, '아래로 잘려나가지 않는다')
      .toBeLessThanOrEqual(wrapBox.bottom + 1)
  })

  test('끌어서 지도 꼭대기까지 올릴 수 있다', async ({ page }) => {
    await openDetailCard(page)

    // 아래로 내렸다가 다시 끝까지 올린다.
    await dragCardBy(page, 0, 200)
    await dragCardBy(page, -4000, -4000)

    const gaps = await page.evaluate(() => {
      const card = document.querySelector('.defect-card')!.getBoundingClientRect()
      const wrap = document
        .querySelector('.defect-map-canvas-wrap')!
        .getBoundingClientRect()
      return {
        top: Math.round(card.top - wrap.top),
        left: Math.round(card.left - wrap.left),
      }
    })

    /*
     * 기본 자리가 지도 모서리에 붙어 있으므로, 한 번 끌었다고 다시는 붙일 수 없게 되면
     * 안 된다. 예전에는 모서리마다 16px을 남겨 꼭대기까지 못 올라갔다.
     */
    expect(gaps.top, '지도 위변에 닿는다').toBe(0)
    expect(gaps.left, '왼쪽 변에도 닿는다').toBe(0)
  })

  test('닫기 버튼에서 시작한 끌기는 카드를 옮기지 않는다', async ({ page }) => {
    await openDetailCard(page)
    const card = page.locator('.defect-card')

    const close = card.getByRole('button', { name: '닫기' })
    const box = (await close.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x - 200, box.y + 100, { steps: 6 })

    await expect(card).not.toHaveClass(/is-dragged/)
    await page.mouse.up()
  })

  test('손잡이에 포커스를 두고 방향키로도 옮긴다', async ({ page }) => {
    await openDetailCard(page)
    const card = page.locator('.defect-card')
    const before = await cardOffset(page)

    await card.getByRole('button', { name: /카드 위치 옮기기/ }).focus()
    // 한 번에 2%p. 다섯 번이면 10%p다.
    for (let index = 0; index < 5; index += 1) {
      await page.keyboard.press('ArrowLeft')
    }

    await expect(card).toHaveClass(/is-dragged/)
    const after = await cardOffset(page)
    expect(after.x, '왼쪽으로 옮겨져야 한다').toBeLessThan(before.x - 30)
  })

  test('다른 결함을 골라도 옮겨둔 자리를 지킨다', async ({ page }) => {
    await openDetailCard(page)
    const card = page.locator('.defect-card')

    await dragCardBy(page, -260, 60)
    const moved = await cardOffset(page)

    // 카드는 결함마다 새로 마운트된다. 자리를 카드가 들고 있으면 여기서 튀어오른다.
    await page.locator('.defect-table-body-row').nth(1).click()
    await expect(card).toContainText('INC-20260805-000002')

    const after = await cardOffset(page)
    expect(Math.abs(after.x - moved.x)).toBeLessThan(2)
    expect(Math.abs(after.y - moved.y)).toBeLessThan(2)
  })
})
