import {
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
import {
  calculateBoundaryAverageCenter,
  getBoundaryOuterPoints,
  getBoundaryPolygons,
} from './patrolAreaBoundary'
import type {
  GeoJsonCoordinate,
  PatrolArea,
  PatrolAreaBoundary,
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

function PatrolAreaNaverPreview({
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
  const [status, setStatus] = useState<'loading' | 'ready' | 'fallback'>(
    NAVER_MAP_CLIENT_ID ? 'loading' : 'fallback',
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
      return
    }

    const { maps } = window.naver
    const map = mapRef.current
    const polygonObjects = polygons.map((polygon) => (
      new maps.Polygon({
        map,
        paths: polygon.map((ring) => ring.map(
          ([longitude, latitude]) => new maps.LatLng(latitude, longitude),
        )),
        fillColor: '#2f806c',
        fillOpacity: 0.1,
        strokeColor: '#175c4c',
        strokeOpacity: 0.95,
        strokeWeight: 3,
        clickable: false,
        zIndex: 10,
      })
    ))
    const centerPosition = new maps.LatLng(center[1], center[0])
    const centerMarker = new maps.Marker({
      map,
      position: centerPosition,
      title: '순찰 구역 경계점 평균 중심',
      zIndex: 20,
    })

    if (boundaryPoints.length > 1) {
      const longitudes = boundaryPoints.map(([longitude]) => longitude)
      const latitudes = boundaryPoints.map(([, latitude]) => latitude)
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
    } else {
      map.setZoom(17)
    }
    // fitBounds로 계산한 Zoom은 유지하고 사용자가 요청한 점 평균을 중앙에 둔다.
    map.setCenter(centerPosition)

    return () => {
      polygonObjects.forEach((polygon) => polygon.setMap(null))
      centerMarker.setMap(null)
    }
  }, [boundaryPoints, center, polygons, status])

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
}: {
  area: PatrolArea
  center: GeoJsonCoordinate
}) {
  const polygons = getBoundaryPolygons(area.boundary)
  const points = getBoundaryOuterPoints(area.boundary)
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
      </svg>
      <span className="patrol-area-preview-fallback-label">
        Naver Maps를 불러오지 못해 좌표 Layer로 표시 중
      </span>
    </div>
  )
}

export function PatrolAreaSelectionPreview({
  area,
}: {
  area: PatrolArea
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
        fallback={(
          <PatrolAreaFallbackPreview area={area} center={center} />
        )}
      />

      <div className="patrol-area-preview-center-summary">
        <Crosshair size={15} aria-hidden="true" />
        <span>경계점 평균 중심</span>
        <code>
          위도 {center[1].toFixed(7)} · 경도 {center[0].toFixed(7)}
        </code>
      </div>
    </section>
  )
}
