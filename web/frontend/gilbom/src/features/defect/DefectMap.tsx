import { useEffect, useRef, useState } from 'react'
import { loadNaverMapsScript } from '../../lib/naverMaps'

export type DefectStatus = 'progress' | 'received' | 'done'

export interface DefectMarker {
  id: string
  lat: number
  lng: number
  status: DefectStatus
}

interface DefectMapProps {
  markers?: DefectMarker[]
}

const legendItems: { tone: DefectStatus; label: string }[] = [
  { tone: 'progress', label: '진행 중' },
  { tone: 'received', label: '접수됨' },
  { tone: 'done', label: '완료' },
]

const statusColor: Record<DefectStatus, string> = {
  progress: '#b8863b',
  received: '#6f6a5e',
  done: '#7a5c0c',
}

// 구미시청 인근 — 결함 좌표 API가 연결되기 전까지 사용하는 기본 뷰포트.
const DEFAULT_CENTER = { lat: 36.1195, lng: 128.3446 }
const DEFAULT_ZOOM = 14

const naverMapClientId = import.meta.env.VITE_NAVER_MAP_CLIENT_ID

function createMarkerIcon(maps: typeof naver.maps, status: DefectStatus): naver.maps.MarkerIcon {
  return {
    content: `<span style="display:block;width:16px;height:16px;border-radius:50% 50% 50% 0;transform:rotate(-45deg);border:2px solid #fff;box-shadow:0 2px 6px rgba(23,34,28,0.35);background:${statusColor[status]}"></span>`,
    size: new maps.Size(16, 16),
    anchor: new maps.Point(8, 16),
  }
}

export function DefectMap({ markers = [] }: DefectMapProps) {
  const mapElementRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const [mapStatus, setMapStatus] = useState<'loading' | 'ready' | 'error'>(() =>
    naverMapClientId ? 'loading' : 'error',
  )

  useEffect(() => {
    if (!naverMapClientId) return

    let cancelled = false

    loadNaverMapsScript(naverMapClientId)
      .then(() => {
        if (cancelled || !mapElementRef.current || !window.naver) return
        mapRef.current = new window.naver.maps.Map(mapElementRef.current, {
          center: new window.naver.maps.LatLng(DEFAULT_CENTER.lat, DEFAULT_CENTER.lng),
          zoom: DEFAULT_ZOOM,
          zoomControl: true,
        })
        setMapStatus('ready')
      })
      .catch(() => {
        if (!cancelled) setMapStatus('error')
      })

    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (mapStatus !== 'ready' || !mapRef.current || !window.naver) return

    const { maps } = window.naver
    const map = mapRef.current
    const markerObjects = markers.map(
      (marker) =>
        new maps.Marker({
          position: new maps.LatLng(marker.lat, marker.lng),
          map,
          icon: createMarkerIcon(maps, marker.status),
          title: marker.id,
        }),
    )

    return () => {
      markerObjects.forEach((marker) => marker.setMap(null))
    }
  }, [markers, mapStatus])

  const countByStatus = (tone: DefectStatus) =>
    markers.filter((marker) => marker.status === tone).length

  return (
    <article className="content-card defect-map-card" aria-label="결함 지도">
      <div className="defect-map-legend">
        {legendItems.map(({ tone, label }) => (
          <span key={tone} className={`defect-legend-item defect-legend-item-${tone}`}>
            <span className="defect-status-dot" aria-hidden="true" />
            {label} ({mapStatus === 'ready' ? countByStatus(tone) : '—'})
          </span>
        ))}
      </div>

      {mapStatus === 'error' ? (
        <div className="defect-map-placeholder map-placeholder empty-state">
          <span className="empty-state-icon" aria-hidden="true">
            MAP
          </span>
          <h3>결함 지도</h3>
          <p>Naver Maps 클라이언트 ID를 확인해주세요. (.env의 VITE_NAVER_MAP_CLIENT_ID)</p>
        </div>
      ) : (
        <div className="defect-map-canvas-wrap">
          <div className="defect-map-canvas" ref={mapElementRef} />
          {mapStatus === 'loading' && (
            <p className="defect-map-loading">지도를 불러오는 중입니다…</p>
          )}
        </div>
      )}
    </article>
  )
}
