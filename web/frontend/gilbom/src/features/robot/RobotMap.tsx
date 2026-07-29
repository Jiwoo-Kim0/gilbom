import { useEffect, useRef, useState } from 'react'
import { loadNaverMapsScript } from '../../lib/naverMaps'
import type { RobotMarker, RobotStatus } from './robotTypes'

interface RobotMapProps {
  markers?: RobotMarker[]
}

const legendItems: { tone: RobotStatus; label: string }[] = [
  { tone: 'online', label: '온라인' },
  { tone: 'warning', label: '경고' },
  { tone: 'charging', label: '충전 중' },
  { tone: 'offline', label: '오프라인' },
]

const statusColor: Record<RobotStatus, string> = {
  online: '#7a5c0c',
  warning: '#c0392b',
  charging: '#b8863b',
  offline: '#6f6a5e',
}

// 구미시청 인근 — 로봇 위치 API가 연결되기 전까지 사용하는 기본 뷰포트.
const DEFAULT_CENTER = { lat: 36.1195, lng: 128.3446 }
const DEFAULT_ZOOM = 14

const naverMapClientId = import.meta.env.VITE_NAVER_MAP_CLIENT_ID

function createMarkerIcon(
  maps: typeof naver.maps,
  status: RobotStatus,
  headingDeg: number | null,
): naver.maps.MarkerIcon {
  const heading = headingDeg === null || !Number.isFinite(headingDeg)
    ? 0
    : headingDeg
  return {
    content: `<span style="display:grid;width:28px;height:28px;border-radius:50%;place-items:center;border:2px solid #fff;box-shadow:0 2px 6px rgba(23,34,28,0.35);color:#fff;background:${statusColor[status]}"><span style="display:block;font-size:14px;line-height:1;transform:rotate(${heading}deg)">↑</span></span>`,
    size: new maps.Size(28, 28),
    anchor: new maps.Point(14, 14),
  }
}

export function RobotMap({ markers = [] }: RobotMapProps) {
  const mapElementRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const hasCenteredOnRobotRef = useRef(false)
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
          zoomControlOptions: { position: window.naver.maps.Position.TOP_RIGHT },
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

  // 로봇 위치 API 연결 후 markers prop이 갱신될 때마다 마커를 새로 그려 실시간 위치를 반영한다.
  useEffect(() => {
    if (mapStatus !== 'ready' || !mapRef.current || !window.naver) return

    const { maps } = window.naver
    const map = mapRef.current
    if (!hasCenteredOnRobotRef.current && markers.length > 0) {
      map.setCenter(new maps.LatLng(markers[0].lat, markers[0].lng))
      hasCenteredOnRobotRef.current = true
    }
    const markerObjects = markers.map(
      (marker) =>
        new maps.Marker({
          position: new maps.LatLng(marker.lat, marker.lng),
          map,
          icon: createMarkerIcon(maps, marker.status, marker.headingDeg),
          title: `${marker.name} · 방위 ${
            marker.headingDeg === null ? '미수신' : `${marker.headingDeg.toFixed(0)}°`
          }`,
        }),
    )

    return () => {
      markerObjects.forEach((marker) => marker.setMap(null))
    }
  }, [markers, mapStatus])

  const countByStatus = (tone: RobotStatus) =>
    markers.filter((marker) => marker.status === tone).length
  const alertCount = countByStatus('warning')
  const activeCount = markers.filter((marker) => marker.status !== 'offline').length

  return (
    <article className="content-card robot-map-card" aria-label="로봇 위치 지도">
      <div className="robot-map-legend">
        {legendItems.map(({ tone, label }) => (
          <span key={tone} className={`robot-legend-item robot-legend-item-${tone}`}>
            <span className="robot-status-dot" aria-hidden="true" />
            {label} ({mapStatus === 'ready' ? countByStatus(tone) : '—'})
          </span>
        ))}
      </div>

      {mapStatus === 'error' ? (
        <div className="robot-map-placeholder map-placeholder empty-state">
          <span className="empty-state-icon" aria-hidden="true">
            MAP
          </span>
          <h3>로봇 위치 지도</h3>
          <p>Naver Maps 클라이언트 ID를 확인해주세요. (.env의 VITE_NAVER_MAP_CLIENT_ID)</p>
        </div>
      ) : (
        <div className="robot-map-canvas-wrap">
          <div className="robot-map-canvas" ref={mapElementRef} />
          {mapStatus === 'ready' && (
            <div className="robot-map-overlay" aria-live="polite">
              <span className="robot-map-overlay-live">
                <span className="robot-map-overlay-dot" aria-hidden="true" />
                라이브 함대 데이터
              </span>
              <span className="robot-map-overlay-stat">
                활성 로봇<strong>{activeCount}</strong>
              </span>
              <span className="robot-map-overlay-stat">
                경고 알림<strong>{alertCount}</strong>
              </span>
            </div>
          )}
          {mapStatus === 'loading' && (
            <p className="robot-map-loading">지도를 불러오는 중입니다…</p>
          )}
        </div>
      )}
    </article>
  )
}
