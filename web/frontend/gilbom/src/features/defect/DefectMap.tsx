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

/**
 * 화면상 이 픽셀 안에 들어오는 마커들을 한 덩어리로 묶는다.
 * 마커 상자가 34px이므로 그보다 조금 큰 값이라야 겹침이 실제로 사라진다.
 */
const CLUSTER_CELL_PX = 44

type MarkerCluster = {
  key: string
  lat: number
  lng: number
  features: DefectMapFeature[]
}

/**
 * 화면 좌표 격자로 마커를 묶는다.
 *
 * <p>네이버가 제공하는 MarkerClustering은 SDK 본체가 아니라 별도 스크립트라서,
 * `script-src`에 새 출처를 열어야 한다. 시연 앞두고 CSP를 건드릴 이유가 없고
 * 우리가 필요한 동작은 격자 묶기로 충분해서 직접 만들었다.
 *
 * <p>격자를 위경도가 아니라 **화면 픽셀**로 나누는 것이 요점이다. 위경도로 나누면
 * 확대할 때마다 셀 크기를 다시 정해야 하고, 위도에 따라 셀 모양이 찌그러진다.
 */
function clusterFeatures(
  features: DefectMapFeature[],
  projection: naver.maps.MapSystemProjection,
  maps: typeof naver.maps,
): MarkerCluster[] {
  const cells = new Map<string, MarkerCluster>()

  for (const feature of features) {
    const [longitude, latitude] = feature.geometry.coordinates
    const offset = projection.fromCoordToOffset(
      new maps.LatLng(latitude, longitude),
    )
    const key = `${Math.floor(offset.x / CLUSTER_CELL_PX)}:`
      + `${Math.floor(offset.y / CLUSTER_CELL_PX)}`
    const cell = cells.get(key)
    if (cell === undefined) {
      cells.set(key, { key, lat: latitude, lng: longitude, features: [feature] })
      continue
    }
    cell.features.push(feature)
  }

  // 묶인 덩어리는 구성원의 평균 위치에 놓는다. 첫 마커 자리에 두면 덩어리가
  // 한쪽으로 치우쳐 보인다.
  return [...cells.values()].map((cell) => {
    if (cell.features.length === 1) {
      return cell
    }
    const count = cell.features.length
    return {
      ...cell,
      lat: cell.features.reduce(
        (sum, feature) => sum + feature.geometry.coordinates[1], 0) / count,
      lng: cell.features.reduce(
        (sum, feature) => sum + feature.geometry.coordinates[0], 0) / count,
    }
  })
}

/**
 * 묶인 마커의 아이콘. 개수를 숫자로 보여주고, 안에 든 결함 중 가장 급한 상태를
 * 색으로 쓴다. 처리 중인 결함이 섞여 있는데 종결 색으로 보이면 안 된다.
 */
function createClusterIcon(
  maps: typeof naver.maps,
  cluster: MarkerCluster,
): naver.maps.MarkerIcon {
  const tones = cluster.features.map(
    (feature) => toneOf(feature.properties.workflowStatus),
  )
  const tone: DefectStatusTone = tones.includes('received')
    ? 'received'
    : (tones.includes('progress') ? 'progress' : 'done')
  // style 속성은 CSP(style-src 'self')에 막힌다. 모양은 App.css에만 둔다.
  return {
    content:
      `<div class="defect-cluster defect-cluster-${tone}">`
      + `<span>${cluster.features.length}</span>`
      + '</div>',
    size: new maps.Size(CLUSTER_CELL_PX, CLUSTER_CELL_PX),
    anchor: new maps.Point(CLUSTER_CELL_PX / 2, CLUSTER_CELL_PX / 2),
  }
}

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
/** 축척 바가 차지할 최대 너비. 이 안에 들어가는 가장 큰 "깔끔한" 거리를 고른다. */
const SCALE_MAX_WIDTH_PX = 96
/** 1·2·5 계열만 쓴다. 33m 같은 값은 눈대중에 쓸모가 없다. */
const SCALE_STEPS_M = [
  1, 2, 5, 10, 20, 50, 100, 200, 500,
  1000, 2000, 5000, 10000, 20000, 50000,
]

type MapScale = { label: string; widthPx: number }

/**
 * 화면 1px이 몇 m인지로 축척 바를 만든다.
 *
 * <p>네이버 SDK의 축척 컨트롤을 쓰지 않는 이유는 CSP다. `style-src 'self'`가 네이버
 * 스타일시트를 통째로 막아 여백·배경이 사라지고, 어느 모서리에 두든 지도 컨테이너의
 * `overflow: hidden`에 잘렸다. 우리 CSS로 그리면 그 문제가 없다.
 */
function computeScale(
  bounds: naver.maps.LatLngBounds,
  widthPx: number,
): MapScale | null {
  if (widthPx <= 0) {
    return null
  }
  const southWest = bounds.getSW()
  const northEast = bounds.getNE()
  const centerLat = (southWest.lat() + northEast.lat()) / 2
  // 경도 1도의 거리는 위도에 따라 줄어든다. 적도 기준 값에 cos을 곱해 보정한다.
  const metersPerDegree = 111_320 * Math.cos((centerLat * Math.PI) / 180)
  const spanMeters = Math.abs(northEast.lng() - southWest.lng())
    * metersPerDegree
  if (!Number.isFinite(spanMeters) || spanMeters <= 0) {
    return null
  }

  const metersPerPixel = spanMeters / widthPx
  const step = [...SCALE_STEPS_M]
    .reverse()
    .find((value) => value / metersPerPixel <= SCALE_MAX_WIDTH_PX)
  if (step === undefined) {
    return null
  }
  return {
    label: step >= 1000 ? `${step / 1000}km` : `${step}m`,
    widthPx: Math.round(step / metersPerPixel),
  }
}

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
  const [scale, setScale] = useState<MapScale | null>(null)
  // 확대·이동이 끝나면 화면 좌표가 달라져 마커 묶음을 다시 계산해야 한다.
  const [idleToken, setIdleToken] = useState(0)
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
        // CSP가 style-src 'self'라 SDK가 자기 컨트롤에 입히는 인라인 스타일이
        // 막힌다. 스킨을 되살리려면 'unsafe-inline'이 필요한데 그건 보안 판단이라,
        // 대신 눈에 덜 띄고 지도를 가리지 않는 배치로 줄인다.
        //
        //   - 기본 LARGE 줌 컨트롤은 왼쪽 위에서 마커를 가린다 -> SMALL + 오른쪽 위
        //   - 축척 바는 어느 모서리에 둬도 잘려서 껐다. 대신 직접 그린다
        //
        // SDK 버전에 따라 ZoomControlStyle이 없을 수 있다. 그때는 style을 넘기지
        // 않아 기본값으로 두고, 위치 지정만 살린다.
        const map = new maps.Map(mapElementRef.current, {
          center: new maps.LatLng(DEFAULT_CENTER.lat, DEFAULT_CENTER.lng),
          zoom: DEFAULT_ZOOM,
          zoomControl: true,
          zoomControlOptions: {
            position: maps.Position?.TOP_RIGHT,
            style: maps.ZoomControlStyle?.SMALL,
          },
          // 축척은 우리가 직접 그린다. computeScale 주석 참고.
          scaleControl: false,
        })
        mapRef.current = map
        setMapStatus('ready')

        // 축척은 조회를 유발하지 않으므로 아래 fittedRef 가드와 debounce를 타지
        // 않는다. 가드는 첫 조회 결과를 지우지 않으려고 bbox에만 거는 것이다.
        const refreshScale = () => {
          const next = computeScale(
            map.getBounds(),
            mapElementRef.current?.clientWidth ?? 0,
          )
          // 같은 값이면 렌더를 만들지 않는다. bounds_changed는 드래그 중 자주 온다.
          setScale((previous) =>
            previous?.label === next?.label
            && previous?.widthPx === next?.widthPx
              ? previous
              : next,
          )
        }
        // fitBounds가 뷰포트를 바꾸지 않으면 idle이 한 번도 오지 않는다.
        // 그때도 축척은 보여야 하므로 여기서 한 번 계산해 둔다.
        refreshScale()
        maps.Event.addListener(map, 'bounds_changed', refreshScale)

        // 지도 이동이 멈출 때만 뷰포트를 갱신한다. 외부 시스템 콜백이라
        // effect 본문에서 동기로 setState 하는 것과는 다르다.
        let timer: number | undefined
        maps.Event.addListener(map, 'idle', () => {
          // 결함 위치로 맞추기 전의 idle은 무시한다. 기본 뷰포트 기준으로
          // bbox를 걸어버리면 첫 조회 결과를 스스로 지운다.
          // 묶음은 화면 좌표 기준이라 bbox 재조회와 무관하게 다시 계산해야 한다.
          setIdleToken((token) => token + 1)
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
    // 선택한 결함은 묶음에 숨기지 않는다. 카드를 열어둔 채 지도를 움직였을 때
    // 그 결함이 사라지면 어디를 보고 있는지 알 수 없다.
    const selected = features.filter(
      (feature) => feature.properties.defectId === selectedDefectId,
    )
    const clusters = clusterFeatures(
      features.filter(
        (feature) => feature.properties.defectId !== selectedDefectId,
      ),
      map.getProjection(),
      maps,
    )

    const markers = [
      ...selected.map((feature) => ({
        key: feature.properties.defectId,
        lat: feature.geometry.coordinates[1],
        lng: feature.geometry.coordinates[0],
        features: [feature],
      })),
      ...clusters,
    ].map((cluster) => {
      const grouped = cluster.features.length > 1
      const [feature] = cluster.features
      const {
        defectId,
        incidentCode,
        defectType,
        riskLevel,
        workflowStatus,
      } = feature.properties
      const marker = new maps.Marker({
        position: new maps.LatLng(cluster.lat, cluster.lng),
        map,
        icon: grouped
          ? createClusterIcon(maps, cluster)
          : createMarkerIcon(
              maps,
              toneOf(workflowStatus),
              defectId === selectedDefectId,
            ),
        title: grouped
          ? `결함 ${cluster.features.length}건 — 확대하면 나뉩니다`
          : `${incidentCode} · ${DEFECT_TYPE_LABELS[defectType] ?? defectType}`
            + ` · ${RISK_LEVEL_LABELS[riskLevel] ?? riskLevel}`
            + ` · ${WORKFLOW_STATUS_LABELS[workflowStatus] ?? workflowStatus}`,
        zIndex: defectId === selectedDefectId ? 100 : (grouped ? 50 : 1),
      })
      maps.Event.addListener(marker, 'click', () => {
        if (!grouped) {
          onSelect(defectId)
          return
        }
        // 묶음을 클릭하면 그 자리로 확대한다. 두 단계면 대개 나뉜다.
        map.setCenter(new maps.LatLng(cluster.lat, cluster.lng))
        map.setZoom(Math.min(map.getZoom() + 2, 21))
      })
      return marker
    })

    if (import.meta.env.DEV) {
      const [first] = features
      // 마커가 안 보일 때 원인을 좁히기 위한 개발 전용 로그다.
      // features는 있는데 마커가 없다면 좌표나 아이콘을 의심한다.
      console.info(
        '[DefectMap] 결함 %d건 -> 마커 %d개. 첫 좌표=%o',
        features.length,
        markers.length,
        first ? first.geometry.coordinates : null,
      )
    }

    return () => {
      markers.forEach((marker) => marker.setMap(null))
    }
  }, [features, mapStatus, selectedDefectId, onSelect, idleToken])

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
          {scale !== null && (
            <div className="defect-map-scale" aria-label={`축척 ${scale.label}`}>
              {/*
                너비가 배율마다 달라져 클래스로 뺄 수 없다. style 속성은 CSP에
                막히므로, bbox 오버레이와 같이 SVG 기하 속성으로 그린다.
              */}
              <svg
                className="defect-map-scale-bar"
                width={scale.widthPx}
                height="8"
                aria-hidden="true"
              >
                <path
                  d={`M1 1 V7 H${scale.widthPx - 1} V1`}
                  fill="none"
                  vectorEffect="non-scaling-stroke"
                />
              </svg>
              <span>{scale.label}</span>
            </div>
          )}
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
              onSelectDefect={onSelect}
            />
          )}
        </div>
      )}
    </article>
  )
}
