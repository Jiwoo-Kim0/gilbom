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
  /** 지금 지도에 붙어 있는 마커 좌표. */
  markers: { lat: number; lng: number }[]
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
        /** 지금 지도에 붙어 있는 마커 좌표. `setMap(null)`이면 빠진다. */
        markers: { lat: number; lng: number; live: boolean }[]
        mapListeners: Map<string, (() => void)[]>
        sseListeners: Map<string, ((event: MessageEvent<string>) => void)[]>
      } = {
        zoom: [],
        center: [],
        clickHandlers: [],
        markers: [],
        mapListeners: new Map(),
        sseListeners: new Map(),
      }
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
      /** `panTo` 애니메이션이 끝나는 데 걸리는 시간. 실제 SDK도 이 정도다. */
      const PAN_DURATION_MS = 300
      let panTimer: number | undefined
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
          /*
           * 실제 SDK와 같이, 진행 중인 이동 애니메이션은 확대에 끊긴다. 이동과 확대를
           * 따로 부르면 옮겨가지 못한 자리에서 확대되는 것을 이 가짜가 재현한다.
           */
          if (panTimer !== undefined) {
            window.clearTimeout(panTimer)
            panTimer = undefined
          }
          calls.zoom.push(next)
          zoom = next
          settle()
        },
        /** 애니메이션으로 옮긴다. 실제 SDK처럼 곧바로 끝나지 않는다. */
        panTo: (next: LatLng) => {
          panTimer = window.setTimeout(() => {
            panTimer = undefined
            center = { lat: next.lat(), lng: next.lng() }
            calls.center.push(center)
            settle()
          }, PAN_DURATION_MS)
        },
        setCenter: (next: LatLng) => {
          center = { lat: next.lat(), lng: next.lng() }
          calls.center.push(center)
          settle()
        },
        /** 이동과 확대를 한 번에. 실제 SDK의 `morph`와 같은 자리다. */
        morph: (next: LatLng, nextZoom?: number) => {
          center = { lat: next.lat(), lng: next.lng() }
          calls.center.push(center)
          if (nextZoom !== undefined) {
            calls.zoom.push(nextZoom)
            zoom = nextZoom
          }
          settle()
        },
        // panTo가 없으면 centerMapOn이 setCenter로 떨어진다. 그쪽만 기록하면 된다.
        fitBounds: () => {},
      }

      /**
       * 시야가 바뀌면 실제 지도처럼 `bounds_changed` → `idle`을 쏜다.
       *
       * <p>이게 없으면 앱이 **뷰포트 bbox로 다시 조회하지 않는다.** 옮겨간 자리에 마커가
       * 실제로 올라오는지를 시험하려면 이 신호가 있어야 한다.
       */
      function settle(): void {
        for (const handler of calls.mapListeners.get('bounds_changed') ?? []) {
          handler()
        }
        for (const handler of calls.mapListeners.get('idle') ?? []) {
          handler()
        }
      }

      Object.defineProperty(window, 'naver', {
        writable: true,
        value: {
          maps: {
            Map: class { constructor() { return fakeMap } },
            Marker: class {
              private readonly slot: number
              constructor(options?: { position?: LatLng }) {
                const position = options?.position
                calls.markers.push({
                  lat: position?.lat() ?? baseLat,
                  lng: position?.lng() ?? baseLng,
                  live: true,
                })
                this.slot = calls.markers.length - 1
              }
              setMap(map: unknown) {
                // 마커를 다시 만들 때 옛것을 떼는 호출이다. 살아 있는 것만 세려고 본다.
                calls.markers[this.slot].live = map !== null
              }
              setIcon() {}
              setZIndex() {}
              getPosition() {
                const marker = calls.markers[this.slot]
                return new LatLng(marker.lat, marker.lng)
              }
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
                // 마커 클릭 핸들러와 지도 이벤트를 갈라 담는다.
                if (target === fakeMap) {
                  const handlers = calls.mapListeners.get(type) ?? []
                  handlers.push(handler)
                  calls.mapListeners.set(type, handlers)
                  return {}
                }
                if (type === 'click') {
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
        markers: { lat: number; lng: number; live: boolean }[]
      }
    }).mapCalls
    return {
      zoom: calls.zoom,
      center: calls.center,
      clickHandlers: calls.clickHandlers.length,
      markers: calls.markers
        .filter((marker) => marker.live)
        .map(({ lat, lng }) => ({ lat, lng })),
    }
  })
}

/** 가짜 지도가 지금 보고 있는 범위와 배율. */
export interface MapView {
  south: number
  west: number
  north: number
  east: number
  zoom: number
}

/** 지금 화면에 무엇이 보이는지 확인할 때 쓴다. */
export function readMapView(page: Page): Promise<MapView> {
  return page.evaluate(() => {
    const map = (window as unknown as {
      naver: { maps: { Map: new () => {
        getBounds: () => {
          getSW: () => { lat: () => number; lng: () => number }
          getNE: () => { lat: () => number; lng: () => number }
        }
        getZoom: () => number
      } } }
    }).naver.maps.Map
    // 가짜는 언제나 같은 지도 하나를 돌려준다.
    const instance = new map()
    const bounds = instance.getBounds()
    return {
      south: bounds.getSW().lat(),
      west: bounds.getSW().lng(),
      north: bounds.getNE().lat(),
      east: bounds.getNE().lng(),
      zoom: instance.getZoom(),
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
