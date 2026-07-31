import {
  Bot,
  Crosshair,
  MapPinned,
} from 'lucide-react'
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { loadNaverMapsScript } from '../../lib/naverMaps'
import type { RobotMapItem } from '../robot/robotTypes'
import {
  calculateBoundaryAverageCenter,
  getBoundaryOuterPoints,
  getBoundaryPolygons,
} from './patrolAreaBoundary'
import type {
  GeoJsonCoordinate,
  PatrolArea,
  PatrolAreaBoundary,
  RobotOption,
} from './missionTypes'
import './patrol-area-selection-preview.css'

const NAVER_MAP_CLIENT_ID = import.meta.env.VITE_NAVER_MAP_CLIENT_ID
const FALLBACK_WIDTH = 800
const FALLBACK_HEIGHT = 300
const FALLBACK_PADDING = 28
const DEFAULT_CENTER = {
  latitude: 36.1195,
  longitude: 128.3446,
}

type ProjectedPoint = {
  x: number
  y: number
}

type ProjectedPolygon = ProjectedPoint[][]

function robotCoordinate(
  robot: RobotMapItem | null,
): GeoJsonCoordinate | null {
  if (robot === null) {
    return null
  }

  const { longitude, latitude } = robot
  if (
    longitude === null
    || latitude === null
    || !Number.isFinite(longitude)
    || !Number.isFinite(latitude)
  ) {
    return null
  }
  return [longitude, latitude]
}

function projectCoordinate(
  projection: naver.maps.MapSystemProjection,
  [longitude, latitude]: GeoJsonCoordinate,
): ProjectedPoint {
  const offset = projection.fromCoordToOffset(
    new naver.maps.LatLng(latitude, longitude),
  )
  return {
    x: Number(offset.x.toFixed(2)),
    y: Number(offset.y.toFixed(2)),
  }
}

function createProjectedPolygonPath(polygon: ProjectedPolygon): string {
  return polygon
    .map((ring) => ring
      .map(({ x, y }, pointIndex) => (
        `${pointIndex === 0 ? 'M' : 'L'} ${x} ${y}`
      ))
      .concat('Z')
      .join(' '))
    .join(' ')
}

function PatrolAreaNaverPreview({
  boundary,
  center,
  robot,
  fallback,
}: {
  boundary: PatrolAreaBoundary
  center: GeoJsonCoordinate
  robot: RobotMapItem | null
  fallback: ReactNode
}) {
  const mapElementRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'fallback'>(
    NAVER_MAP_CLIENT_ID ? 'loading' : 'fallback',
  )
  const [projectedPolygons, setProjectedPolygons] = useState<
    ProjectedPolygon[]
  >([])
  const [projectedCenter, setProjectedCenter] = useState<ProjectedPoint | null>(
    null,
  )
  const [projectedRobot, setProjectedRobot] = useState<ProjectedPoint | null>(
    null,
  )
  const polygons = useMemo(
    () => getBoundaryPolygons(boundary),
    [boundary],
  )
  const boundaryPoints = useMemo(
    () => getBoundaryOuterPoints(boundary),
    [boundary],
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
          center: new window.naver.maps.LatLng(
            DEFAULT_CENTER.latitude,
            DEFAULT_CENTER.longitude,
          ),
          zoom: 17,
          draggable: false,
          pinchZoom: false,
          scrollWheel: false,
          keyboardShortcuts: false,
          disableDoubleClickZoom: true,
          disableDoubleTapZoom: true,
          disableTwoFingerTapZoom: true,
          zoomControl: false,
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
      setProjectedPolygons([])
      setProjectedCenter(null)
      setProjectedRobot(null)
      return
    }

    const { maps } = window.naver
    const map = mapRef.current
    const centerPosition = new maps.LatLng(center[1], center[0])
    const viewportListeners: naver.maps.MapEventListener[] = []
    let projectionFrame: number | null = null

    const updateProjection = () => {
      const projection = map.getProjection()
      setProjectedPolygons(polygons.map((polygon) => (
        polygon.map((ring) => ring.map((coordinate) => (
          projectCoordinate(projection, coordinate)
        )))
      )))
      setProjectedCenter(projectCoordinate(projection, center))
      const robotPoint = robotCoordinate(robot)
      setProjectedRobot(robotPoint === null
        ? null
        : projectCoordinate(projection, robotPoint))
    }
    const scheduleProjection = () => {
      if (projectionFrame !== null) {
        return
      }
      projectionFrame = window.requestAnimationFrame(() => {
        projectionFrame = null
        updateProjection()
      })
    }

    viewportListeners.push(
      maps.Event.addListener(map, 'bounds_changed', scheduleProjection),
      maps.Event.addListener(map, 'size_changed', scheduleProjection),
    )

    if (boundaryPoints.length > 1) {
      const longitudeDelta = Math.max(
        ...boundaryPoints.map(
          ([longitude]) => Math.abs(longitude - center[0]),
        ),
        0.0005,
      )
      const latitudeDelta = Math.max(
        ...boundaryPoints.map(
          ([, latitude]) => Math.abs(latitude - center[1]),
        ),
        0.0005,
      )
      map.fitBounds(new maps.LatLngBounds(
        new maps.LatLng(
          center[1] - latitudeDelta,
          center[0] - longitudeDelta,
        ),
        new maps.LatLng(
          center[1] + latitudeDelta,
          center[0] + longitudeDelta,
        ),
      ))
    } else {
      map.setZoom(17)
    }
    // fitBounds로 계산한 Zoom은 유지하고 사용자가 요청한 점 평균을 중앙에 둔다.
    map.setCenter(centerPosition)
    scheduleProjection()

    return () => {
      viewportListeners.forEach((listener) => {
        maps.Event.removeListener(listener)
      })
      if (projectionFrame !== null) {
        window.cancelAnimationFrame(projectionFrame)
      }
    }
  }, [boundaryPoints, center, polygons, robot, status])

  if (status === 'fallback') {
    return fallback
  }

  return (
    <div className="patrol-area-preview-map-wrap">
      <div
        className="patrol-area-preview-map"
        ref={mapElementRef}
        aria-label="선택한 순찰 구역 Polygon 지도"
        data-testid="mission-area-preview-naver-map"
      />
      {status === 'ready'
        && projectedPolygons.length > 0
        && projectedCenter !== null ? (
          <svg
            className="patrol-area-preview-projected-overlay"
            data-testid="mission-area-preview-projected-overlay"
            aria-hidden="true"
          >
            {projectedPolygons.map((polygon, polygonIndex) => (
              <path
                key={polygonIndex}
                className="patrol-area-preview-projected-shape"
                d={createProjectedPolygonPath(polygon)}
                fillRule="evenodd"
              />
            ))}
            <circle
              className="patrol-area-preview-projected-center-circle"
              cx={projectedCenter.x}
              cy={projectedCenter.y}
              r="10"
            />
            <path
              className="patrol-area-preview-projected-center-cross"
              d={[
                `M ${projectedCenter.x - 16} ${projectedCenter.y}`,
                `H ${projectedCenter.x + 16}`,
                `M ${projectedCenter.x} ${projectedCenter.y - 16}`,
                `V ${projectedCenter.y + 16}`,
              ].join(' ')}
            />
            {projectedRobot !== null && robot !== null ? (
              <g
                data-testid="mission-area-preview-robot"
                data-robot-id={robot.robotId}
                data-mission-status={robot.missionStatus ?? ''}
              >
                <circle
                  className={[
                    'patrol-area-preview-projected-robot',
                    robot.connectivityStatus === 'ONLINE'
                      ? 'is-online'
                      : 'is-offline',
                  ].join(' ')}
                  cx={projectedRobot.x}
                  cy={projectedRobot.y}
                  r="15"
                />
                <path
                  className="patrol-area-preview-projected-robot-heading"
                  d={[
                    `M ${projectedRobot.x} ${projectedRobot.y - 10}`,
                    `L ${projectedRobot.x + 5} ${projectedRobot.y + 7}`,
                    `L ${projectedRobot.x} ${projectedRobot.y + 4}`,
                    `L ${projectedRobot.x - 5} ${projectedRobot.y + 7}`,
                    'Z',
                  ].join(' ')}
                  transform={[
                    `rotate(${robot.headingDeg ?? 0}`,
                    `${projectedRobot.x}`,
                    `${projectedRobot.y})`,
                  ].join(' ')}
                />
              </g>
            ) : null}
          </svg>
        ) : null}
      {status === 'loading' ? (
        <div className="patrol-area-preview-loading" role="status">
          <span className="loading-spinner" aria-hidden="true" />
          선택 구역 지도를 불러오는 중…
        </div>
      ) : null}
    </div>
  )
}

function PatrolAreaFallbackPreview({
  area,
  center,
  robot,
}: {
  area: PatrolArea
  center: GeoJsonCoordinate
  robot: RobotMapItem | null
}) {
  const polygons = getBoundaryPolygons(area.boundary)
  const points = getBoundaryOuterPoints(area.boundary)
  const robotPoint = robotCoordinate(robot)
  const maximumLongitudeDelta = Math.max(
    ...points.map(([longitude]) => Math.abs(longitude - center[0])),
    0.0005,
  )
  const maximumLatitudeDelta = Math.max(
    ...points.map(([, latitude]) => Math.abs(latitude - center[1])),
    0.0005,
  )
  const drawableHalfWidth = FALLBACK_WIDTH / 2 - FALLBACK_PADDING
  const drawableHalfHeight = FALLBACK_HEIGHT / 2 - FALLBACK_PADDING

  function project([longitude, latitude]: GeoJsonCoordinate) {
    return {
      x: FALLBACK_WIDTH / 2
        + (longitude - center[0]) / maximumLongitudeDelta * drawableHalfWidth,
      y: FALLBACK_HEIGHT / 2
        - (latitude - center[1]) / maximumLatitudeDelta * drawableHalfHeight,
    }
  }
  const projectedRobot = robotPoint === null ? null : project(robotPoint)

  return (
    <div className="patrol-area-preview-map-wrap">
      <svg
        className="patrol-area-preview-fallback"
        viewBox={`0 0 ${FALLBACK_WIDTH} ${FALLBACK_HEIGHT}`}
        role="img"
        aria-label={`${area.areaName} Polygon과 경계점 평균 중심`}
        data-testid="mission-area-preview-fallback"
      >
        <rect
          width={FALLBACK_WIDTH}
          height={FALLBACK_HEIGHT}
          className="patrol-area-preview-fallback-background"
        />
        {polygons.map((polygon, polygonIndex) => {
          const path = polygon
            .map((ring) => ring
              .map((coordinate, pointIndex) => {
                const point = project(coordinate)
                return `${pointIndex === 0 ? 'M' : 'L'} ${point.x} ${point.y}`
              })
              .concat('Z')
              .join(' '))
            .join(' ')
          return (
            <path
              key={`${area.patrolAreaId}-${polygonIndex}`}
              d={path}
              className="patrol-area-preview-shape"
              fillRule="evenodd"
            />
          )
        })}
        <g
          className="patrol-area-preview-center"
          transform={`translate(${FALLBACK_WIDTH / 2} ${FALLBACK_HEIGHT / 2})`}
          data-testid="mission-area-preview-center"
        >
          <circle r="10" />
          <path d="M -16 0 H 16 M 0 -16 V 16" />
        </g>
        {projectedRobot !== null && robot !== null ? (
          <g
            className={[
              'patrol-area-preview-robot',
              robot.connectivityStatus === 'ONLINE'
                ? 'is-online'
                : 'is-offline',
            ].join(' ')}
            transform={`translate(${projectedRobot.x} ${projectedRobot.y})`}
            data-testid="mission-area-preview-robot"
            data-robot-id={robot.robotId}
            data-mission-status={robot.missionStatus ?? ''}
          >
            <circle r="15" />
            <path
              d="M 0 -10 L 5 7 L 0 4 L -5 7 Z"
              transform={`rotate(${robot.headingDeg ?? 0})`}
            />
          </g>
        ) : null}
      </svg>
      <span className="patrol-area-preview-fallback-label">
        Naver Maps를 불러오지 못해 좌표 Layer로 표시 중
      </span>
    </div>
  )
}

export function PatrolAreaSelectionPreview({
  area,
  selectedRobot,
  robotPosition,
}: {
  area: PatrolArea
  selectedRobot: RobotOption | null
  robotPosition: RobotMapItem | null
}) {
  const center = useMemo(
    () => calculateBoundaryAverageCenter(area.boundary),
    [area.boundary],
  )
  const pointCount = useMemo(
    () => getBoundaryOuterPoints(area.boundary).length,
    [area.boundary],
  )

  if (center === null) {
    return (
      <div className="page-alert mission-area-selection-preview" role="alert">
        선택한 구역의 유효한 경계 좌표를 찾지 못했습니다.
      </div>
    )
  }

  return (
    <section
      className="mission-area-selection-preview"
      aria-labelledby={`mission-area-preview-title-${area.patrolAreaId}`}
      data-testid="mission-area-selection-preview"
      data-center-longitude={center[0]}
      data-center-latitude={center[1]}
    >
      <div className="patrol-area-preview-heading">
        <div>
          <MapPinned size={18} aria-hidden="true" />
          <div>
            <strong id={`mission-area-preview-title-${area.patrolAreaId}`}>
              {area.areaName} 선택 구역
            </strong>
            <p>{area.areaCode} · 등록된 Polygon 경계</p>
          </div>
        </div>
        <span>경계점 {pointCount}개</span>
      </div>

      <PatrolAreaNaverPreview
        boundary={area.boundary}
        center={center}
        robot={robotPosition}
        fallback={(
          <PatrolAreaFallbackPreview
            area={area}
            center={center}
            robot={robotPosition}
          />
        )}
      />

      <div className="patrol-area-preview-center-summary">
        <Crosshair size={15} aria-hidden="true" />
        <span>경계점 평균 중심</span>
        <code>
          위도 {center[1].toFixed(7)} · 경도 {center[0].toFixed(7)}
        </code>
      </div>
      {selectedRobot !== null ? (
        <div className="patrol-area-preview-robot-summary">
          <Bot size={15} aria-hidden="true" />
          <span>선택 로봇</span>
          <strong>
            {selectedRobot.robotName} ({selectedRobot.mqttId})
          </strong>
          <code>
            {robotPosition === null
              || robotPosition.latitude === null
              || robotPosition.longitude === null
              ? '현재 GPS 위치 미수신'
              : [
                `위도 ${robotPosition.latitude.toFixed(7)}`,
                `경도 ${robotPosition.longitude.toFixed(7)}`,
                robotPosition.missionStatus === null
                  ? '임무 미수행'
                  : `임무 ${robotPosition.missionStatus}`,
              ].join(' · ')}
          </code>
        </div>
      ) : null}
    </section>
  )
}
