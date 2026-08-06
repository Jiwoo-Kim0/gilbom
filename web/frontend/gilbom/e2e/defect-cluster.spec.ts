import { expect, test } from '@playwright/test'
import { defectAt, fulfillJson, USER_ID } from './support/defectPage'

/**
 * 묶음 13개를 한 번 클릭했을 때 실제로 갈리는지 본다.
 *
 * <p>가짜 SDK의 `fromCoordToOffset`은 **화면 픽셀의 3배**를 돌려준다. 예전 구현은 그 값을
 * 화면 픽셀이라고 믿고 확대 단계를 계산해서, 단위가 어긋나면 덜 확대하고 같은 숫자가
 * 다시 나왔다. 지금 구현은 지도에서 잰 배율(m/px)로 계산하므로 이 단위에 영향받지 않는다.
 */
const MEMBER_COUNT = 13
/** 격자 한 변 8m 간격의 덩어리. 가장 가까운 쌍은 축 방향으로 정확히 8m다. */
const SPACING_M = 8
const BASE_LAT = 37.50123
const BASE_LNG = 127.04321
const START_ZOOM = 14
/** 화면 픽셀과 다른 단위를 흉내낸다. */
const UNIT_SCALE = 3

function memberCoordinates(): [number, number][] {
  const latPerMeter = 1 / 111_320
  const lngPerMeter = 1 / (111_320 * Math.cos((BASE_LAT * Math.PI) / 180))
  return Array.from({ length: MEMBER_COUNT }, (_, index) => [
    BASE_LNG + (index % 4) * SPACING_M * lngPerMeter,
    BASE_LAT + Math.floor(index / 4) * SPACING_M * latPerMeter,
  ])
}

test('13개가 묶인 마커를 한 번 클릭하면 갈릴 만큼 확대한다', async ({ page }) => {
  const coordinates = memberCoordinates()
  const features = coordinates.map((point, index) => ({
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

  await page.addInitScript(
    ({ startZoom, unitScale, baseLat }) => {
      Object.defineProperty(window, 'EventSource', {
        writable: true,
        value: class {
          close() {}
          addEventListener() {}
          removeEventListener() {}
        },
      })
      const calls: { zoom: number[]; clickHandlers: (() => void)[] } = {
        zoom: [],
        clickHandlers: [],
      }
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
      const metersPerDegreeLng = 111_320 * Math.cos((baseLat * Math.PI) / 180)
      /** 실제 지도와 같은 성질: 한 단계 확대하면 1px이 덮는 거리가 절반이 된다. */
      const metersPerPixelAt = (z: number) =>
        (156_543.033_92 * Math.cos((baseLat * Math.PI) / 180)) / 2 ** z
      const mapWidth = () =>
        document.querySelector<HTMLElement>('.defect-map-canvas')?.clientWidth
        ?? 800

      const fakeMap = {
        // 지금 배율에 맞는 범위를 돌려준다. 화면 폭으로 나누면 1px이 몇 m인지 나온다.
        getBounds: () => {
          const halfSpanDegrees =
            (metersPerPixelAt(zoom) * mapWidth()) / 2 / metersPerDegreeLng
          return new LatLngBounds(
            new LatLng(baseLat - 0.01, 127.04321 - halfSpanDegrees),
            new LatLng(baseLat + 0.01, 127.04321 + halfSpanDegrees),
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
        setCenter: () => {},
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
              getPosition() { return new LatLng(baseLat, 127.04321) }
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
    { startZoom: START_ZOOM, unitScale: UNIT_SCALE, baseLat: BASE_LAT },
  )

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
    return fulfillJson(route, {}, 404)
  })

  await page.goto('/defects?allStatus=1')
  await expect(page.locator('.defect-map-canvas-wrap')).toBeVisible()
  await expect
    .poll(() => page.evaluate(() =>
      (window as unknown as { mapCalls: { clickHandlers: unknown[] } })
        .mapCalls.clickHandlers.length))
    .toBeGreaterThan(0)

  // 묶음 마커가 하나만 만들어졌는지 확인한다 — 13개가 실제로 한 덩어리다.
  const handlerCount = await page.evaluate(() =>
    (window as unknown as { mapCalls: { clickHandlers: unknown[] } })
      .mapCalls.clickHandlers.length)
  expect(handlerCount, '13건이 한 묶음이어야 시험이 성립한다').toBe(1)

  const clicked = await page.evaluate(() => {
    const calls = (window as unknown as {
      mapCalls: { zoom: number[]; clickHandlers: (() => void)[] }
    }).mapCalls
    calls.clickHandlers[0]?.()
    return calls.zoom
  })
  expect(clicked, '클릭 한 번에 확대는 한 번만 일어난다').toHaveLength(1)
  const nextZoom = clicked[0]
  expect(nextZoom).toBeLessThanOrEqual(21)

  /*
   * 진짜 확인할 것: 그 배율에서 같은 13건을 다시 묶으면 몇 덩어리가 되는가.
   * 화면 픽셀(단위 왜곡 없이)로 44px 격자에 다시 넣어 센다.
   */
  const cells = await page.evaluate(
    ({ coordinates, zoomLevel, baseLat }) => {
      const metersPerPixel =
        (156_543.033_92 * Math.cos((baseLat * Math.PI) / 180)) / 2 ** zoomLevel
      const metersPerDegreeLng = 111_320 * Math.cos((baseLat * Math.PI) / 180)
      const keys = new Set(coordinates.map(([lng, lat]) => {
        const x = (lng * metersPerDegreeLng) / metersPerPixel
        const y = (lat * 111_320) / metersPerPixel
        return `${Math.floor(x / 44)}:${Math.floor(y / 44)}`
      }))
      return keys.size
    },
    { coordinates, zoomLevel: nextZoom, baseLat: BASE_LAT },
  )
  console.log(`확대: ${START_ZOOM} → ${nextZoom}, 확대 뒤 덩어리 수: ${cells}`)
  /*
   * 13건이 8m 간격의 고른 격자라, **가장 가까운 쌍이 갈리면 전부 갈린다.** 그래서 13이
   * 기대값이다. 덜 확대하면 3~4건씩 뭉친 덩어리가 남는다 — 예전 구현이 그랬다(4덩어리).
   */
  expect(cells, '한 번의 클릭으로 묶음이 전부 갈려야 한다').toBe(MEMBER_COUNT)
})
