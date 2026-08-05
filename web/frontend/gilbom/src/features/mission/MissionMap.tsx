import {
  MapPin,
  Navigation,
  Radio,
  RefreshCw,
  Route,
  TriangleAlert,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import carMarkerImage from '../../assets/car.png'
import { loadNaverMapsScript } from '../../lib/naverMaps'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { useRobotRealtime } from '../robot/useRobotRealtime'
import { getMissionMap } from './missionApi'
import type {
  MissionMapCurrentPosition,
  MissionMapResponse,
  MissionTrajectoryPoint,
  PatrolAreaBoundary,
} from './missionTypes'
import './mission-map.css'

type Coordinate = [longitude: number, latitude: number]
type MissionMapOverlay = naver.maps.OverlayView & {
  setSnapshot(snapshot: MissionMapResponse): void
}

const NAVER_MAP_CLIENT_ID = import.meta.env.VITE_NAVER_MAP_CLIENT_ID
const SNAPSHOT_DEBOUNCE_MILLIS = 400
const FALLBACK_WIDTH = 800
const FALLBACK_HEIGHT = 360
const FALLBACK_PADDING = 30
const DEFAULT_MAP_CENTER: Coordinate = [128.3446, 36.1195]

const streamStatusLabel = {
  connecting: '연결 중',
  connected: '실시간 연결',
  reconnecting: '재연결 중',
} as const

function isCoordinate(value: number[]): value is Coordinate {
  return value.length >= 2
    && Number.isFinite(value[0])
    && Number.isFinite(value[1])
    && value[0] >= -180
    && value[0] <= 180
    && value[1] >= -90
    && value[1] <= 90
}

function areaPolygons(boundary: PatrolAreaBoundary): number[][][][] {
  return boundary.type === 'Polygon'
    ? [boundary.coordinates]
    : boundary.coordinates
}

function boundaryCoordinates(boundary: PatrolAreaBoundary): Coordinate[] {
  return areaPolygons(boundary)
    .flatMap((polygon) => polygon)
    .flatMap((ring) => ring)
    .filter(isCoordinate)
}

function trajectoryCoordinates(
  trajectory: MissionTrajectoryPoint[],
): Coordinate[] {
  return trajectory
    .map((point) => [point.longitude, point.latitude])
    .filter(isCoordinate)
}

function missionViewportCoordinates(snapshot: MissionMapResponse): Coordinate[] {
  const patrolAreaCoordinates = boundaryCoordinates(
    snapshot.patrolAreaBoundary,
  )
  if (patrolAreaCoordinates.length > 0) {
    return patrolAreaCoordinates
  }
  return [
    ...trajectoryCoordinates(snapshot.trajectory),
    ...(snapshot.currentPosition === null
      ? []
      : [[
        snapshot.currentPosition.longitude,
        snapshot.currentPosition.latitude,
      ] as Coordinate]),
  ]
}

function coordinateCenter(coordinates: Coordinate[]): Coordinate {
  if (coordinates.length === 0) {
    return DEFAULT_MAP_CENTER
  }
  const longitudes = coordinates.map(([longitude]) => longitude)
  const latitudes = coordinates.map(([, latitude]) => latitude)
  return [
    (Math.min(...longitudes) + Math.max(...longitudes)) / 2,
    (Math.min(...latitudes) + Math.max(...latitudes)) / 2,
  ]
}

function fitMapToCoordinates(
  map: naver.maps.Map,
  maps: typeof naver.maps,
  coordinates: Coordinate[],
) {
  if (coordinates.length === 1) {
    const [longitude, latitude] = coordinates[0]
    map.setCenter(new maps.LatLng(latitude, longitude))
    map.setZoom(17)
    return
  }
  if (coordinates.length > 1) {
    const longitudes = coordinates.map(([longitude]) => longitude)
    const latitudes = coordinates.map(([, latitude]) => latitude)
    map.fitBounds(new maps.LatLngBounds(
      new maps.LatLng(
        Math.min(...latitudes),
        Math.min(...longitudes),
      ),
      new maps.LatLng(
        Math.max(...latitudes),
        Math.max(...longitudes),
      ),
    ))
  }
}

function pointOnSegment(
  point: Coordinate,
  start: Coordinate,
  end: Coordinate,
): boolean {
  const [x, y] = point
  const [x1, y1] = start
  const [x2, y2] = end
  const cross = (x - x1) * (y2 - y1) - (y - y1) * (x2 - x1)
  if (Math.abs(cross) > 1e-10) {
    return false
  }
  return x >= Math.min(x1, x2) - 1e-10
    && x <= Math.max(x1, x2) + 1e-10
    && y >= Math.min(y1, y2) - 1e-10
    && y <= Math.max(y1, y2) + 1e-10
}

function pointInRing(point: Coordinate, rawRing: number[][]): boolean {
  const ring = rawRing.filter(isCoordinate)
  if (ring.length < 3) {
    return false
  }
  let inside = false
  for (let current = 0, previous = ring.length - 1;
    current < ring.length;
    previous = current, current += 1) {
    const start = ring[previous]
    const end = ring[current]
    if (pointOnSegment(point, start, end)) {
      return true
    }
    const intersects = (
      (start[1] > point[1]) !== (end[1] > point[1])
      && point[0] < (
        (end[0] - start[0])
        * (point[1] - start[1])
        / (end[1] - start[1])
        + start[0]
      )
    )
    if (intersects) {
      inside = !inside
    }
  }
  return inside
}

function isInsideBoundary(
  position: MissionMapCurrentPosition,
  boundary: PatrolAreaBoundary,
): boolean | null {
  const point: Coordinate = [position.longitude, position.latitude]
  const polygons = areaPolygons(boundary)
  if (polygons.length === 0) {
    return null
  }
  return polygons.some((polygon) => {
    const [outer, ...holes] = polygon
    if (outer === undefined || !pointInRing(point, outer)) {
      return false
    }
    return !holes.some((hole) => pointInRing(point, hole))
  })
}

function formatObservedAt(value: string | null): string {
  if (value === null) {
    return '기준 시각 미수신'
  }
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

function projectCoordinate(
  projection: naver.maps.MapSystemProjection,
  [longitude, latitude]: Coordinate,
) {
  const offset = projection.fromCoordToOffset(
    new naver.maps.LatLng(latitude, longitude),
  )
  return {
    x: Number(offset.x.toFixed(2)),
    y: Number(offset.y.toFixed(2)),
  }
}

function projectedPolygonPath(
  projection: naver.maps.MapSystemProjection,
  polygon: number[][][],
): string {
  return polygon
    .map((ring) => ring
      .filter(isCoordinate)
      .map((coordinate, pointIndex) => {
        const { x, y } = projectCoordinate(projection, coordinate)
        return `${pointIndex === 0 ? 'M' : 'L'} ${x} ${y}`
      })
      .concat('Z')
      .join(' '))
    .join(' ')
}

function createMissionMapOverlay(
  maps: typeof naver.maps,
  mapElement: HTMLElement,
  initialSnapshot: MissionMapResponse,
): MissionMapOverlay {
  class MapAttachedMissionOverlay extends maps.OverlayView {
    private snapshot = initialSnapshot
    private readonly element = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'svg',
    )
    private areaPaths: SVGPathElement[] = []
    private trajectoryLine: SVGPolylineElement | null = null
    private markerGroup: SVGGElement | null = null
    private markerDirection: SVGImageElement | null = null

    constructor() {
      super()
      this.element.classList.add('mission-map-native-overlay')
      this.element.setAttribute('overflow', 'visible')
      this.element.setAttribute('aria-hidden', 'true')
      this.element.setAttribute('data-testid', 'mission-map-native-overlay')
      this.syncElements()
    }

    setSnapshot(nextSnapshot: MissionMapResponse) {
      this.snapshot = nextSnapshot
      this.syncElements()
      if (this.getMap() !== null) {
        this.draw()
      }
    }

    onAdd() {
      this.getPanes().overlayLayer.appendChild(this.element)
      this.draw()
    }

    draw() {
      if (this.getMap() === null) {
        return
      }
      const projection = this.getProjection()
      const width = mapElement.clientWidth
      const height = mapElement.clientHeight
      this.element.setAttribute('width', String(width))
      this.element.setAttribute('height', String(height))
      this.element.setAttribute('viewBox', `0 0 ${width} ${height}`)

      areaPolygons(this.snapshot.patrolAreaBoundary)
        .forEach((polygon, index) => {
          this.areaPaths[index]?.setAttribute(
            'd',
            projectedPolygonPath(projection, polygon),
          )
        })

      const track = trajectoryCoordinates(this.snapshot.trajectory)
      if (this.trajectoryLine !== null) {
        this.trajectoryLine.setAttribute(
          'points',
          track
            .map((coordinate) => {
              const { x, y } = projectCoordinate(projection, coordinate)
              return `${x},${y}`
            })
            .join(' '),
        )
        this.trajectoryLine.setAttribute(
          'data-point-count',
          String(track.length),
        )
      }

      const current = this.snapshot.currentPosition
      if (current !== null && this.markerGroup !== null) {
        const { x, y } = projectCoordinate(
          projection,
          [current.longitude, current.latitude],
        )
        this.markerGroup.setAttribute('transform', `translate(${x} ${y})`)
        this.markerGroup.classList.toggle(
          'is-offline',
          current.connectivityStatus !== 'ONLINE',
        )
        this.markerGroup.setAttribute('data-latitude', String(current.latitude))
        this.markerGroup.setAttribute(
          'data-longitude',
          String(current.longitude),
        )
        this.markerGroup.setAttribute(
          'data-heading',
          String(current.headingDeg ?? ''),
        )
        this.markerDirection?.setAttribute(
          'transform',
          `rotate(${current.headingDeg ?? 0})`,
        )
      }
    }

    onRemove() {
      this.element.remove()
    }

    private syncElements() {
      const polygonCount = areaPolygons(
        this.snapshot.patrolAreaBoundary,
      ).length
      while (this.areaPaths.length < polygonCount) {
        const path = document.createElementNS(
          'http://www.w3.org/2000/svg',
          'path',
        )
        path.classList.add('mission-map-area-shape')
        path.setAttribute('fill-rule', 'evenodd')
        path.setAttribute('data-testid', 'mission-area-shape')
        this.areaPaths.push(path)
      }
      this.areaPaths.splice(polygonCount).forEach((path) => path.remove())

      const trackVisible = trajectoryCoordinates(
        this.snapshot.trajectory,
      ).length >= 2
      if (trackVisible && this.trajectoryLine === null) {
        this.trajectoryLine = document.createElementNS(
          'http://www.w3.org/2000/svg',
          'polyline',
        )
        this.trajectoryLine.classList.add('mission-map-trajectory-line')
        this.trajectoryLine.setAttribute('fill', 'none')
        this.trajectoryLine.setAttribute('data-testid', 'mission-trajectory')
      } else if (!trackVisible) {
        this.trajectoryLine?.remove()
        this.trajectoryLine = null
      }

      if (this.snapshot.currentPosition !== null && this.markerGroup === null) {
        this.markerGroup = document.createElementNS(
          'http://www.w3.org/2000/svg',
          'g',
        )
        this.markerGroup.classList.add('mission-map-current')
        this.markerGroup.setAttribute(
          'data-testid',
          'mission-current-position',
        )
        this.markerDirection = document.createElementNS(
          'http://www.w3.org/2000/svg',
          'image',
        )
        this.markerDirection.classList.add('mission-map-current-car')
        this.markerDirection.setAttribute('data-testid', 'mission-current-car')
        this.markerDirection.setAttribute('href', carMarkerImage)
        this.markerDirection.setAttribute('x', '-16')
        this.markerDirection.setAttribute('y', '-23')
        this.markerDirection.setAttribute('width', '32')
        this.markerDirection.setAttribute('height', '46')
        this.markerDirection.setAttribute('preserveAspectRatio', 'xMidYMid meet')
        this.markerGroup.append(this.markerDirection)
      } else if (this.snapshot.currentPosition === null) {
        this.markerGroup?.remove()
        this.markerGroup = null
        this.markerDirection = null
      }

      this.element.replaceChildren(
        ...this.areaPaths,
        ...(this.trajectoryLine === null ? [] : [this.trajectoryLine]),
        ...(this.markerGroup === null ? [] : [this.markerGroup]),
      )
    }
  }

  return new MapAttachedMissionOverlay()
}

function MissionNaverMap({
  snapshot,
  fallback,
}: {
  snapshot: MissionMapResponse
  fallback: ReactNode
}) {
  const mapElementRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const fittedMissionIdRef = useRef<string | null>(null)
  const initialSnapshotRef = useRef(snapshot)
  const missionOverlayRef = useRef<MissionMapOverlay | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'fallback'>(
    NAVER_MAP_CLIENT_ID ? 'loading' : 'fallback',
  )

  useEffect(() => {
    if (!NAVER_MAP_CLIENT_ID) {
      return
    }
    let cancelled = false
    let initializationFrame: number | null = null
    let initializationTimeout: number | null = null
    let idleListener: naver.maps.MapEventListener | null = null
    loadNaverMapsScript(NAVER_MAP_CLIENT_ID)
      .then(() => {
        if (cancelled || mapElementRef.current === null || !window.naver) {
          return
        }
        const { maps } = window.naver
        const initialSnapshot = initialSnapshotRef.current
        const initialCoordinates = missionViewportCoordinates(initialSnapshot)
        const [centerLongitude, centerLatitude] = coordinateCenter(
          initialCoordinates,
        )
        const map = new maps.Map(mapElementRef.current, {
          center: new maps.LatLng(centerLatitude, centerLongitude),
          zoom: initialCoordinates.length === 1 ? 17 : 15,
          zoomControl: true,
          zoomControlOptions: {
            position: maps.Position.TOP_RIGHT,
            style: maps.ZoomControlStyle.SMALL,
          },
        })
        mapRef.current = map

        const markReady = () => {
          if (idleListener !== null) {
            maps.Event.removeListener(idleListener)
            idleListener = null
          }
          if (initializationTimeout !== null) {
            window.clearTimeout(initializationTimeout)
            initializationTimeout = null
          }
          if (!cancelled) {
            setStatus('ready')
          }
        }
        const projectionStartedAt = window.performance.now()
        const initializeViewport = () => {
          if (cancelled) {
            return
          }
          try {
            const projection = map.getProjection()
            const centerOffset = projection.fromCoordToOffset(
              new maps.LatLng(centerLatitude, centerLongitude),
            )
            const projectionLimit = Math.max(
              mapElementRef.current?.clientWidth ?? 0,
              mapElementRef.current?.clientHeight ?? 0,
              1,
            ) * 4
            if (
              !Number.isFinite(centerOffset.x)
              || !Number.isFinite(centerOffset.y)
              || Math.abs(centerOffset.x) > projectionLimit
              || Math.abs(centerOffset.y) > projectionLimit
            ) {
              throw new Error('Map projection is not ready')
            }
          } catch {
            if (window.performance.now() - projectionStartedAt >= 8_000) {
              setStatus('fallback')
              return
            }
            initializationFrame = window.requestAnimationFrame(
              initializeViewport,
            )
            return
          }

          fittedMissionIdRef.current = initialSnapshot.missionId
          if (initialCoordinates.length > 1) {
            idleListener = maps.Event.addListener(map, 'idle', markReady)
            fitMapToCoordinates(map, maps, initialCoordinates)
            initializationTimeout = window.setTimeout(markReady, 1_000)
            return
          }
          markReady()
        }
        initializationFrame = window.requestAnimationFrame(initializeViewport)
      })
      .catch(() => {
        if (!cancelled) {
          setStatus('fallback')
        }
      })
    return () => {
      cancelled = true
      if (initializationFrame !== null) {
        window.cancelAnimationFrame(initializationFrame)
      }
      if (initializationTimeout !== null) {
        window.clearTimeout(initializationTimeout)
      }
      if (idleListener !== null && window.naver) {
        window.naver.maps.Event.removeListener(idleListener)
      }
      missionOverlayRef.current?.setMap(null)
      missionOverlayRef.current = null
      mapRef.current?.destroy()
      mapRef.current = null
      fittedMissionIdRef.current = null
    }
  }, [])

  useEffect(() => {
    if (status !== 'ready' || mapRef.current === null || !window.naver) {
      return
    }
    const { maps } = window.naver
    const map = mapRef.current
    if (missionOverlayRef.current === null) {
      missionOverlayRef.current = createMissionMapOverlay(
        maps,
        mapElementRef.current as HTMLElement,
        snapshot,
      )
      missionOverlayRef.current.setMap(map)
    } else {
      missionOverlayRef.current.setSnapshot(snapshot)
    }

    if (fittedMissionIdRef.current !== snapshot.missionId) {
      fitMapToCoordinates(map, maps, missionViewportCoordinates(snapshot))
      fittedMissionIdRef.current = snapshot.missionId
    }
  }, [snapshot, status])

  useEffect(() => {
    if (status !== 'ready' || mapRef.current === null || !window.naver) {
      return
    }
    const { maps } = window.naver
    const map = mapRef.current
    let redrawFrame: number | null = null

    const scheduleOverlayRedraw = () => {
      if (redrawFrame !== null) {
        return
      }
      redrawFrame = window.requestAnimationFrame(() => {
        redrawFrame = null
        missionOverlayRef.current?.draw()
      })
    }
    const viewportEvents = [
      'drag',
      'panning',
      'zooming',
      'bounds_changed',
      'size_changed',
      'zoom_changed',
      'dragend',
      'idle',
    ] as const
    const viewportListeners = viewportEvents.map((eventName) => (
      maps.Event.addListener(map, eventName, scheduleOverlayRedraw)
    ))

    scheduleOverlayRedraw()
    return () => {
      viewportListeners.forEach((listener) => {
        maps.Event.removeListener(listener)
      })
      if (redrawFrame !== null) {
        window.cancelAnimationFrame(redrawFrame)
      }
    }
  }, [status])

  if (status === 'fallback') {
    return fallback
  }
  return (
    <div className="mission-map-canvas-wrap">
      <div
        className="mission-map-canvas"
        ref={mapElementRef}
        data-testid="mission-naver-map"
      />
      {status === 'loading' ? (
        <p className="mission-map-loading">네이버 지도를 불러오는 중입니다…</p>
      ) : null}
    </div>
  )
}

function MissionFallbackMap({ snapshot }: { snapshot: MissionMapResponse }) {
  const polygons = areaPolygons(snapshot.patrolAreaBoundary)
  const boundaryPoints = boundaryCoordinates(snapshot.patrolAreaBoundary)
  const trackPoints = trajectoryCoordinates(snapshot.trajectory)
  const current = snapshot.currentPosition
  const allPoints = [
    ...boundaryPoints,
    ...trackPoints,
    ...(current === null
      ? []
      : [[current.longitude, current.latitude] as Coordinate]),
  ]
  if (allPoints.length === 0) {
    return (
      <div className="mission-map-empty">
        표시할 위치 좌표가 없습니다.
      </div>
    )
  }

  const longitudes = allPoints.map(([longitude]) => longitude)
  const latitudes = allPoints.map(([, latitude]) => latitude)
  const minimumLongitude = Math.min(...longitudes)
  const maximumLongitude = Math.max(...longitudes)
  const minimumLatitude = Math.min(...latitudes)
  const maximumLatitude = Math.max(...latitudes)
  const longitudeSpan = Math.max(maximumLongitude - minimumLongitude, 0.001)
  const latitudeSpan = Math.max(maximumLatitude - minimumLatitude, 0.001)

  function project([longitude, latitude]: Coordinate) {
    return {
      x: FALLBACK_PADDING + (
        (longitude - minimumLongitude)
        / longitudeSpan
        * (FALLBACK_WIDTH - FALLBACK_PADDING * 2)
      ),
      y: FALLBACK_HEIGHT - FALLBACK_PADDING - (
        (latitude - minimumLatitude)
        / latitudeSpan
        * (FALLBACK_HEIGHT - FALLBACK_PADDING * 2)
      ),
    }
  }

  const trajectoryPoints = trackPoints
    .map((coordinate) => {
      const point = project(coordinate)
      return `${point.x},${point.y}`
    })
    .join(' ')
  const currentPoint = current === null
    ? null
    : project([current.longitude, current.latitude])

  return (
    <div className="mission-map-fallback-wrap">
      <svg
        className="mission-map-fallback"
        viewBox={`0 0 ${FALLBACK_WIDTH} ${FALLBACK_HEIGHT}`}
        role="img"
        aria-label="순찰 구역, 실제 궤적, 현재 위치 좌표 지도"
        data-testid="mission-map-fallback"
      >
        <defs>
          <pattern
            id="mission-map-grid"
            width="40"
            height="40"
            patternUnits="userSpaceOnUse"
          >
            <path d="M 40 0 L 0 0 0 40" className="mission-map-grid-line" />
          </pattern>
        </defs>
        <rect
          width={FALLBACK_WIDTH}
          height={FALLBACK_HEIGHT}
          className="mission-map-fallback-background"
        />
        <rect
          width={FALLBACK_WIDTH}
          height={FALLBACK_HEIGHT}
          fill="url(#mission-map-grid)"
        />
        {polygons.map((polygon, index) => {
          const path = polygon
            .map((ring) => ring
              .filter(isCoordinate)
              .map((coordinate, pointIndex) => {
                const point = project(coordinate)
                return `${pointIndex === 0 ? 'M' : 'L'} ${point.x} ${point.y}`
              })
              .concat('Z')
              .join(' '))
            .join(' ')
          return (
            <path
              key={`${snapshot.patrolAreaId}-${index}`}
              d={path}
              className="mission-map-area-shape"
              fillRule="evenodd"
              data-testid="mission-area-shape"
            />
          )
        })}
        {trackPoints.length >= 2 ? (
          <polyline
            points={trajectoryPoints}
            className="mission-map-trajectory-line"
            fill="none"
            data-testid="mission-trajectory"
            data-point-count={trackPoints.length}
          />
        ) : null}
        {current !== null && currentPoint !== null ? (
          <g
            className={`mission-map-current${
              current.connectivityStatus === 'ONLINE' ? '' : ' is-offline'
            }`}
            transform={`translate(${currentPoint.x} ${currentPoint.y})`}
            data-testid="mission-current-position"
            data-latitude={current.latitude}
            data-longitude={current.longitude}
            data-heading={current.headingDeg ?? ''}
          >
            <image
              className="mission-map-current-car"
              href={carMarkerImage}
              x="-16"
              y="-23"
              width="32"
              height="46"
              preserveAspectRatio="xMidYMid meet"
              transform={`rotate(${current.headingDeg ?? 0})`}
              data-testid="mission-current-car"
            />
          </g>
        ) : null}
      </svg>
      <span className="mission-map-fallback-label">
        Naver Maps를 불러오지 못해 좌표 Layer로 표시 중
      </span>
    </div>
  )
}

export function MissionMap({
  missionId,
  missionCode,
  patrolAreaName,
}: {
  missionId: string
  missionCode: string
  patrolAreaName: string
}) {
  const requestSequenceRef = useRef(0)
  const refreshTimerRef = useRef<number | null>(null)
  const [snapshot, setSnapshot] = useState<MissionMapResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(null)

  const loadSnapshot = useCallback(async () => {
    const sequence = requestSequenceRef.current + 1
    requestSequenceRef.current = sequence
    setLoading(true)
    setError(null)
    try {
      const result = await getMissionMap(missionId)
      if (requestSequenceRef.current === sequence) {
        setSnapshot(result)
        setLastSyncedAt(new Date().toISOString())
      }
    } catch (nextError) {
      if (requestSequenceRef.current === sequence) {
        setError(nextError)
      }
    } finally {
      if (requestSequenceRef.current === sequence) {
        setLoading(false)
      }
    }
  }, [missionId])

  const scheduleSnapshot = useCallback(() => {
    if (refreshTimerRef.current !== null) {
      window.clearTimeout(refreshTimerRef.current)
    }
    refreshTimerRef.current = window.setTimeout(() => {
      refreshTimerRef.current = null
      void loadSnapshot()
    }, SNAPSHOT_DEBOUNCE_MILLIS)
  }, [loadSnapshot])

  const { status: streamStatus } = useRobotRealtime(scheduleSnapshot)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadSnapshot()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [loadSnapshot])

  useEffect(() => () => {
    if (refreshTimerRef.current !== null) {
      window.clearTimeout(refreshTimerRef.current)
    }
  }, [])

  const displayedSnapshot = snapshot?.missionId === missionId
    ? snapshot
    : null
  const positionInside = displayedSnapshot?.currentPosition === null
    || displayedSnapshot === null
    ? null
    : isInsideBoundary(
      displayedSnapshot.currentPosition,
      displayedSnapshot.patrolAreaBoundary,
    )

  return (
    <section
      className="mission-map"
      aria-labelledby={`mission-map-title-${missionId}`}
    >
      <div className="mission-section-heading mission-map-heading">
        <div>
          <MapPin size={17} aria-hidden="true" />
          <div>
            <h4 id={`mission-map-title-${missionId}`}>임무 위치 지도</h4>
            <p>{patrolAreaName} · {missionCode}</p>
          </div>
        </div>
        <button
          className="mission-map-refresh"
          type="button"
          onClick={() => void loadSnapshot()}
          disabled={loading}
          aria-label="임무 지도 새로고침"
        >
          <RefreshCw size={15} aria-hidden="true" />
        </button>
      </div>

      <div className="mission-map-livebar" aria-live="polite">
        <span className={`mission-map-stream mission-map-stream-${streamStatus}`}>
          <Radio size={13} aria-hidden="true" />
          {streamStatusLabel[streamStatus]}
        </span>
        <span>지도 갱신 {formatObservedAt(lastSyncedAt)}</span>
      </div>

      <div className="mission-map-legend" aria-label="임무 지도 범례">
        <span className="mission-map-legend-area">
          <span aria-hidden="true" />
          순찰 구역
        </span>
        <span className="mission-map-legend-trajectory">
          <span aria-hidden="true" />
          실제 궤적 ({displayedSnapshot?.trajectory.length ?? 0})
        </span>
        <span className="mission-map-legend-current">
          <img src={carMarkerImage} alt="" aria-hidden="true" />
          현재 위치
        </span>
      </div>

      {error ? (
        <div className="page-alert" role="alert">
          임무 지도를 불러오지 못했습니다. {getApiErrorMessage(error)}
        </div>
      ) : displayedSnapshot === null ? (
        <div className="admin-loading" role="status">
          <span className="loading-spinner" aria-hidden="true" />
          순찰 구역과 이동 궤적을 불러오는 중입니다.
        </div>
      ) : (
        <>
          <MissionNaverMap
            snapshot={displayedSnapshot}
            fallback={<MissionFallbackMap snapshot={displayedSnapshot} />}
          />
          <div className="mission-map-summary">
            <span>
              <Route size={14} aria-hidden="true" />
              궤적 {displayedSnapshot.trajectory.length}개 좌표
            </span>
            <span>
              <Navigation size={14} aria-hidden="true" />
              {displayedSnapshot.currentPosition === null
                ? '현재 위치 미수신'
                : formatObservedAt(
                  displayedSnapshot.currentPosition.observedAt,
                )}
            </span>
          </div>
          {positionInside === false ? (
            <div className="mission-map-boundary-warning" role="alert">
              <TriangleAlert size={16} aria-hidden="true" />
              현재 위치가 지정된 순찰 구역 밖입니다.
            </div>
          ) : null}
        </>
      )}
    </section>
  )
}
