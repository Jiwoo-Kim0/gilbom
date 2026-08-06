import { type Page } from '@playwright/test'

/**
 * 지도 SDK와 SSE의 가짜다. 지도 동작 e2e가 함께 쓴다.
 *
 * <p>실제 `naver.maps`는 스크립트를 외부에서 받아 와야 하고 확대·이동이 비동기라, 어느
 * 배율로 옮겼는지·왜 옮겼는지를 시험에서 붙잡을 수 없다. 대신 **확대 한 단계 = 화면 거리
 * 두 배**라는 성질만 지키는 가짜를 심고, `setZoom`·`setCenter`에 무엇이 들어오는지 본다.
 *
 * <p>`fromCoordToOffset`은 일부러 화면 픽셀의 {@link FakeMapOptions.unitScale}배를
 * 돌려준다. 계산이 SDK가 주는 값의 단위에 기대고 있으면 여기서 드러난다.
 */

export interface FakeMapOptions {
  /** 지도를 만들 때의 배율. */
  startZoom: number
  /** projection이 화면 픽셀의 몇 배를 돌려주게 할지. 기본 3배 — 단위 어긋남을 흉내낸다. */
  unitScale?: number
  baseLat: number
  baseLng: number
}

/** 가짜 지도가 기록한 호출. `readMapCalls`로 읽는다. */
export interface MapCalls {
  zoom: number[]
  center: { lat: number; lng: number }[]
  clickHandlers: number
}

export async function installFakeMap(
  page: Page,
  options: FakeMapOptions,
): Promise<void> {
  await page.addInitScript(
    ({ startZoom, unitScale, baseLat, baseLng }) => {
      const scale = unitScale ?? 3
      const calls: {
        zoom: number[]
        center: { lat: number; lng: number }[]
        clickHandlers: (() => void)[]
        sseListeners: Map<string, ((event: MessageEvent<string>) => void)[]>
      } = { zoom: [], center: [], clickHandlers: [], sseListeners: new Map() }
      ;(window as unknown as { mapCalls: typeof calls }).mapCalls = calls

      /**
       * 실시간 스트림 가짜. 스텁 서버에는 SSE 종단이 없다. 시험이 원하는 순간에
       * `window.pushSseEvent(...)`로 이벤트를 밀어 넣는다.
       */
      class ControllableEventSource {
        static readonly CONNECTING = 0
        static readonly OPEN = 1
        static readonly CLOSED = 2
        readonly readyState = ControllableEventSource.OPEN
        onopen: (() => void) | null = null
        onerror: (() => void) | null = null
        onmessage: (() => void) | null = null
        addEventListener(
          type: string,
          handler: (event: MessageEvent<string>) => void,
        ): void {
          const handlers = calls.sseListeners.get(type) ?? []
          handlers.push(handler)
          calls.sseListeners.set(type, handlers)
        }
        removeEventListener(): void {}
        close(): void {}
      }
      Object.defineProperty(window, 'EventSource', {
        writable: true,
        value: ControllableEventSource,
      })
      Object.defineProperty(window, 'pushSseEvent', {
        writable: true,
        value: (type: string, payload: unknown) => {
          const event = new MessageEvent(type, {
            data: JSON.stringify(payload),
          }) as MessageEvent<string>
          for (const handler of calls.sseListeners.get(type) ?? []) {
            handler(event)
          }
        },
      })

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
          fromCoordToOffset: (latLng: LatLng) => ({
            x: (latLng.lng() * metersPerDegreeLng * scale)
              / metersPerPixelAt(zoom),
            y: (latLng.lat() * 111_320 * scale) / metersPerPixelAt(zoom),
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
        // panTo가 없으면 centerMapOn이 setCenter로 떨어진다. 그쪽만 기록하면 된다.
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
      startZoom: options.startZoom,
      unitScale: options.unitScale,
      baseLat: options.baseLat,
      baseLng: options.baseLng,
    },
  )
}

/** 가짜 지도가 기록한 호출을 읽는다. */
export function readMapCalls(page: Page): Promise<MapCalls> {
  return page.evaluate(() => {
    const calls = (window as unknown as {
      mapCalls: {
        zoom: number[]
        center: { lat: number; lng: number }[]
        clickHandlers: unknown[]
      }
    }).mapCalls
    return {
      zoom: calls.zoom,
      center: calls.center,
      clickHandlers: calls.clickHandlers.length,
    }
  })
}

/** 실시간 이벤트를 밀어 넣는다. 서버 `DefectSseEventResponse` 모양이어야 한다. */
export function pushSseEvent(
  page: Page,
  type: string,
  payload: Record<string, unknown>,
): Promise<void> {
  return page.evaluate(
    ({ type, payload }) => {
      (window as unknown as {
        pushSseEvent: (type: string, payload: unknown) => void
      }).pushSseEvent(type, payload)
    },
    { type, payload },
  )
}
