declare namespace naver.maps {
  class LatLng {
    constructor(lat: number, lng: number)
    lat(): number
    lng(): number
  }

  class Point {
    constructor(x: number, y: number)
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

  interface ZoomControlOptions {
    position?: Position
  }

  interface MapOptions {
    center: LatLng
    zoom?: number
    minZoom?: number
    maxZoom?: number
    zoomControl?: boolean
    zoomControlOptions?: ZoomControlOptions
  }

  class Map {
    constructor(element: HTMLElement, options: MapOptions)
    setCenter(latlng: LatLng): void
    setZoom(zoom: number): void
    destroy(): void
  }

  interface MarkerIcon {
    content: string
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
    getPosition(): LatLng
  }
}

interface Window {
  naver?: typeof naver
}
