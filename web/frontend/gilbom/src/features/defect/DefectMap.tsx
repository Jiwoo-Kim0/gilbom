import { useEffect, useMemo, useRef, useState } from 'react'
import { loadNaverMapsScript } from '../../lib/naverMaps'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { DefectDetailCard } from './DefectDetailCard'
import { getDefectMap } from './defectApi'
import {
  DEFECT_TYPE_LABELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type {
  DefectFilters,
  DefectMapFeature,
  WorkflowStatus,
} from './defectTypes'

/** 범례는 세 갈래로 접는다. 6개 상태를 그대로 칠하면 지도에서 구분이 안 된다. */
export type DefectStatusTone = 'progress' | 'received' | 'done'

const legendItems: { tone: DefectStatusTone; label: string }[] = [
  { tone: 'received', label: '접수 전·접수' },
  { tone: 'progress', label: '처리 중' },
  { tone: 'done', label: '종결' },
]

/** App.css의 `.defect-marker-box` 크기와 같아야 앵커가 중심에 맞는다. */
const MARKER_BOX_SIZE = 34

function toneOf(status: WorkflowStatus): DefectStatusTone {
  switch (status) {
    case 'IN_PROGRESS':
      return 'progress'
    case 'RESOLVED':
    case 'FALSE_POSITIVE':
    case 'REJECTED':
      return 'done'
    default:
      return 'received'
  }
}

// 구미시청 인근 — 조회 결과가 없을 때의 기본 뷰포트.
const DEFAULT_CENTER = { lat: 36.1195, lng: 128.3446 }
const DEFAULT_ZOOM = 14
/** 지도를 끌 때마다 조회하지 않도록 idle 이후 잠깐 기다린다. */
const VIEWPORT_DEBOUNCE_MS = 300

const naverMapClientId = import.meta.env.VITE_NAVER_MAP_CLIENT_ID

function createMarkerIcon(
  maps: typeof naver.maps,
  tone: DefectStatusTone,
  selected: boolean,
): naver.maps.MarkerIcon {
  // style 속성을 쓰면 Nginx CSP(style-src 'self')가 차단해 마커가 보이지 않는다.
  // 모양은 App.css의 .defect-marker* 클래스에만 둔다.
  const classes = [
    'defect-marker',
    `defect-marker-${tone}`,
    selected ? 'is-selected' : '',
  ]
    .filter(Boolean)
    .join(' ')
  return {
    content: `<div class="defect-marker-box"><span class="${classes}"></span></div>`,
    // .defect-marker-box는 34px 정사각형이므로 중심이 앵커다.
    anchor: new maps.Point(MARKER_BOX_SIZE / 2, MARKER_BOX_SIZE / 2),
  }
}

interface DefectMapProps {
  filters: DefectFilters
  selectedDefectId: string | null
  onSelect: (defectId: string | null) => void
  /** 카드에서 상태를 바꾸면 목록·지도를 다시 읽는다. */
  onChanged: () => void
}

export function DefectMap({
  filters,
  selectedDefectId,
  onSelect,
  onChanged,
}: DefectMapProps) {
  const mapElementRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const [mapStatus, setMapStatus] = useState<'loading' | 'ready' | 'error'>(
    () => (naverMapClientId ? 'loading' : 'error'),
  )
  /** "minLon,minLat,maxLon,maxLat". 첫 조회는 뷰포트 제한 없이 전체를 본다. */
  const [bbox, setBbox] = useState('')
  const [features, setFeatures] = useState<DefectMapFeature[]>([])
  const [error, setError] = useState<string | null>(null)
  /**
   * 결함 위치로 지도를 한 번 맞췄는지. 기본 뷰포트(구미)와 실제 결함 위치가
   * 다를 수 있어, 처음에는 전체를 조회해 그 범위로 이동한다. 이 전에 뷰포트
   * bbox를 적용하면 엉뚱한 범위로 걸러 0건이 되고 지도가 영영 비어 보인다.
   */
  const fittedRef = useRef(false)

  useEffect(() => {
    if (!naverMapClientId) return
    let cancelled = false

    loadNaverMapsScript(naverMapClientId)
      .then(() => {
        if (cancelled || !mapElementRef.current || !window.naver) return
        const { maps } = window.naver
        const map = new maps.Map(mapElementRef.current, {
          center: new maps.LatLng(DEFAULT_CENTER.lat, DEFAULT_CENTER.lng),
          zoom: DEFAULT_ZOOM,
          zoomControl: true,
        })
        mapRef.current = map
        setMapStatus('ready')

        // 지도 이동이 멈출 때만 뷰포트를 갱신한다. 외부 시스템 콜백이라
        // effect 본문에서 동기로 setState 하는 것과는 다르다.
        let timer: number | undefined
        maps.Event.addListener(map, 'idle', () => {
          // 결함 위치로 맞추기 전의 idle은 무시한다. 기본 뷰포트 기준으로
          // bbox를 걸어버리면 첫 조회 결과를 스스로 지운다.
          if (!fittedRef.current) return
          window.clearTimeout(timer)
          timer = window.setTimeout(() => {
            const bounds = map.getBounds()
            const southWest = bounds.getSW()
            const northEast = bounds.getNE()
            // 서버 계약 순서는 minLon,minLat,maxLon,maxLat다.
            setBbox(
              [
                southWest.lng(),
                southWest.lat(),
                northEast.lng(),
                northEast.lat(),
              ]
                .map((value) => value.toFixed(6))
                .join(','),
            )
          }, VIEWPORT_DEBOUNCE_MS)
        })
      })
      .catch(() => {
        if (!cancelled) setMapStatus('error')
      })

    return () => {
      cancelled = true
    }
  }, [])

  // 필터나 뷰포트가 바뀌면 지도용 GeoJSON을 다시 받는다.
  useEffect(() => {
    let cancelled = false

    getDefectMap({ ...filters, bbox })
      .then((response) => {
        if (!cancelled) {
          setFeatures(response.features)
          setError(null)
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setFeatures([])
          setError(getApiErrorMessage(cause))
        }
      })

    return () => {
      cancelled = true
    }
  }, [filters, bbox])

  // 첫 조회 결과가 도착하면 그 결함들이 모두 보이도록 지도를 한 번 맞춘다.
  // 이후의 이동·확대는 사용자 조작을 따르고 뷰포트 bbox로 다시 조회한다.
  useEffect(() => {
    if (mapStatus !== 'ready' || fittedRef.current) return
    if (features.length === 0 || !mapRef.current || !window.naver) return

    const { maps } = window.naver
    const latitudes = features.map((feature) => feature.geometry.coordinates[1])
    const longitudes = features.map((feature) => feature.geometry.coordinates[0])
    // 한 점뿐이면 fitBounds가 과도하게 확대되므로 약간의 여백을 준다.
    const padding = 0.002
    mapRef.current.fitBounds(
      new maps.LatLngBounds(
        new maps.LatLng(
          Math.min(...latitudes) - padding,
          Math.min(...longitudes) - padding,
        ),
        new maps.LatLng(
          Math.max(...latitudes) + padding,
          Math.max(...longitudes) + padding,
        ),
      ),
    )
    fittedRef.current = true
  }, [features, mapStatus])

  // 마커는 조회 결과와 선택 상태에 따라 통째로 다시 만든다.
  useEffect(() => {
    if (mapStatus !== 'ready' || !mapRef.current || !window.naver) return

    const { maps } = window.naver
    const map = mapRef.current
    const markers = features.map((feature) => {
      const [longitude, latitude] = feature.geometry.coordinates
      const {
        defectId,
        incidentCode,
        defectType,
        riskLevel,
        workflowStatus,
      } = feature.properties
      const marker = new maps.Marker({
        position: new maps.LatLng(latitude, longitude),
        map,
        icon: createMarkerIcon(
          maps,
          toneOf(workflowStatus),
          defectId === selectedDefectId,
        ),
        title:
          `${incidentCode} · ${DEFECT_TYPE_LABELS[defectType] ?? defectType}`
          + ` · ${RISK_LEVEL_LABELS[riskLevel] ?? riskLevel}`
          + ` · ${WORKFLOW_STATUS_LABELS[workflowStatus] ?? workflowStatus}`,
        zIndex: defectId === selectedDefectId ? 100 : 1,
      })
      maps.Event.addListener(marker, 'click', () => {
        onSelect(defectId)
      })
      return marker
    })

    if (import.meta.env.DEV) {
      const [first] = features
      // 마커가 안 보일 때 원인을 좁히기 위한 개발 전용 로그다.
      // features는 있는데 마커가 없다면 좌표나 아이콘을 의심한다.
      console.info(
        '[DefectMap] 마커 %d개 생성. 첫 좌표=%o',
        markers.length,
        first ? first.geometry.coordinates : null,
      )
    }

    return () => {
      markers.forEach((marker) => marker.setMap(null))
    }
  }, [features, mapStatus, selectedDefectId, onSelect])

  const countByTone = useMemo(() => {
    const counts: Record<DefectStatusTone, number> = {
      progress: 0,
      received: 0,
      done: 0,
    }
    for (const feature of features) {
      counts[toneOf(feature.properties.workflowStatus)] += 1
    }
    return counts
  }, [features])

  return (
    <article className="content-card defect-map-card" aria-label="결함 지도">
      <div className="defect-map-legend">
        {legendItems.map(({ tone, label }) => (
          <span
            key={tone}
            className={`defect-legend-item defect-legend-item-${tone}`}
          >
            <span className="defect-status-dot" aria-hidden="true" />
            {label} ({mapStatus === 'ready' ? countByTone[tone] : '—'})
          </span>
        ))}
      </div>

      {mapStatus === 'error' ? (
        <div className="defect-map-placeholder map-placeholder empty-state">
          <span className="empty-state-icon" aria-hidden="true">
            MAP
          </span>
          <h3>결함 지도</h3>
          <p>
            Naver Maps 클라이언트 ID를 확인해주세요. (.env의
            VITE_NAVER_MAP_CLIENT_ID)
          </p>
        </div>
      ) : (
        <div className="defect-map-canvas-wrap">
          <div className="defect-map-canvas" ref={mapElementRef} />
          {mapStatus === 'loading' && (
            <p className="defect-map-loading">지도를 불러오는 중입니다…</p>
          )}
          {error !== null && (
            <p className="defect-map-loading" role="alert">
              {error}
            </p>
          )}
          {selectedDefectId !== null && (
            <DefectDetailCard
              key={selectedDefectId}
              defectId={selectedDefectId}
              onClose={() => onSelect(null)}
              onChanged={onChanged}
            />
          )}
        </div>
      )}
    </article>
  )
}
