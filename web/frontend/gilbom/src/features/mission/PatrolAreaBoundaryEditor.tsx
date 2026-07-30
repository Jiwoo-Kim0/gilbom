import {
  MapPin,
  RotateCcw,
  Undo2,
  X,
} from 'lucide-react'
import {
  useEffect,
  useRef,
  useState,
} from 'react'
import { loadNaverMapsScript } from '../../lib/naverMaps'
import type { GeoJsonCoordinate } from './missionTypes'
import './patrol-area-boundary-editor.css'

const NAVER_MAP_CLIENT_ID = import.meta.env.VITE_NAVER_MAP_CLIENT_ID
const MAX_BOUNDARY_POINTS = 100
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

function roundCoordinate(value: number): number {
  return Number(value.toFixed(7))
}

function pointMarkerIcon(
  maps: typeof naver.maps,
  pointNumber: number,
): naver.maps.MarkerIcon {
  return {
    content: `<span style="display:grid;width:28px;height:28px;border:3px solid #fff;border-radius:50%;place-items:center;color:#fff;background:#175c4c;box-shadow:0 3px 10px rgba(15,23,42,.3);font-size:12px;font-weight:800">${pointNumber}</span>`,
    size: new maps.Size(28, 28),
    anchor: new maps.Point(14, 14),
  }
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
          zoom: 17,
          zoomControl: true,
          zoomControlOptions: {
            position: maps.Position.TOP_RIGHT,
          },
        })
        mapRef.current = map
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
          const previousPoint = currentPoints.at(-1)
          if (
            previousPoint?.[0] === nextPoint[0]
            && previousPoint[1] === nextPoint[1]
          ) {
            return
          }
          onChangeRef.current([...currentPoints, nextPoint])
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
      if (clickListener !== null && window.naver) {
        window.naver.maps.Event.removeListener(clickListener)
      }
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
    const path = points.map(([longitude, latitude]) => (
      new maps.LatLng(latitude, longitude)
    ))
    const polygon = path.length >= 3
      ? new maps.Polygon({
        map,
        paths: path,
        fillColor: '#2f806c',
        fillOpacity: 0.2,
        strokeColor: '#175c4c',
        strokeOpacity: 0.95,
        strokeWeight: 3,
        clickable: false,
        zIndex: 10,
      })
      : null
    const polyline = path.length >= 2 && path.length < 3
      ? new maps.Polyline({
        map,
        path,
        strokeColor: '#175c4c',
        strokeOpacity: 0.95,
        strokeWeight: 3,
        strokeStyle: 'solid',
        clickable: false,
        zIndex: 10,
      })
      : null
    const markers = path.map((position, index) => new maps.Marker({
      map,
      position,
      icon: pointMarkerIcon(maps, index + 1),
      title: `순찰 구역 경계점 ${index + 1}`,
      zIndex: 20,
    }))

    if (path.length === 1) {
      map.setCenter(path[0])
      map.setZoom(18)
    } else if (path.length > 1) {
      const longitudes = points.map(([longitude]) => longitude)
      const latitudes = points.map(([, latitude]) => latitude)
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
      polygon?.setMap(null)
      polyline?.setMap(null)
      markers.forEach((marker) => marker.setMap(null))
    }
  }, [points, status])

  function removePoint(index: number) {
    onChange(points.filter((_, pointIndex) => pointIndex !== index))
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
            <p>경계점을 순서대로 3개 이상 클릭하면 Polygon이 만들어집니다.</p>
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
            : '등록 가능한 Polygon입니다.'}
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
        <ol className="patrol-boundary-point-list" aria-label="선택된 경계점">
          {points.map(([longitude, latitude], index) => (
            <li key={`${longitude}-${latitude}-${index}`}>
              <span>{index + 1}</span>
              <code>
                위도 {latitude.toFixed(7)} · 경도 {longitude.toFixed(7)}
              </code>
              <button
                type="button"
                onClick={() => removePoint(index)}
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
