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
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
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

const NAVER_MAP_CLIENT_ID = import.meta.env.VITE_NAVER_MAP_CLIENT_ID
const SNAPSHOT_DEBOUNCE_MILLIS = 400
const FALLBACK_WIDTH = 800
const FALLBACK_HEIGHT = 360
const FALLBACK_PADDING = 30

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

function currentMarkerIcon(
  maps: typeof naver.maps,
  headingDeg: number | null,
  online: boolean,
): naver.maps.MarkerIcon {
  const heading = headingDeg !== null && Number.isFinite(headingDeg)
    ? headingDeg
    : 0
  const color = online ? '#175c4c' : '#6b7280'
  return {
    content: `<span style="display:grid;width:32px;height:32px;border:3px solid #fff;border-radius:50%;place-items:center;color:#fff;background:${color};box-shadow:0 3px 10px rgba(15,23,42,.35)"><span style="font-size:16px;line-height:1;transform:rotate(${heading}deg)">↑</span></span>`,
    size: new maps.Size(32, 32),
    anchor: new maps.Point(16, 16),
  }
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
  const [status, setStatus] = useState<'loading' | 'ready' | 'fallback'>(
    NAVER_MAP_CLIENT_ID ? 'loading' : 'fallback',
  )
  const polygons = useMemo(
    () => areaPolygons(snapshot.patrolAreaBoundary),
    [snapshot.patrolAreaBoundary],
  )
  const trackCoordinates = useMemo(
    () => trajectoryCoordinates(snapshot.trajectory),
    [snapshot.trajectory],
  )

  useEffect(() => {
    if (!NAVER_MAP_CLIENT_ID) {
      return
    }
    let cancelled = false
    loadNaverMapsScript(NAVER_MAP_CLIENT_ID)
      .then(() => {
        if (cancelled || mapElementRef.current === null || !window.naver) {
          return
        }
        mapRef.current = new window.naver.maps.Map(mapElementRef.current, {
          center: new window.naver.maps.LatLng(36.1195, 128.3446),
          zoom: 15,
          zoomControl: true,
          zoomControlOptions: {
            position: window.naver.maps.Position.TOP_RIGHT,
          },
        })
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) {
          setStatus('fallback')
        }
      })
    return () => {
      cancelled = true
      mapRef.current?.destroy()
      mapRef.current = null
    }
  }, [])

  useEffect(() => {
    if (status !== 'ready' || mapRef.current === null || !window.naver) {
      return
    }
    const { maps } = window.naver
    const map = mapRef.current
    const polygonObjects = polygons.map((polygon) => (
      new maps.Polygon({
        map,
        paths: polygon.map((ring) => ring
          .filter(isCoordinate)
          .map(([longitude, latitude]) => (
            new maps.LatLng(latitude, longitude)
          ))),
        fillColor: '#2f806c',
        fillOpacity: 0.1,
        strokeColor: '#175c4c',
        strokeOpacity: 0.9,
        strokeWeight: 3,
        clickable: false,
        zIndex: 10,
      })
    ))
    const trajectoryObject = trackCoordinates.length >= 2
      ? new maps.Polyline({
        map,
        path: trackCoordinates.map(([longitude, latitude]) => (
          new maps.LatLng(latitude, longitude)
        )),
        strokeColor: '#e36b2c',
        strokeOpacity: 0.95,
        strokeWeight: 5,
        strokeStyle: 'solid',
        clickable: false,
        zIndex: 20,
      })
      : null
    const current = snapshot.currentPosition
    const currentMarker = current === null
      ? null
      : new maps.Marker({
        map,
        position: new maps.LatLng(current.latitude, current.longitude),
        icon: currentMarkerIcon(
          maps,
          current.headingDeg,
          current.connectivityStatus === 'ONLINE',
        ),
        title: `현재 위치 · ${formatObservedAt(current.observedAt)}`,
        zIndex: 30,
      })

    const allCoordinates = [
      ...boundaryCoordinates(snapshot.patrolAreaBoundary),
      ...trackCoordinates,
      ...(current === null
        ? []
        : [[current.longitude, current.latitude] as Coordinate]),
    ]
    if (allCoordinates.length === 1) {
      const [longitude, latitude] = allCoordinates[0]
      map.setCenter(new maps.LatLng(latitude, longitude))
      map.setZoom(17)
    } else if (allCoordinates.length > 1) {
      const longitudes = allCoordinates.map(([longitude]) => longitude)
      const latitudes = allCoordinates.map(([, latitude]) => latitude)
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

    return () => {
      polygonObjects.forEach((polygon) => polygon.setMap(null))
      trajectoryObject?.setMap(null)
      currentMarker?.setMap(null)
    }
  }, [polygons, snapshot, status, trackCoordinates])

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
            className="mission-map-current"
            transform={`translate(${currentPoint.x} ${currentPoint.y})`}
            data-testid="mission-current-position"
            data-latitude={current.latitude}
            data-longitude={current.longitude}
            data-heading={current.headingDeg ?? ''}
          >
            <circle r="13" />
            <path
              d="M 0 -9 L 5 6 L 0 3 L -5 6 Z"
              transform={`rotate(${current.headingDeg ?? 0})`}
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
          <span aria-hidden="true" />
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
