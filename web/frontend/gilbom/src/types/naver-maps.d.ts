declare namespace naver.maps {
  class LatLng {
    constructor(lat: number, lng: number)
    lat(): number
    lng(): number
  }

  class LatLngBounds {
    constructor(southWest: LatLng, northEast: LatLng)
    getSW(): LatLng
    getNE(): LatLng
  }

  class Point {
    constructor(x: number, y: number)
    readonly x: number
    readonly y: number
  }

  class Size {
    constructor(width: number, height: number)
  }

  enum Position {
    CENTER,
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    LEFT_TOP,
    LEFT_CENTER,
    LEFT_BOTTOM,
    RIGHT_TOP,
    RIGHT_CENTER,
    RIGHT_BOTTOM,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
  }

  enum ZoomControlStyle {
    LARGE,
    SMALL,
  }

  interface ZoomControlOptions {
    position?: Position
    style?: ZoomControlStyle
  }

  interface ScaleControlOptions {
    position?: Position
  }

  interface MapOptions {
    center: LatLng
    zoom?: number
    minZoom?: number
    maxZoom?: number
    draggable?: boolean
    pinchZoom?: boolean
    scrollWheel?: boolean
    keyboardShortcuts?: boolean
    disableDoubleClickZoom?: boolean
    disableDoubleTapZoom?: boolean
    disableTwoFingerTapZoom?: boolean
    zoomControl?: boolean
    zoomControlOptions?: ZoomControlOptions
    scaleControl?: boolean
    scaleControlOptions?: ScaleControlOptions
  }

  interface MapSystemProjection {
    fromCoordToOffset(coord: LatLng): Point
  }

  interface MapPanes {
    overlayLayer: HTMLElement
  }

  class Map {
    constructor(element: HTMLElement, options: MapOptions)
    getProjection(): MapSystemProjection
    getBounds(): LatLngBounds
    getZoom(): number
    setCenter(latlng: LatLng): void
    /**
     * 중심을 부드럽게 옮긴다. SDK 버전에 따라 없을 수 있어 호출부에서
     * `typeof map.panTo === 'function'`으로 확인하고 `setCenter`로 떨어진다.
     */
    panTo?(latlng: LatLng): void
    setZoom(zoom: number): void
    fitBounds(bounds: LatLngBounds): void
    destroy(): void
  }

  class OverlayView {
    setMap(map: Map | null): void
    getMap(): Map | null
    getPanes(): MapPanes
    getProjection(): MapSystemProjection
    onAdd(): void
    draw(): void
    onRemove(): void
  }

  interface MapPointerEvent {
    coord: LatLng
    offset?: Point
  }

  interface MapEventListener {
    eventName?: string
  }

  namespace Event {
    function addListener(
      target: Map,
      eventName: 'click',
      listener: (event: MapPointerEvent) => void,
    ): MapEventListener

    function addListener(
      target: Map,
      eventName:
        | 'bounds_changed'
        | 'size_changed'
        | 'idle'
        | 'init'
        | 'drag'
        | 'dragend'
        | 'panning'
        | 'zooming'
        | 'zoom_changed',
      listener: () => void,
    ): MapEventListener

    function addListener(
      target: Marker,
      eventName: 'click',
      listener: (event: MapPointerEvent) => void,
    ): MapEventListener

    function removeListener(listener: MapEventListener): void
  }

  interface PolygonOptions {
    map?: Map
    paths: LatLng[] | LatLng[][]
    fillColor?: string
    fillOpacity?: number
    strokeColor?: string
    strokeOpacity?: number
    strokeWeight?: number
    clickable?: boolean
    zIndex?: number
  }

  class Polygon {
    constructor(options: PolygonOptions)
    setMap(map: Map | null): void
  }

  interface PolylineOptions {
    map?: Map
    path: LatLng[]
    strokeColor?: string
    strokeOpacity?: number
    strokeWeight?: number
    strokeStyle?: string
    clickable?: boolean
    zIndex?: number
  }

  class Polyline {
    constructor(options: PolylineOptions)
    setMap(map: Map | null): void
    setPath(path: LatLng[]): void
  }

  interface MarkerIcon {
    content?: string
    url?: string
    size?: Size
    anchor?: Point
  }

  interface MarkerOptions {
    position: LatLng
    map?: Map
    icon?: MarkerIcon | string
    title?: string
    zIndex?: number
  }

  class Marker {
    constructor(options: MarkerOptions)
    setMap(map: Map | null): void
    setPosition(latlng: LatLng): void
    setIcon(icon: MarkerIcon | string): void
    setTitle(title: string): void
    getPosition(): LatLng
  }
}

interface Window {
  naver?: typeof naver
}
