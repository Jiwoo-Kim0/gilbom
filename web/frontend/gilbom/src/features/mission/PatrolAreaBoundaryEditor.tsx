import {
  MapPin,
  X,
} from 'lucide-react'
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { loadNaverMapsScript } from '../../lib/naverMaps'
import { orderBoundaryPointsForPolygon } from './patrolAreaBoundary'
import type { GeoJsonCoordinate } from './missionTypes'
import './patrol-area-boundary-editor.css'

const NAVER_MAP_CLIENT_ID = import.meta.env.VITE_NAVER_MAP_CLIENT_ID
const MAX_BOUNDARY_POINTS = 100
const BOUNDARY_EDITOR_ZOOM = 17
const BOUNDARY_EDITOR_MIN_ZOOM = 10
const BOUNDARY_EDITOR_MAX_ZOOM = 21
const DEFAULT_CENTER = {
  latitude: 36.1195,
  longitude: 128.3446,
}

type BoundaryEditorStatus = 'loading' | 'ready' | 'fallback'

type PatrolAreaBoundaryEditorProps = {
  points: GeoJsonCoordinate[]
  disabled?: boolean
  onChange: (points: GeoJsonCoordinate[]) => void
  actions?: ReactNode
}

type PatrolAreaBoundaryOverlay = naver.maps.OverlayView & {
  setPoints(points: GeoJsonCoordinate[]): void
}

function roundCoordinate(value: number): number {
  return Number(value.toFixed(7))
}

function createPatrolAreaBoundaryOverlay(
  maps: typeof naver.maps,
  mapElement: HTMLElement,
  initialPoints: GeoJsonCoordinate[],
): PatrolAreaBoundaryOverlay {
  class MapAttachedBoundaryOverlay extends maps.OverlayView {
    private points = initialPoints
    private readonly element = document.createElementNS(
      'http://www.w3.org/2000/svg',
      'svg',
    )
    private shape: SVGPolygonElement | SVGPolylineElement | null = null
    private pointGroups: SVGGElement[] = []
    private pointCircles: SVGCircleElement[] = []
    private pointLabels: SVGTextElement[] = []

    constructor() {
      super()
      this.element.classList.add('patrol-boundary-projected-overlay')
      this.element.setAttribute('overflow', 'visible')
      this.element.setAttribute('aria-hidden', 'true')
      this.element.setAttribute(
        'data-testid',
        'patrol-boundary-projected-overlay',
      )
      this.syncElements()
    }

    setPoints(nextPoints: GeoJsonCoordinate[]) {
      this.points = nextPoints
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

      const projectedPoints = this.points.map(([longitude, latitude]) => {
        const offset = projection.fromCoordToOffset(
          new maps.LatLng(latitude, longitude),
        )
        return {
          x: Number(offset.x.toFixed(2)),
          y: Number(offset.y.toFixed(2)),
        }
      })
      this.shape?.setAttribute(
        'points',
        projectedPoints.map(({ x, y }) => `${x},${y}`).join(' '),
      )
      projectedPoints.forEach(({ x, y }, index) => {
        const [longitude, latitude] = this.points[index]
        this.pointGroups[index].setAttribute(
          'data-longitude',
          String(longitude),
        )
        this.pointGroups[index].setAttribute(
          'data-latitude',
          String(latitude),
        )
        this.pointCircles[index].setAttribute('cx', String(x))
        this.pointCircles[index].setAttribute('cy', String(y))
        this.pointLabels[index].setAttribute('x', String(x))
        this.pointLabels[index].setAttribute('y', String(y))
        this.pointLabels[index].textContent = String(index + 1)
      })
    }

    onRemove() {
      this.element.remove()
    }

    private syncElements() {
      const requiredShape = this.points.length >= 3
        ? 'polygon'
        : this.points.length === 2
          ? 'polyline'
          : null
      if (
        requiredShape === null
        || this.shape?.tagName.toLowerCase() !== requiredShape
      ) {
        this.shape?.remove()
        this.shape = null
      }
      if (requiredShape !== null && this.shape === null) {
        this.shape = document.createElementNS(
          'http://www.w3.org/2000/svg',
          requiredShape,
        )
        this.shape.classList.add(
          requiredShape === 'polygon'
            ? 'patrol-boundary-projected-polygon'
            : 'patrol-boundary-projected-line',
        )
      }

      while (this.pointGroups.length < this.points.length) {
        const group = document.createElementNS(
          'http://www.w3.org/2000/svg',
          'g',
        )
        group.classList.add('patrol-boundary-projected-point')
        const circle = document.createElementNS(
          'http://www.w3.org/2000/svg',
          'circle',
        )
        circle.classList.add('patrol-boundary-projected-marker')
        circle.setAttribute('r', '14')
        const label = document.createElementNS(
          'http://www.w3.org/2000/svg',
          'text',
        )
        label.classList.add('patrol-boundary-projected-label')
        group.append(circle, label)
        this.pointGroups.push(group)
        this.pointCircles.push(circle)
        this.pointLabels.push(label)
      }
      this.pointGroups.splice(this.points.length).forEach((group) => {
        group.remove()
      })
      this.pointCircles.splice(this.points.length)
      this.pointLabels.splice(this.points.length)
      this.element.replaceChildren(
        ...(this.shape === null ? [] : [this.shape]),
        ...this.pointGroups,
      )
    }
  }

  return new MapAttachedBoundaryOverlay()
}

export function PatrolAreaBoundaryEditor({
  points,
  disabled = false,
  onChange,
  actions,
}: PatrolAreaBoundaryEditorProps) {
  const mapElementRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const boundaryOverlayRef = useRef<PatrolAreaBoundaryOverlay | null>(null)
  const pointsRef = useRef(points)
  const onChangeRef = useRef(onChange)
  const disabledRef = useRef(disabled)
  const [status, setStatus] = useState<BoundaryEditorStatus>(
    NAVER_MAP_CLIENT_ID ? 'loading' : 'fallback',
  )
  const orderedPoints = useMemo(
    () => orderBoundaryPointsForPolygon(points),
    [points],
  )

  useEffect(() => {
    pointsRef.current = points
  }, [points])

  useEffect(() => {
    onChangeRef.current = onChange
  }, [onChange])

  useEffect(() => {
    disabledRef.current = disabled
  }, [disabled])

  useEffect(() => {
    if (!NAVER_MAP_CLIENT_ID) {
      return
    }

    let cancelled = false
    let clickListener: naver.maps.MapEventListener | null = null
    const viewportListeners: naver.maps.MapEventListener[] = []
    let projectionFrame: number | null = null

    loadNaverMapsScript(NAVER_MAP_CLIENT_ID)
      .then(() => {
        if (cancelled || mapElementRef.current === null || !window.naver) {
          return
        }
        const { maps } = window.naver
        const map = new maps.Map(mapElementRef.current, {
          center: new maps.LatLng(
            DEFAULT_CENTER.latitude,
            DEFAULT_CENTER.longitude,
          ),
          zoom: BOUNDARY_EDITOR_ZOOM,
          minZoom: BOUNDARY_EDITOR_MIN_ZOOM,
          maxZoom: BOUNDARY_EDITOR_MAX_ZOOM,
          pinchZoom: false,
          scrollWheel: false,
          keyboardShortcuts: false,
          disableDoubleClickZoom: true,
          disableDoubleTapZoom: true,
          disableTwoFingerTapZoom: true,
          zoomControl: true,
          zoomControlOptions: {
            position: maps.Position.TOP_RIGHT,
            style: maps.ZoomControlStyle.SMALL,
          },
        })
        mapRef.current = map
        boundaryOverlayRef.current = createPatrolAreaBoundaryOverlay(
          maps,
          mapElementRef.current,
          orderBoundaryPointsForPolygon(pointsRef.current),
        )
        boundaryOverlayRef.current.setMap(map)
        const scheduleProjection = () => {
          if (projectionFrame !== null) {
            return
          }
          projectionFrame = window.requestAnimationFrame(() => {
            projectionFrame = null
            boundaryOverlayRef.current?.draw()
          })
        }
        clickListener = maps.Event.addListener(map, 'click', (event) => {
          const currentPoints = pointsRef.current
          if (
            disabledRef.current
            || currentPoints.length >= MAX_BOUNDARY_POINTS
          ) {
            return
          }

          const nextPoint: GeoJsonCoordinate = [
            roundCoordinate(event.coord.lng()),
            roundCoordinate(event.coord.lat()),
          ]
          if (currentPoints.some(([longitude, latitude]) => (
            longitude === nextPoint[0] && latitude === nextPoint[1]
          ))) {
            return
          }
          const nextPoints = [...currentPoints, nextPoint]
          pointsRef.current = nextPoints
          boundaryOverlayRef.current?.setPoints(
            orderBoundaryPointsForPolygon(nextPoints),
          )
          onChangeRef.current(nextPoints)
        })
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
          maps.Event.addListener(map, eventName, scheduleProjection)
        )))
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) {
          setStatus('fallback')
        }
      })

    return () => {
      cancelled = true
      if (clickListener !== null && window.naver) {
        window.naver.maps.Event.removeListener(clickListener)
      }
      if (window.naver) {
        viewportListeners.forEach((listener) => {
          window.naver?.maps.Event.removeListener(listener)
        })
      }
      if (projectionFrame !== null) {
        window.cancelAnimationFrame(projectionFrame)
      }
      boundaryOverlayRef.current?.setMap(null)
      boundaryOverlayRef.current = null
      mapRef.current?.destroy()
      mapRef.current = null
    }
  }, [])

  useEffect(() => {
    if (status !== 'ready') {
      return
    }
    boundaryOverlayRef.current?.setPoints(orderedPoints)
  }, [orderedPoints, status])

  function removePoint(target: GeoJsonCoordinate) {
    onChange(points.filter(([longitude, latitude]) => (
      longitude !== target[0] || latitude !== target[1]
    )))
  }

  return (
    <section
      className="patrol-boundary-editor"
      aria-labelledby="patrol-boundary-editor-title"
      data-testid="patrol-area-boundary-editor"
    >
      <div className="patrol-boundary-editor-heading">
        <div className="patrol-boundary-editor-heading-primary">
          <div className="patrol-boundary-editor-title-row">
            <MapPin size={18} aria-hidden="true" />
            <strong id="patrol-boundary-editor-title">지도에서 구역 경계 지정</strong>
          </div>
          <span className="patrol-boundary-point-count">
            {points.length}/{MAX_BOUNDARY_POINTS} 개의 점
          </span>
        </div>
        <div className="patrol-boundary-editor-heading-tools">
          {actions}
        </div>
      </div>

      <div className="patrol-boundary-editor-body">
        {status === 'fallback' ? (
          <div className="patrol-boundary-fallback" role="alert">
            <p>
              지도 SDK를 사용할 수 없어 경계를 지정할 수 없습니다. 잠시 후 다시
              시도하거나 지도 API 설정을 확인해주세요.
            </p>
          </div>
        ) : (
          <div className="patrol-boundary-map-wrap">
            <div
              className="patrol-boundary-map"
              ref={mapElementRef}
              aria-label="순찰 구역 경계 편집 지도"
              data-testid="patrol-boundary-naver-map"
            />
            {status === 'loading' ? (
              <div className="patrol-boundary-map-loading" role="status">
                <span className="loading-spinner" aria-hidden="true" />
                지도 불러오는 중…
              </div>
            ) : null}
          </div>
        )}

        <aside className="patrol-boundary-point-panel" aria-label="경계점 목록">
          {points.length < 3 ? (
            <p className="patrol-boundary-editor-status" role="status">
              경계점을 {3 - points.length}개 더 지정해주세요.
            </p>
          ) : null}

          {points.length > 0 ? (
            <ol
              className="patrol-boundary-point-list"
              aria-label="자동 정렬된 경계점"
            >
              {orderedPoints.map(([longitude, latitude], index) => (
                <li key={`${longitude}-${latitude}-${index}`}>
                  <button
                    type="button"
                    onClick={() => removePoint([longitude, latitude])}
                    disabled={disabled}
                    aria-label={`경계점 ${index + 1} 삭제`}
                  >
                    <span aria-hidden="true">{index + 1}</span>
                    <X size={15} aria-hidden="true" />
                  </button>
                </li>
              ))}
            </ol>
          ) : null}
        </aside>
      </div>
    </section>
  )
}
