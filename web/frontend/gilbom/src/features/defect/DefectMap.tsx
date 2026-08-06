import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { RefObject } from 'react'
import { loadNaverMapsScript } from '../../lib/naverMaps'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { CLUSTER_LIST_MAX, DefectClusterList } from './DefectClusterList'
import { DefectDetailCard } from './DefectDetailCard'
import type { DefectCardPosition } from './defectCardDrag'
import { getDefectMap } from './defectApi'
import {
  bboxForDefectMapFilter,
  isPointOutsideMapView,
  unclusterZoomStep,
  REVEAL_ZOOM,
  revealZoom,
  clusterZoomPlan,
  defectMapFilterKey,
  defectTypeTone,
} from './defectMapState'
import type {
  DefectMapViewport,
  DefectTypeTone,
} from './defectMapState'
import {
  DEFECT_TYPES,
  DEFECT_TYPE_LABELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type {
  DefectFilters,
  DefectMapFeature,
  DefectType,
} from './defectTypes'

type DefectClusterTone = DefectTypeTone | 'mixed'

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
 * 묶인 마커의 아이콘. 같은 유형만 모였으면 해당 유형의 색을 유지하고, 여러 유형이
 * 섞였으면 중립색을 사용한다.
 */
function createClusterIcon(
  maps: typeof naver.maps,
  cluster: MarkerCluster,
): naver.maps.MarkerIcon {
  const tones = new Set(
    cluster.features.map(
      (feature) => defectTypeTone(feature.properties.defectType),
    ),
  )
  const [firstTone] = tones
  const tone: DefectClusterTone = tones.size === 1 && firstTone !== undefined
    ? firstTone
    : 'mixed'
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

/** Naver 지도가 허용하는 최대 확대 단계. */
const MAX_MAP_ZOOM = 21

/** App.css의 `.defect-marker-box` 크기와 같아야 앵커가 중심에 맞는다. */
const MARKER_BOX_SIZE = 34

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

/**
 * 지금 배율에서 1px이 몇 m인가. `computeScale`과 같은 식이고, 지도가 실제로 보여주는
 * 범위에서 재므로 projection 단위·해상도 배율에 기대지 않는다.
 */
function metersPerPixelOf(map: naver.maps.Map, widthPx: number): number {
  if (widthPx <= 0) {
    return 0
  }
  const bounds = map.getBounds()
  const southWest = bounds.getSW()
  const northEast = bounds.getNE()
  const centerLatitude = (southWest.lat() + northEast.lat()) / 2
  const spanMeters = Math.abs(northEast.lng() - southWest.lng())
    * 111_320 * Math.cos((centerLatitude * Math.PI) / 180)
  return spanMeters / widthPx
}

/** 지도를 끌 때마다 조회하지 않도록 idle 이후 잠깐 기다린다. */
const VIEWPORT_DEBOUNCE_MS = 300

const naverMapClientId = import.meta.env.VITE_NAVER_MAP_CLIENT_ID

function createMarkerIcon(
  maps: typeof naver.maps,
  tone: DefectTypeTone,
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

/**
 * "이 좌표를 지도 가운데로" 요청. 목록 표에서 결함을 고를 때 상위가 넘긴다.
 *
 * <p>`token`이 있는 이유는 **같은 결함을 다시 고르는 경우** 때문이다. 좌표만 보고
 * 있으면 지도를 딴 곳으로 끌어놓고 같은 행을 다시 눌러도 값이 그대로라 아무 일도
 * 일어나지 않는다. 요청마다 token을 올려 "또 요청했다"를 구분한다.
 */
export type DefectMapCenterRequest = {
  latitude: number
  longitude: number
  token: number
  /**
   * 고른 결함. 이웃과의 거리를 잴 때 자기 자신을 빼기 위해 쓴다. 없으면 같은 좌표에
   * 있는 것을 자기 자신으로 본다.
   */
  defectId?: string
}

/**
 * 실시간으로 새 결함이 들어왔다는 신호. 좌표는 여기 없다 — SSE 이벤트가 위치를 주지
 * 않으므로 지도가 직접 조회해서 찾는다.
 *
 * <p>새 이벤트마다 객체를 새로 만들어야 지도가 반응한다.
 */
export type DefectMapRevealRequest = {
  defectId: string
}

interface DefectMapProps {
  filters: DefectFilters
  selectedDefectId: string | null
  onSelect: (defectId: string | null) => void
  /** 카드에서 상태를 바꾸면 목록·지도를 다시 읽는다. */
  onChanged: () => void
  /** 목록에서 결함을 고르면 페이지가 이 카드로 스크롤한다. */
  cardRef?: RefObject<HTMLElement | null>
  /** 목록에서 고른 결함을 지도 가운데로 옮긴다. {@link DefectMapCenterRequest} 참고. */
  centerRequest?: DefectMapCenterRequest | null
  /**
   * 방금 탐지된 결함. **화면 밖에 있을 때만** 그 위치로 지도를 옮긴다.
   * {@link DefectMapRevealRequest} 참고.
   */
  revealRequest?: DefectMapRevealRequest | null
  /**
   * 값이 바뀌면 같은 필터·같은 뷰포트라도 GeoJSON을 다시 받는다.
   *
   * <p>SSE로 새 결함이 들어오면 목록은 갱신되는데 지도는 그대로였다. 필터와 bbox가
   * 안 바뀌니 다시 읽을 이유가 없었기 때문이다. 부모의 재조회 신호를 여기까지
   * 내려야 마커가 새로고침 없이 올라온다.
   */
  reloadToken?: number
}

export function DefectMap({
  filters,
  selectedDefectId,
  onSelect,
  onChanged,
  cardRef,
  centerRequest = null,
  revealRequest = null,
  reloadToken = 0,
}: DefectMapProps) {
  const mapElementRef = useRef<HTMLDivElement | null>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const [mapStatus, setMapStatus] = useState<'loading' | 'ready' | 'error'>(
    () => (naverMapClientId ? 'loading' : 'error'),
  )
  /*
   * bbox는 해당 필터에서 지도를 맞춘 뒤에만 유효하다. 날짜나 검색 조건이 바뀌면
   * 이전 화면 범위를 재사용하지 않고 전체 범위로 다시 조회해야 새 결과가 지도
   * 밖에 있다는 이유로 사라지지 않는다.
   */
  const filterKey = useMemo(
    () => defectMapFilterKey(filters),
    [filters],
  )
  const filterKeyRef = useRef(filterKey)
  const [viewport, setViewport] = useState<DefectMapViewport>({
    filterKey,
    bbox: '',
  })
  const bbox = bboxForDefectMapFilter(viewport, filterKey)
  const requestKey = `${filterKey}\u0000${bbox}\u0000${reloadToken}`
  const [featureSnapshot, setFeatureSnapshot] = useState<{
    key: string
    features: DefectMapFeature[]
    error: string | null
  } | null>(null)
  // 새 응답이 오는 동안에는 기존 마커를 유지하되, 새 필터의 결과로 오인하지 않는다.
  const features = useMemo(
    () => featureSnapshot?.features ?? [],
    [featureSnapshot],
  )
  /**
   * 범례에 붙일 유형별 개수. 지금 지도에 그려진 것만 센다(뷰포트·필터 반영).
   */
  const countByType = useMemo(() => {
    const counts = new Map<DefectType, number>()
    for (const feature of features) {
      const { defectType } = feature.properties
      counts.set(defectType, (counts.get(defectType) ?? 0) + 1)
    }
    return counts
  }, [features])
  const queryLoading = featureSnapshot?.key !== requestKey
  const error = featureSnapshot?.key === requestKey
    ? featureSnapshot.error
    : null
  const [scale, setScale] = useState<MapScale | null>(null)
  /**
   * 묶음을 눌러 펼친 구성원 목록. 지도 위 왼쪽에 뜬다. 닫기 전까지 남아 있어
   * 형제 결함을 이어서 훑을 수 있다.
   *
   * <p>어느 조건에서 편 것인지 함께 들고 있다가, 조건이 바뀌면 파생값 쪽에서 가린다.
   * 걸러진 결함을 가리키는 목록이 남지 않는다 — effect로 지우면 그만큼 렌더가 한 번 더 돈다.
   */
  const [clusterList, setClusterList] = useState<{
    filterKey: string
    features: DefectMapFeature[]
  } | null>(null)
  /**
   * 상세 카드를 끌어 옮긴 자리. 카드가 아니라 지도가 들고 있다 — 카드는 결함마다
   * `key`로 새로 마운트되므로, 카드 안에 두면 결함을 바꿀 때마다 제자리로 돌아간다.
   */
  const [cardPosition, setCardPosition] = useState<DefectCardPosition | null>(
    null,
  )
  const visibleClusterList = clusterList?.filterKey === filterKey
    ? clusterList.features
    : null

  /**
   * 좌표를 지도 가운데로 옮긴다. 배율은 건드리지 않는다 — 고른 결함을 보려는 것이지
   * 더 들여다보려는 것이 아니고, 확대까지 바뀌면 주변 결함이 화면에서 사라진다.
   */
  const centerMapOn = useCallback((latitude: number, longitude: number) => {
    const map = mapRef.current
    if (map === null || !window.naver) return

    const position = new window.naver.maps.LatLng(latitude, longitude)
    // 움직임을 줄여달라고 설정한 사용자에게 미끄러지는 지도는 멀미를 준다.
    // SDK 버전에 panTo가 없을 수도 있어 그때도 setCenter로 떨어진다.
    const reduceMotion = window.matchMedia(
      '(prefers-reduced-motion: reduce)',
    ).matches
    if (reduceMotion || typeof map.panTo !== 'function') {
      map.setCenter(position)
      return
    }
    map.panTo(position)
  }, [])

  /*
   * 아래 "새 결함이 화면 밖이면 옮기기"가 최신 필터를 읽되, 필터가 바뀔 때마다 다시
   * 실행되지는 않게 하려고 ref로 들고 있는다. 그 효과는 **새 이벤트에만** 반응해야 한다.
   */
  const filtersRef = useRef(filters)
  useEffect(() => {
    filtersRef.current = filters
  }, [filters])

  /**
   * 좌표로 옮기면서 배율까지 맞춘다.
   *
   * <p>**한 번에 해야 한다.** `panTo`(애니메이션) 뒤에 곧바로 `setZoom`을 부르면 이동이
   * 중간에 끊겨 엉뚱한 자리에서 확대된다 — 옮겨간 화면에 정작 그 결함이 없게 된다.
   * SDK의 `morph`가 이 일을 한 번에 하고, 없거나 움직임을 줄여달라고 한 사용자에게는
   * 애니메이션 없이 `setCenter` + `setZoom`으로 떨어진다(이 조합은 끊길 것이 없다).
   */
  const focusMapOn = useCallback(
    (latitude: number, longitude: number, zoom: number) => {
      const map = mapRef.current
      if (map === null || !window.naver) return

      const position = new window.naver.maps.LatLng(latitude, longitude)
      const reduceMotion = window.matchMedia(
        '(prefers-reduced-motion: reduce)',
      ).matches
      if (!reduceMotion && typeof map.morph === 'function') {
        map.morph(position, zoom)
        return
      }
      map.setCenter(position)
      if (map.getZoom() !== zoom) {
        map.setZoom(zoom)
      }
    },
    [],
  )

  /**
   * 목록에서 고른 결함의 마커로 화면을 옮기고, **그 마커가 묶이지 않을 만큼** 확대한다.
   *
   * <p>묶음 안에 있으면 옮겨가도 정작 그 마커가 보이지 않는다 — 숫자만 보인다. 그래서
   * 가장 가까운 이웃과 격자 한 칸(44px)만큼 벌어지는 배율까지 들어간다.
   *
   * <p>이웃을 알려면 **뷰포트 bbox 없이** 지도 GeoJSON을 받아야 한다. 지금 그려진 것만
   * 보면, 화면 밖에 있는 결함을 골랐을 때 그 주변을 아예 모른다(그 자리로 옮긴 뒤에야
   * 이웃이 조회되고, 그때는 이미 묶여 보인다).
   *
   * <p>조회가 실패하면 확대 없이 가운데로만 옮긴다. 옮기기는 살려야 한다.
   */
  const focusDefectMarker = useCallback(
    (defectId: string | null, latitude: number, longitude: number) => {
      const map = mapRef.current
      if (map === null) return

      getDefectMap({ ...filtersRef.current, bbox: '' })
        .then((response) => {
          if (mapRef.current === null) return
          const others = response.features
            .filter((feature) => (
              defectId === null
                ? feature.geometry.coordinates[1] !== latitude
                  || feature.geometry.coordinates[0] !== longitude
                : feature.properties.defectId !== defectId
            ))
            .map((feature) => ({
              latitude: feature.geometry.coordinates[1],
              longitude: feature.geometry.coordinates[0],
            }))
          const step = unclusterZoomStep(
            { latitude, longitude },
            others,
            // 배율과 확대 단계는 같은 시점에 읽어야 어긋나지 않는다.
            metersPerPixelOf(
              mapRef.current,
              mapElementRef.current?.clientWidth ?? 0,
            ),
            CLUSTER_CELL_PX,
          )
          focusMapOn(
            latitude,
            longitude,
            Math.min(mapRef.current.getZoom() + step, MAX_MAP_ZOOM),
          )
        })
        .catch(() => centerMapOn(latitude, longitude))
    },
    [centerMapOn, focusMapOn],
  )

  /*
   * 목록 표에서 고른 결함을 가운데로 옮긴다.
   *
   * 지도 마커를 눌렀을 때는 옮기지 않는다 — 이미 그 자리를 보고 있고, 손가락 밑에서
   * 지도가 미끄러지면 방금 무엇을 눌렀는지 놓친다. 그래서 선택(`selectedDefectId`)이
   * 아니라 **상위가 보낸 요청**에 반응한다.
   */
  useEffect(() => {
    if (centerRequest === null || mapStatus !== 'ready') return
    focusDefectMarker(
      centerRequest.defectId ?? null,
      centerRequest.latitude,
      centerRequest.longitude,
    )
  }, [centerRequest, focusDefectMarker, mapStatus])
  // 확대·이동이 끝나면 화면 좌표가 달라져 마커 묶음을 다시 계산해야 한다.
  const [idleToken, setIdleToken] = useState(0)
  /**
   * 결함 위치로 지도를 한 번 맞췄는지. 기본 뷰포트(구미)와 실제 결함 위치가
   * 다를 수 있어, 처음에는 전체를 조회해 그 범위로 이동한다. 이 전에 뷰포트
   * bbox를 적용하면 엉뚱한 범위로 걸러 0건이 되고 지도가 영영 비어 보인다.
   */
  const fittedFilterKeyRef = useRef<string | null>(null)

  useEffect(() => {
    filterKeyRef.current = filterKey
  }, [filterKey])


  /**
   * 새로 탐지된 결함이 **화면 밖일 때만** 그 위치로 지도를 옮긴다.
   *
   * <p>SSE 이벤트에는 좌표가 없어서(계약상 알림만 준다) 지금 필터로 지도용 GeoJSON을
   * 한 번 더 받아 그 결함을 찾는다. **뷰포트 bbox 없이** 받는 것이 요점이다 — 화면 밖에
   * 있는 결함이 목적인데 bbox를 걸면 애초에 응답에 없다.
   *
   * <p>이 응답으로 마커를 바꾸지는 않는다. 찾는 목적만이다. 지도를 옮기면 idle이 오고,
   * 그때 뷰포트 bbox로 정상 조회가 다시 돌아 마커가 올라온다.
   *
   * <p>서버 필터를 그대로 쓰기 때문에 **지금 조건에 맞지 않는 결함이면 응답에 없어
   * 지도가 움직이지 않는다.** 필터에 걸려 그려지지도 않을 마커로 화면을 끌고 가면
   * 사용자는 빈 지도를 보게 된다.
   */
  useEffect(() => {
    if (revealRequest === null || mapStatus !== 'ready') return
    let cancelled = false

    getDefectMap({ ...filtersRef.current, bbox: '' })
      .then((response) => {
        const map = mapRef.current
        if (cancelled || map === null) return
        const target = response.features.find(
          (feature) => feature.properties.defectId === revealRequest.defectId,
        )
        if (target === undefined) return

        const [longitude, latitude] = target.geometry.coordinates
        const bounds = map.getBounds()
        const southWest = bounds.getSW()
        const northEast = bounds.getNE()
        const outside = isPointOutsideMapView({ latitude, longitude }, {
          south: southWest.lat(),
          west: southWest.lng(),
          north: northEast.lat(),
          east: northEast.lng(),
        })
        // 이미 보이는 자리면 움직이지 않는다. 보던 위치를 빼앗지 않는 것이 기본이다.
        if (!outside) return
        /*
         * 옮기는 것만으로는 부족하다. 넓게 보고 있었다면 마커가 화면에 들어와도 어느
         * 길인지 알 수 없어서, 알아볼 배율까지 확대한다. 이미 더 깊이 보고 있었다면
         * 그대로 둔다(revealZoom).
         */
        focusMapOn(
          latitude,
          longitude,
          revealZoom(map.getZoom(), REVEAL_ZOOM, MAX_MAP_ZOOM),
        )
      })
      // 못 찾거나 조회가 실패하면 지도를 움직이지 않는다. 알림 줄은 이미 떠 있다.
      .catch(() => {})

    return () => {
      cancelled = true
    }
  }, [revealRequest, mapStatus, focusMapOn])

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

        // 축척은 조회를 유발하지 않으므로 아래 fittedFilterKeyRef 가드와 debounce를 타지
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
          if (fittedFilterKeyRef.current !== filterKeyRef.current) return
          window.clearTimeout(timer)
          timer = window.setTimeout(() => {
            const activeFilterKey = filterKeyRef.current
            if (fittedFilterKeyRef.current !== activeFilterKey) return
            const bounds = map.getBounds()
            const southWest = bounds.getSW()
            const northEast = bounds.getNE()
            // 서버 계약 순서는 minLon,minLat,maxLon,maxLat다.
            setViewport({
              filterKey: activeFilterKey,
              bbox: [
                southWest.lng(),
                southWest.lat(),
                northEast.lng(),
                northEast.lat(),
              ]
                .map((value) => value.toFixed(6))
                .join(','),
            })
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
          setFeatureSnapshot({
            key: requestKey,
            features: response.features,
            error: null,
          })
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setFeatureSnapshot({
            key: requestKey,
            features: [],
            error: getApiErrorMessage(cause),
          })
        }
      })

    return () => {
      cancelled = true
    }
  }, [filters, bbox, requestKey])

  // 첫 조회 결과가 도착하면 그 결함들이 모두 보이도록 지도를 한 번 맞춘다.
  // 이후의 이동·확대는 사용자 조작을 따르고 뷰포트 bbox로 다시 조회한다.
  useEffect(() => {
    if (
      mapStatus !== 'ready'
      || bbox !== ''
      || featureSnapshot?.key !== requestKey
      || fittedFilterKeyRef.current === filterKey
    ) return
    if (features.length === 0 || !mapRef.current || !window.naver) return

    const { maps } = window.naver
    const latitudes = features.map((feature) => feature.geometry.coordinates[1])
    const longitudes = features.map((feature) => feature.geometry.coordinates[0])
    // 한 점뿐이면 fitBounds가 과도하게 확대되므로 약간의 여백을 준다.
    const padding = 0.002
    // fitBounds가 동기로 idle을 발생시켜도 새 필터의 bbox를 받을 수 있게 먼저 기록한다.
    fittedFilterKeyRef.current = filterKey
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
  }, [
    bbox,
    featureSnapshot?.key,
    features,
    filterKey,
    mapStatus,
    requestKey,
  ])

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
              defectTypeTone(defectType),
              defectId === selectedDefectId,
            ),
        title: grouped
          ? `결함 ${cluster.features.length}건 — 누르면 확대합니다`
            + (cluster.features.length <= CLUSTER_LIST_MAX
              ? ' (목록도 함께 열립니다)'
              : '')
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
        /*
         * 묶음을 클릭하면 **구성원 전체가 담기는 가장 큰 배율**로 확대한다. 고정 두 단계로
         * 두면 촘촘한 묶음은 같은 숫자가 다시 나오고, 가장 가까운 쌍까지 갈라내려 하면
         * 지나치게 확대돼 나머지 구성원이 화면 밖으로 나간다.
         */
        const element = mapElementRef.current
        const plan = clusterZoomPlan(
          cluster.features.map((member) => {
            const [longitude, latitude] = member.geometry.coordinates
            return { latitude, longitude }
          }),
          // 1px이 몇 m인지는 지도에서 직접 잰다(축척 바와 같은 방법).
          metersPerPixelOf(map, element?.clientWidth ?? 0),
          {
            widthPx: element?.clientWidth ?? 0,
            heightPx: element?.clientHeight ?? 0,
          },
        )
        map.setCenter(new maps.LatLng(plan.center.latitude, plan.center.longitude))
        map.setZoom(Math.min(map.getZoom() + plan.step, MAX_MAP_ZOOM))

        /*
         * 셀 만한 수면 그 자리에서 구성원 목록까지 편다. 확대만 하면 "이 동네에 몇
         * 건"까지만 알 수 있고 코드를 보려면 마커를 하나씩 눌러야 한다. 그보다 많으면
         * 목록이 지도를 가리기만 하므로 확대에서 멈춘다.
         */
        setClusterList(
          cluster.features.length <= CLUSTER_LIST_MAX
            ? { filterKey: filterKeyRef.current, features: cluster.features }
            : null,
        )
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

  return (
    <article
      className="content-card defect-map-card"
      aria-label="결함 지도"
      ref={cardRef}
    >
      {/*
        마커 색이 무슨 뜻인지 화면에 설명이 있어야 한다. 유형→색은 필터 드롭다운의 점과
        같은 CSS 변수를 쓰므로 둘이 어긋나지 않는다. 개수는 지금 그려진 마커 기준이다.
      */}
      <div className="defect-map-legend" aria-label="마커 색 범례">
        {DEFECT_TYPES.map((defectType) => (
          <span
            key={defectType}
            className="defect-legend-item"
          >
            <span
              className={`defect-type-dot defect-type-dot-${defectTypeTone(defectType)}`}
              aria-hidden="true"
            />
            {DEFECT_TYPE_LABELS[defectType]}
            {' '}
            ({mapStatus === 'ready' ? (countByType.get(defectType) ?? 0) : '—'})
          </span>
        ))}
        {/* 묶인 마커가 여러 유형이면 회색이다. 그 색도 설명이 필요하다. */}
        <span className="defect-legend-item defect-legend-item-mixed">
          <span className="defect-type-dot" aria-hidden="true" />
          여러 유형 묶음
        </span>
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
          {(mapStatus === 'loading' || queryLoading) && (
            <p className="defect-map-loading">지도를 불러오는 중입니다…</p>
          )}
          {error !== null && (
            <p className="defect-map-loading" role="alert">
              {error}
            </p>
          )}
          {visibleClusterList !== null && (
            // 다른 묶음을 누르면 새로 마운트해 이전 묶음의 사진·위치를 들고 있지 않게 한다.
            <DefectClusterList
              key={visibleClusterList
                .map((feature) => feature.properties.defectId)
                .join(',')}
              features={visibleClusterList}
              selectedDefectId={selectedDefectId}
              // 이 목록도 목록이다. 고른 결함의 마커가 가운데로 온다.
              onSelect={(defectId, latitude, longitude) => {
                onSelect(defectId)
                // 묶음 목록에서 고르는 것이라 특히 묶이지 않을 배율까지 들어가야 한다.
                focusDefectMarker(defectId, latitude, longitude)
              }}
              onClose={() => setClusterList(null)}
            />
          )}
          {selectedDefectId !== null && (
            <DefectDetailCard
              key={selectedDefectId}
              defectId={selectedDefectId}
              onClose={() => onSelect(null)}
              onChanged={onChanged}
              onSelectDefect={onSelect}
              /* 카드는 결함마다 새로 마운트된다(`key`). 옮겨둔 자리는 여기서 들고
                 있어야 결함을 바꿀 때 제자리로 튀어오르지 않는다. */
              position={cardPosition}
              onMove={setCardPosition}
            />
          )}
        </div>
      )}
    </article>
  )
}
