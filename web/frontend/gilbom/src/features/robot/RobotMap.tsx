import { useEffect, useRef, useState } from 'react'
import carMarkerImage from '../../assets/car.png'
import { loadNaverMapsScript } from '../../lib/naverMaps'
import type { RobotMarker, RobotStatus, RobotSummary } from './robotTypes'

interface RobotMapProps {
  markers?: RobotMarker[]
  summary?: RobotSummary | null
}

const legendItems: { tone: 'total' | RobotStatus; label: string }[] = [
  { tone: 'total', label: '전체' },
  { tone: 'online', label: '온라인' },
  { tone: 'offline', label: '오프라인' },
  { tone: 'warning', label: '경고' },
]

// 구미시청 인근 — 로봇 위치 API가 연결되기 전까지 사용하는 기본 뷰포트.
const DEFAULT_CENTER = { lat: 36.1195, lng: 128.3446 }
const DEFAULT_ZOOM = 14

const naverMapClientId = import.meta.env.VITE_NAVER_MAP_CLIENT_ID

function headingIndex(headingDeg: number | null): number {
  if (headingDeg === null || !Number.isFinite(headingDeg)) {
    return 0
  }
  const normalized = ((headingDeg % 360) + 360) % 360
  return Math.round(normalized / 45) % 8
}

function createMarkerIcon(
  maps: typeof naver.maps,
  status: RobotStatus,
  headingDeg: number | null,
): naver.maps.MarkerIcon {
  const direction = headingIndex(headingDeg)
  return {
    content:
      `<span class="robot-map-marker robot-map-marker-${status}">`
      + `<img class="robot-map-marker-car robot-map-marker-heading-${direction}" `
      + `src="${carMarkerImage}" alt="">`
      + '<span class="robot-map-marker-status" aria-hidden="true"></span>'
      + '</span>',
    size: new maps.Size(44, 44),
    anchor: new maps.Point(22, 22),
  }
}

export function RobotMap({ markers = [], summary = null }: RobotMapProps) {
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

  const statusCounts: Record<'total' | RobotStatus, number> = {
    total: summary?.totalRobots ?? markers.length,
    online: summary?.onlineRobots
      ?? markers.filter((marker) => marker.connectivityStatus === 'ONLINE').length,
    offline: summary?.offlineRobots
      ?? markers.filter((marker) => marker.connectivityStatus === 'OFFLINE').length,
    warning: summary === null
      ? markers.filter((marker) => marker.status === 'warning').length
      : summary.warningRobots + summary.criticalRobots,
  }
  const statusCountReady = summary !== null || mapStatus === 'ready'

  return (
    <article className="content-card robot-map-card" aria-label="로봇 위치 지도">
      <div className="robot-map-legend">
        {legendItems.map(({ tone, label }) => (
          <span key={tone} className={`robot-legend-item robot-legend-item-${tone}`}>
            <span className="robot-status-dot" aria-hidden="true" />
            {label} ({statusCountReady ? statusCounts[tone] : '—'})
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
          {mapStatus === 'loading' && (
            <p className="robot-map-loading">지도를 불러오는 중입니다…</p>
          )}
        </div>
      )}
    </article>
  )
}
