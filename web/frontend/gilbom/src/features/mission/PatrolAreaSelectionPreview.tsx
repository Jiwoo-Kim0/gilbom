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
import { getRobotStatusLabel } from '../robot/robotStatusPresentation'
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
const PREVIEW_MIN_ZOOM = 10
const PREVIEW_MAX_ZOOM = 21
const TOOLTIP_HALF_WIDTH = 94
const FALLBACK_WIDTH = 800
const FALLBACK_HEIGHT = 300
const FALLBACK_PADDING = 28

type ProjectedPoint = {
  x: number
  y: number
}

type ProjectedBoundaryPoint = ProjectedPoint & {
  longitude: number
  latitude: number
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

type PatrolAreaViewOverlay = naver.maps.OverlayView & {
  hideTooltip(): void
}

function createPatrolAreaViewOverlay(
  maps: typeof naver.maps,
  mapElement: HTMLElement,
  polygons: GeoJsonCoordinate[][][],
  boundaryPoints: GeoJsonCoordinate[],
  center: GeoJsonCoordinate,
): PatrolAreaViewOverlay {
  class MapAttachedViewOverlay extends maps.OverlayView {
    private readonly element = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'svg',
    )
    private readonly shapes = polygons.map(() => document.createElementNS(
      'http://www.w3.org/2000/svg',
      'path',
    ))
    private readonly pointGroups = boundaryPoints.map(() => document.createElementNS(
      'http://www.w3.org/2000/svg',
      'g',
    ))
    private readonly pointCircles = boundaryPoints.map(() => document.createElementNS(
      'http://www.w3.org/2000/svg',
      'circle',
    ))
    private readonly pointLabels = boundaryPoints.map(() => document.createElementNS(
      'http://www.w3.org/2000/svg',
      'text',
    ))
    private readonly centerGroup = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'g',
    )
    private readonly centerCircle = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'circle',
    )
    private readonly centerCross = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'path',
    )
    private readonly tooltip = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'g',
    )
    private readonly tooltipRect = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'rect',
    )
    private readonly tooltipLatitude = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'text',
    )
    private readonly tooltipLongitude = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'text',
    )
    private hoveredPointIndex: number | null = null

    constructor() {
      super()
      this.element.classList.add('patrol-area-view-projected-overlay')
      this.element.setAttribute('overflow', 'hidden')
      this.element.setAttribute('aria-hidden', 'true')
      this.element.setAttribute(
        'data-testid',
        'mission-area-preview-projected-overlay',
      )

      this.shapes.forEach((shape) => {
        shape.classList.add('patrol-area-preview-projected-shape')
        shape.setAttribute('fill-rule', 'evenodd')
      })
      this.centerGroup.setAttribute(
        'data-testid',
        'mission-area-preview-center',
      )
      this.centerGroup.setAttribute('aria-label', '경계점 평균 중심')
      this.centerCircle.classList.add(
        'patrol-area-preview-projected-center-circle',
      )
      this.centerCircle.setAttribute('r', '10')
      this.centerCross.classList.add(
        'patrol-area-preview-projected-center-cross',
      )
      this.centerCross.setAttribute('d', 'M -16 0 H 16 M 0 -16 V 16')
      this.centerGroup.append(this.centerCircle, this.centerCross)
      this.pointGroups.forEach((group, index) => {
        const [longitude, latitude] = boundaryPoints[index]
        const circle = this.pointCircles[index]
        const label = this.pointLabels[index]
        group.classList.add('patrol-area-preview-projected-vertex')
        group.setAttribute(
          'data-testid',
          'patrol-area-preview-boundary-point',
        )
        group.setAttribute('data-longitude', String(longitude))
        group.setAttribute('data-latitude', String(latitude))
        circle.setAttribute('r', '12')
        label.textContent = String(index + 1)
        group.append(circle, label)
        group.addEventListener('mouseenter', () => {
          this.showTooltip(index)
        })
        group.addEventListener('mouseleave', () => {
          this.hideTooltip()
        })
      })

      this.tooltip.classList.add('patrol-area-preview-coordinate-tooltip')
      this.tooltip.setAttribute(
        'data-testid',
        'patrol-area-preview-coordinate-tooltip',
      )
      this.tooltipRect.setAttribute('x', String(-TOOLTIP_HALF_WIDTH))
      this.tooltipRect.setAttribute('width', String(TOOLTIP_HALF_WIDTH * 2))
      this.tooltipRect.setAttribute('height', '48')
      this.tooltipRect.setAttribute('rx', '8')
      this.tooltipLatitude.setAttribute('x', '0')
      this.tooltipLongitude.setAttribute('x', '0')
      this.tooltip.append(
        this.tooltipRect,
        this.tooltipLatitude,
        this.tooltipLongitude,
      )
      this.element.append(
        ...this.shapes,
        this.centerGroup,
        ...this.pointGroups,
      )
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

      const project = ([longitude, latitude]: GeoJsonCoordinate) => {
        const offset = projection.fromCoordToOffset(
          new maps.LatLng(latitude, longitude),
        )
        return {
          x: Number(offset.x.toFixed(2)),
          y: Number(offset.y.toFixed(2)),
        }
      }
      this.shapes.forEach((shape, polygonIndex) => {
        shape.setAttribute(
          'd',
          createProjectedPolygonPath(
            polygons[polygonIndex].map((ring) => ring.map(project)),
          ),
        )
      })
      const projectedCenter = project(center)
      this.centerGroup.setAttribute(
        'transform',
        `translate(${projectedCenter.x} ${projectedCenter.y})`,
      )
      boundaryPoints.forEach((coordinate, index) => {
        const { x, y } = project(coordinate)
        this.pointCircles[index].setAttribute('cx', String(x))
        this.pointCircles[index].setAttribute('cy', String(y))
        this.pointLabels[index].setAttribute('x', String(x))
        this.pointLabels[index].setAttribute('y', String(y))
      })
      this.drawTooltip(project, width)
    }

    hideTooltip() {
      this.hoveredPointIndex = null
      this.tooltip.remove()
    }

    onRemove() {
      this.hideTooltip()
      this.element.remove()
    }

    private showTooltip(index: number) {
      this.hoveredPointIndex = index
      this.element.appendChild(this.tooltip)
      this.draw()
    }

    private drawTooltip(
      project: (coordinate: GeoJsonCoordinate) => ProjectedPoint,
      mapWidth: number,
    ) {
      if (this.hoveredPointIndex === null) {
        return
      }
      const coordinate = boundaryPoints[this.hoveredPointIndex]
      if (coordinate === undefined) {
        this.hideTooltip()
        return
      }
      const [longitude, latitude] = coordinate
      const { x, y } = project(coordinate)
      const showBelow = y < 70
      const tooltipX = Math.min(
        Math.max(x, TOOLTIP_HALF_WIDTH),
        Math.max(TOOLTIP_HALF_WIDTH, mapWidth - TOOLTIP_HALF_WIDTH),
      )
      this.tooltip.setAttribute('transform', `translate(${tooltipX} ${y})`)
      this.tooltipRect.setAttribute('y', String(showBelow ? 18 : -66))
      this.tooltipLatitude.setAttribute('y', String(showBelow ? 39 : -45))
      this.tooltipLongitude.setAttribute('y', String(showBelow ? 55 : -29))
      this.tooltipLatitude.textContent = `위도 ${latitude.toFixed(7)}`
      this.tooltipLongitude.textContent = `경도 ${longitude.toFixed(7)}`
    }
  }

  return new MapAttachedViewOverlay()
}

function PatrolAreaNaverView({
  boundary,
  center,
  fallback,
}: {
  boundary: PatrolAreaBoundary
  center: GeoJsonCoordinate
  fallback: ReactNode
}) {
  const mapElementRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const overlayRef = useRef<PatrolAreaViewOverlay | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'fallback'>(
    NAVER_MAP_CLIENT_ID ? 'loading' : 'fallback',
  )
  const polygons = useMemo(() => getBoundaryPolygons(boundary), [boundary])
  const boundaryPoints = useMemo(
    () => getBoundaryOuterPoints(boundary),
    [boundary],
  )

  useEffect(() => {
    if (!NAVER_MAP_CLIENT_ID) {
      return
    }

    let cancelled = false
    let projectionFrame: number | null = null
    const viewportListeners: naver.maps.MapEventListener[] = []
    const mapElement = mapElementRef.current
    const hideTooltip = () => overlayRef.current?.hideTooltip()

    loadNaverMapsScript(NAVER_MAP_CLIENT_ID)
      .then(() => {
        if (cancelled || mapElement === null || !window.naver) {
          return
        }
        const { maps } = window.naver
        const map = new maps.Map(mapElement, {
          center: new maps.LatLng(center[1], center[0]),
          zoom: 17,
          minZoom: PREVIEW_MIN_ZOOM,
          maxZoom: PREVIEW_MAX_ZOOM,
          draggable: true,
          pinchZoom: true,
          scrollWheel: true,
          keyboardShortcuts: true,
          disableDoubleClickZoom: false,
          disableDoubleTapZoom: false,
          disableTwoFingerTapZoom: false,
          zoomControl: true,
          zoomControlOptions: {
            position: maps.Position.TOP_RIGHT,
            style: maps.ZoomControlStyle.SMALL,
          },
        })
        mapRef.current = map
        overlayRef.current = createPatrolAreaViewOverlay(
          maps,
          mapElement,
          polygons,
          boundaryPoints,
          center,
        )
        overlayRef.current.setMap(map)

        const scheduleProjection = () => {
          if (projectionFrame !== null) {
            return
          }
          projectionFrame = window.requestAnimationFrame(() => {
            projectionFrame = null
            overlayRef.current?.draw()
          })
        }
        const handleViewportChange = () => {
          hideTooltip()
          scheduleProjection()
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
        viewportListeners.push(...viewportEvents.map((eventName) => (
          maps.Event.addListener(map, eventName, handleViewportChange)
        )))

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
        map.setCenter(new maps.LatLng(center[1], center[0]))
        scheduleProjection()
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) {
          setStatus('fallback')
        }
      })

    mapElement?.addEventListener('pointerdown', hideTooltip, true)
    mapElement?.addEventListener('touchstart', hideTooltip, true)
    mapElement?.addEventListener('wheel', hideTooltip, true)
    return () => {
      cancelled = true
      mapElement?.removeEventListener('pointerdown', hideTooltip, true)
      mapElement?.removeEventListener('touchstart', hideTooltip, true)
      mapElement?.removeEventListener('wheel', hideTooltip, true)
      if (window.naver) {
        viewportListeners.forEach((listener) => {
          window.naver?.maps.Event.removeListener(listener)
        })
      }
      if (projectionFrame !== null) {
        window.cancelAnimationFrame(projectionFrame)
      }
      overlayRef.current?.setMap(null)
      overlayRef.current = null
      mapRef.current?.destroy()
      mapRef.current = null
    }
  }, [boundaryPoints, center, polygons])

  if (status === 'fallback') {
    return fallback
  }

  return (
    <div className="patrol-area-preview-map-wrap">
      <div
        className="patrol-area-preview-map"
        ref={mapElementRef}
        aria-label="조회할 순찰 구역 Polygon 지도"
        data-testid="mission-area-preview-naver-map"
      />
      {status === 'loading' ? (
        <div className="patrol-area-preview-loading" role="status">
          <span className="loading-spinner" aria-hidden="true" />
          조회 구역 지도를 불러오는 중…
        </div>
      ) : null}
    </div>
  )
}

function PatrolAreaNaverPreview({
  boundary,
  center,
  robot,
  fallback,
  showBoundaryPoints,
}: {
  boundary: PatrolAreaBoundary
  center: GeoJsonCoordinate
  robot: RobotMapItem | null
  fallback: ReactNode
  showBoundaryPoints: boolean
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
  const [projectedBoundaryPoints, setProjectedBoundaryPoints] = useState<
    ProjectedBoundaryPoint[]
  >([])
  const [hoveredBoundaryPointIndex, setHoveredBoundaryPointIndex] = useState<
    number | null
  >(null)
  const [mapWidth, setMapWidth] = useState(0)
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
        const { maps } = window.naver
        mapRef.current = new maps.Map(mapElementRef.current, {
          center: new maps.LatLng(center[1], center[0]),
          zoom: 17,
          minZoom: PREVIEW_MIN_ZOOM,
          maxZoom: PREVIEW_MAX_ZOOM,
          draggable: showBoundaryPoints,
          pinchZoom: showBoundaryPoints,
          scrollWheel: showBoundaryPoints,
          keyboardShortcuts: showBoundaryPoints,
          disableDoubleClickZoom: !showBoundaryPoints,
          disableDoubleTapZoom: !showBoundaryPoints,
          disableTwoFingerTapZoom: !showBoundaryPoints,
          zoomControl: showBoundaryPoints,
          zoomControlOptions: showBoundaryPoints ? {
            position: maps.Position.TOP_RIGHT,
            style: maps.ZoomControlStyle.SMALL,
          } : undefined,
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
  }, [center, showBoundaryPoints])

  useEffect(() => {
    if (status !== 'ready' || mapRef.current === null || !window.naver) {
      setProjectedPolygons([])
      setProjectedCenter(null)
      setProjectedRobot(null)
      setProjectedBoundaryPoints([])
      setHoveredBoundaryPointIndex(null)
      return
    }

    const { maps } = window.naver
    const map = mapRef.current
    const centerPosition = new maps.LatLng(center[1], center[0])
    const viewportListeners: naver.maps.MapEventListener[] = []
    let projectionFrame: number | null = null

    const updateProjection = () => {
      const projection = map.getProjection()
      setMapWidth(mapElementRef.current?.clientWidth ?? 0)
      setProjectedPolygons(polygons.map((polygon) => (
        polygon.map((ring) => ring.map((coordinate) => (
          projectCoordinate(projection, coordinate)
        )))
      )))
      setProjectedBoundaryPoints(boundaryPoints.map(
        ([longitude, latitude]) => ({
          ...projectCoordinate(projection, [longitude, latitude]),
          longitude,
          latitude,
        }),
      ))
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

    const handleViewportChange = () => {
      setHoveredBoundaryPointIndex(null)
      scheduleProjection()
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
    viewportListeners.push(...viewportEvents.map((eventName) => (
      maps.Event.addListener(map, eventName, handleViewportChange)
    )))

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

  const hoveredBoundaryPoint = hoveredBoundaryPointIndex === null
    ? null
    : projectedBoundaryPoints[hoveredBoundaryPointIndex] ?? null
  const showTooltipBelow = hoveredBoundaryPoint !== null
    && hoveredBoundaryPoint.y < 70
  const tooltipX = hoveredBoundaryPoint === null
    ? 0
    : Math.min(
        Math.max(hoveredBoundaryPoint.x, TOOLTIP_HALF_WIDTH),
        Math.max(TOOLTIP_HALF_WIDTH, mapWidth - TOOLTIP_HALF_WIDTH),
      )
  const tooltipRectY = showTooltipBelow ? 18 : -66
  const tooltipLatitudeY = showTooltipBelow ? 39 : -45
  const tooltipLongitudeY = showTooltipBelow ? 55 : -29

  if (status === 'fallback') {
    return fallback
  }

  return (
    <div className="patrol-area-preview-map-wrap">
      <div
        className="patrol-area-preview-map"
        ref={mapElementRef}
   
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
            {showBoundaryPoints ? projectedBoundaryPoints.map((point, index) => (
              <g
                key={`${point.longitude}-${point.latitude}-${index}`}
                className="patrol-area-preview-projected-vertex"
                data-testid="patrol-area-preview-boundary-point"
                data-longitude={point.longitude}
                data-latitude={point.latitude}
                onMouseEnter={() => setHoveredBoundaryPointIndex(index)}
                onMouseLeave={() => setHoveredBoundaryPointIndex(null)}
              >
                <circle cx={point.x} cy={point.y} r="12" />
                <text x={point.x} y={point.y}>{index + 1}</text>
              </g>
            )) : null}
            {showBoundaryPoints && hoveredBoundaryPoint !== null ? (
              <g
                className="patrol-area-preview-coordinate-tooltip"
                data-testid="patrol-area-preview-coordinate-tooltip"
                transform={[
                  `translate(${tooltipX}`,
                  `${hoveredBoundaryPoint.y})`,
                ].join(' ')}
              >
                <rect
                  x={-TOOLTIP_HALF_WIDTH}
                  y={tooltipRectY}
                  width={TOOLTIP_HALF_WIDTH * 2}
                  height="48"
                  rx="8"
                />
                <text x="0" y={tooltipLatitudeY}>
                  위도 {hoveredBoundaryPoint.latitude.toFixed(7)}
                </text>
                <text x="0" y={tooltipLongitudeY}>
                  경도 {hoveredBoundaryPoint.longitude.toFixed(7)}
                </text>
              </g>
            ) : null}
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
  showBoundaryPoints = false,
  titleSuffix = '선택 구역',
}: {
  area: PatrolArea
  selectedRobot: RobotOption | null
  robotPosition: RobotMapItem | null
  showBoundaryPoints?: boolean
  titleSuffix?: string
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
              {area.areaName} {titleSuffix}
            </strong>
          
          </div>
        </div>
        <span>경계점 {pointCount}개</span>
      </div>

      {showBoundaryPoints ? (
        <PatrolAreaNaverView
          boundary={area.boundary}
          center={center}
          fallback={(
            <PatrolAreaFallbackPreview
              area={area}
              center={center}
              robot={null}
            />
          )}
        />
      ) : (
        <PatrolAreaNaverPreview
          boundary={area.boundary}
          center={center}
          robot={robotPosition}
          showBoundaryPoints={false}
          fallback={(
            <PatrolAreaFallbackPreview
              area={area}
              center={center}
              robot={robotPosition}
            />
          )}
        />
      )}

      <div className="patrol-area-preview-center-summary">
        <Crosshair size={15} aria-hidden="true" />
        <span>경계점 중심</span>
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
                  : `임무 ${getRobotStatusLabel(robotPosition.missionStatus)}`,
              ].join(' · ')}
          </code>
        </div>
      ) : null}
    </section>
  )
}
