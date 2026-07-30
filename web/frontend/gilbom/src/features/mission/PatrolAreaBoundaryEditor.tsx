import {
  MapPin,
  RotateCcw,
  Undo2,
  X,
} from 'lucide-react'
import {
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { loadNaverMapsScript } from '../../lib/naverMaps'
import { orderBoundaryPointsForPolygon } from './patrolAreaBoundary'
import type { GeoJsonCoordinate } from './missionTypes'
import './patrol-area-boundary-editor.css'

const NAVER_MAP_CLIENT_ID = import.meta.env.VITE_NAVER_MAP_CLIENT_ID
const MAX_BOUNDARY_POINTS = 100
const BOUNDARY_EDITOR_ZOOM = 17
const DEFAULT_CENTER = {
  latitude: 36.1195,
  longitude: 128.3446,
}

type BoundaryEditorStatus = 'loading' | 'ready' | 'fallback'

type PatrolAreaBoundaryEditorProps = {
  points: GeoJsonCoordinate[]
  disabled?: boolean
  onChange: (points: GeoJsonCoordinate[]) => void
  onRequestFallback: () => void
}

type ProjectedBoundaryPoint = {
  x: number
  y: number
}

function roundCoordinate(value: number): number {
  return Number(value.toFixed(7))
}

function projectBoundaryPoints(
  map: naver.maps.Map,
  points: GeoJsonCoordinate[],
): ProjectedBoundaryPoint[] {
  const projection = map.getProjection()
  return points.map(([longitude, latitude]) => {
    const offset = projection.fromCoordToOffset(
      new naver.maps.LatLng(latitude, longitude),
    )
    return {
      x: Number(offset.x.toFixed(2)),
      y: Number(offset.y.toFixed(2)),
    }
  })
}

export function PatrolAreaBoundaryEditor({
  points,
  disabled = false,
  onChange,
  onRequestFallback,
}: PatrolAreaBoundaryEditorProps) {
  const mapElementRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const pointsRef = useRef(points)
  const onChangeRef = useRef(onChange)
  const disabledRef = useRef(disabled)
  const [status, setStatus] = useState<BoundaryEditorStatus>(
    NAVER_MAP_CLIENT_ID ? 'loading' : 'fallback',
  )
  const [projectedPoints, setProjectedPoints] = useState<
    ProjectedBoundaryPoint[]
  >([])
  const orderedPoints = useMemo(
    () => orderBoundaryPointsForPolygon(points),
    [points],
  )
  const projectedPointList = projectedPoints
    .map(({ x, y }) => `${x},${y}`)
    .join(' ')

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
          minZoom: BOUNDARY_EDITOR_ZOOM,
          maxZoom: BOUNDARY_EDITOR_ZOOM,
          pinchZoom: false,
          scrollWheel: false,
          keyboardShortcuts: false,
          disableDoubleClickZoom: true,
          disableDoubleTapZoom: true,
          disableTwoFingerTapZoom: true,
          zoomControl: false,
        })
        mapRef.current = map
        const scheduleProjection = () => {
          if (projectionFrame !== null) {
            return
          }
          projectionFrame = window.requestAnimationFrame(() => {
            projectionFrame = null
            setProjectedPoints(projectBoundaryPoints(
              map,
              orderBoundaryPointsForPolygon(pointsRef.current),
            ))
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
          onChangeRef.current([...currentPoints, nextPoint])
        })
        viewportListeners.push(
          maps.Event.addListener(map, 'bounds_changed', scheduleProjection),
          maps.Event.addListener(map, 'size_changed', scheduleProjection),
        )
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
      mapRef.current?.destroy()
      mapRef.current = null
    }
  }, [])

  useEffect(() => {
    if (status !== 'ready' || mapRef.current === null || !window.naver) {
      setProjectedPoints([])
      return
    }
    setProjectedPoints(projectBoundaryPoints(mapRef.current, orderedPoints))
  }, [orderedPoints, status])

  function removePoint(target: GeoJsonCoordinate) {
    onChange(points.filter(([longitude, latitude]) => (
      longitude !== target[0] || latitude !== target[1]
    )))
  }

  function removeLastPoint() {
    onChange(points.slice(0, -1))
  }

  function resetPoints() {
    onChange([])
  }

  return (
    <section
      className="patrol-boundary-editor"
      aria-labelledby="patrol-boundary-editor-title"
      data-testid="patrol-area-boundary-editor"
    >
      <div className="patrol-boundary-editor-heading">
        <div>
          <MapPin size={18} aria-hidden="true" />
          <div>
            <strong id="patrol-boundary-editor-title">지도에서 구역 경계 지정</strong>
            <p>
              경계점을 3개 이상 아무 순서로 클릭하면 연결 순서를 자동 최적화합니다.
            </p>
          </div>
        </div>
        <span>{points.length}/{MAX_BOUNDARY_POINTS}개 점</span>
      </div>

      {status === 'fallback' ? (
        <div className="patrol-boundary-fallback" role="status">
          <p>
            지도 SDK를 사용할 수 없습니다. 중심 좌표와 가로폭을 직접 입력해주세요.
          </p>
          <button
            className="secondary-button"
            type="button"
            onClick={onRequestFallback}
            disabled={disabled}
          >
            좌표 직접 입력으로 전환
          </button>
        </div>
      ) : (
        <div className="patrol-boundary-map-wrap">
          <div
            className="patrol-boundary-map"
            ref={mapElementRef}
            aria-label="순찰 구역 경계 편집 지도"
            data-testid="patrol-boundary-naver-map"
          />
          {status === 'ready' && projectedPoints.length > 0 ? (
            <svg
              className="patrol-boundary-projected-overlay"
              data-testid="patrol-boundary-projected-overlay"
              aria-hidden="true"
            >
              {projectedPoints.length >= 3 ? (
                <polygon
                  className="patrol-boundary-projected-polygon"
                  points={projectedPointList}
                />
              ) : projectedPoints.length === 2 ? (
                <polyline
                  className="patrol-boundary-projected-line"
                  points={projectedPointList}
                />
              ) : null}
              {projectedPoints.map(({ x, y }, index) => (
                <g key={`${x}-${y}-${index}`}>
                  <circle
                    className="patrol-boundary-projected-marker"
                    cx={x}
                    cy={y}
                    r="14"
                  />
                  <text
                    className="patrol-boundary-projected-label"
                    x={x}
                    y={y}
                  >
                    {index + 1}
                  </text>
                </g>
              ))}
            </svg>
          ) : null}
          {status === 'loading' ? (
            <div className="patrol-boundary-map-loading" role="status">
              <span className="loading-spinner" aria-hidden="true" />
              지도 불러오는 중…
            </div>
          ) : null}
        </div>
      )}

      <div className="patrol-boundary-editor-actions">
        <span>
          {points.length < 3
            ? `경계점을 ${3 - points.length}개 더 지정해주세요.`
            : '교차 없이 자동 정렬된 Polygon입니다.'}
        </span>
        <div>
          <button
            className="secondary-button button-with-icon"
            type="button"
            onClick={removeLastPoint}
            disabled={disabled || points.length === 0}
          >
            <Undo2 size={15} aria-hidden="true" />
            마지막 점 취소
          </button>
          <button
            className="secondary-button button-with-icon"
            type="button"
            onClick={resetPoints}
            disabled={disabled || points.length === 0}
          >
            <RotateCcw size={15} aria-hidden="true" />
            전체 초기화
          </button>
        </div>
      </div>

      {points.length > 0 ? (
        <ol
          className="patrol-boundary-point-list"
          aria-label="자동 정렬된 경계점"
        >
          {orderedPoints.map(([longitude, latitude], index) => (
            <li key={`${longitude}-${latitude}-${index}`}>
              <span>{index + 1}</span>
              <code>
                위도 {latitude.toFixed(7)} · 경도 {longitude.toFixed(7)}
              </code>
              <button
                type="button"
                onClick={() => removePoint([longitude, latitude])}
                disabled={disabled}
                aria-label={`경계점 ${index + 1} 삭제`}
              >
                <X size={15} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ol>
      ) : null}
    </section>
  )
}
