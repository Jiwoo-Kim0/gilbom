import type { PageResponse } from '../admin/adminTypes'

export type { PageResponse }

/**
 * 서버 `DefectDtos`와 1:1로 맞춘 결함 계약이다.
 * 필드 이름과 enum 값을 임의로 바꾸지 않는다.
 */

// ---------------------------------------------------------------- enum

/** `DefectWorkflow`의 상태. 전이 규칙은 {@link allowedTransitions} 참고. */
export const WORKFLOW_STATUSES = [
  'NEW',
  'ACCEPTED',
  'IN_PROGRESS',
  'RESOLVED',
  'FALSE_POSITIVE',
  'REJECTED',
] as const
export type WorkflowStatus = (typeof WORKFLOW_STATUSES)[number]

export const RISK_LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const
export type RiskLevel = (typeof RISK_LEVELS)[number]

/** 서버 `DefectFilter.DEFECT_TYPES`와 같아야 한다. */
export const DEFECT_TYPES = [
  'DAMAGED',
  'MISSING',
  'INSTALLATION_INVALID',
  'UNKNOWN_ANOMALY',
] as const
export type DefectType = (typeof DEFECT_TYPES)[number]

export const BLOCK_TYPES = ['LINEAR', 'DOT', 'UNKNOWN'] as const
export type BlockType = (typeof BLOCK_TYPES)[number]

/** 서버 `DefectFilter.RESOLUTIONS`와 같아야 한다. */
export const RESOLUTIONS = [
  'REPAIRED',
  'CLEANED',
  'DUPLICATE',
  'REINSPECTION_REQUIRED',
  'NOT_APPLICABLE',
] as const
export type Resolution = (typeof RESOLUTIONS)[number]

/** 결함 분류. 현재는 점자블록 하나뿐이다. */
export const CATEGORIES = ['BRAILLE_BLOCK'] as const
export type Category = (typeof CATEGORIES)[number]

/**
 * 사람이 지정할 수 있는 처리 상태. 서버 `DefectWorkflow.ASSIGNABLE_STATUSES`와 같다.
 *
 * <p>`NEW`는 결함이 적재될 때 시스템이 매기는 상태라 빠져 있다. 되돌리면 관측 이력과
 * 어긋나고, 서버도 409로 막는다.
 */
export const ASSIGNABLE_STATUSES: WorkflowStatus[] = [
  'ACCEPTED',
  'IN_PROGRESS',
  'RESOLVED',
  'FALSE_POSITIVE',
  'REJECTED',
]

/**
 * 지금 상태에서 고를 수 있는 다음 상태.
 *
 * <p>단계에 묶지 않는다(서버 `DefectWorkflow` 주석 참고). 현장 처리가 순서대로
 * 흐르지 않아서, 접수 없이 바로 완료하거나 완료한 뒤 처리 중으로 되돌리는 일이 있다.
 * 지금과 같은 상태만 뺀다. 최종 판정은 서버가 409로 한다.
 */
export function allowedTransitions(from: WorkflowStatus): WorkflowStatus[] {
  return ASSIGNABLE_STATUSES.filter((status) => status !== from)
}

/** 종결 상태는 내용 수정이 막힌다. 처리 상태 변경은 막지 않는다. */
export const TERMINAL_STATUSES: WorkflowStatus[] = [
  'RESOLVED',
  'FALSE_POSITIVE',
  'REJECTED',
]

export function isTerminal(status: WorkflowStatus): boolean {
  return TERMINAL_STATUSES.includes(status)
}

// ---------------------------------------------------------------- 표시 라벨

export const WORKFLOW_STATUS_LABELS: Record<WorkflowStatus, string> = {
  NEW: '신규',
  ACCEPTED: '접수',
  IN_PROGRESS: '처리 중',
  RESOLVED: '완료',
  FALSE_POSITIVE: '오탐',
  REJECTED: '반려',
}

export const RISK_LEVEL_LABELS: Record<RiskLevel, string> = {
  LOW: '낮음',
  MEDIUM: '보통',
  HIGH: '높음',
  CRITICAL: '심각',
}

export const DEFECT_TYPE_LABELS: Record<DefectType, string> = {
  DAMAGED: '파손',
  MISSING: '미설치',
  INSTALLATION_INVALID: '설치 이상',
  UNKNOWN_ANOMALY: '기타 이상',
}

export const RESOLUTION_LABELS: Record<Resolution, string> = {
  REPAIRED: '수리 완료',
  CLEANED: '청소 완료',
  DUPLICATE: '중복',
  REINSPECTION_REQUIRED: '재점검 필요',
  NOT_APPLICABLE: '해당 없음',
}

// ---------------------------------------------------------------- 조회 응답

/** 좌표 순서는 20장 공통 규칙대로 [longitude, latitude]다. */
export type GeoPoint = {
  longitude: number
  latitude: number
}

export type DefectListItem = {
  defectId: string
  incidentCode: string
  assetType: string
  blockType: BlockType | null
  defectType: DefectType
  riskLevel: RiskLevel
  riskScore: number | null
  workflowStatus: WorkflowStatus
  resolution: Resolution | null
  location: GeoPoint | null
  locationAccuracyM: number | null
  zoneId: string | null
  patrolAreaId: string | null
  patrolRouteId: string | null
  locationLabel: string | null
  /** 서버가 목록에서는 발급하지 않는다. 상세에서 image-url로 따로 받는다. */
  representativeImageUrl: string | null
  firstSeenAt: string
  lastSeenAt: string
  observationCount: number
  latestConfidence: number | null
  reviewRequired: boolean
}

export type DefectDetail = {
  defect: DefectListItem
  resolutionNote: string | null
  riskScore: number | null
  riskRuleVersion: string | null
  riskCalculatedAt: string | null
  mergedIntoDefectId: string | null
  mergedDefectIds: string[]
  createdAt: string
  updatedAt: string
}

export type DefectSummary = {
  totalDefects: number
  newDefects: number
  acceptedDefects: number
  inProgressDefects: number
  resolvedDefects: number
  falsePositiveDefects: number
  rejectedDefects: number
  criticalDefects: number
  highDefects: number
  reviewRequiredDefects: number
}

// 22.5의 GeoJSON FeatureCollection
export type DefectMapProperties = {
  defectId: string
  incidentCode: string
  defectType: DefectType
  riskLevel: RiskLevel
  workflowStatus: WorkflowStatus
  latestConfidence: number | null
}

export type DefectMapFeature = {
  type: 'Feature'
  geometry: {
    type: 'Point'
    /** [longitude, latitude] */
    coordinates: [number, number]
  }
  properties: DefectMapProperties
}

export type DefectMapResponse = {
  type: 'FeatureCollection'
  features: DefectMapFeature[]
}

export type DefectObservation = {
  eventId: string
  defectId: string
  robotId: string
  /** 표시용 로봇 이름. 로봇 행이 없으면 null이다. */
  robotName: string | null
  locationValid: boolean
  location: GeoPoint | null
  locationAccuracyM: number | null
  zoneId: string | null
  assetType: string
  blockType: BlockType | null
  detectedDefectType: DefectType
  confidence: number | null
  damageRatio: number | null
  imageId: string | null
  evidenceCapturedAt: string | null
  imageWidth: number | null
  imageHeight: number | null
  /** [xMin, yMin, xMax, yMax] 정규 좌표 */
  bboxNorm: number[] | null
  modelName: string | null
  modelVersion: string | null
  representative: boolean
  createdAt: string
}

export type DefectHistoryItem = {
  historyId: number
  defectId: string
  changedBy: string | null
  changeType: string
  beforeStatus: WorkflowStatus | null
  afterStatus: WorkflowStatus | null
  beforeValues: Record<string, unknown> | null
  afterValues: Record<string, unknown> | null
  reason: string | null
  createdAt: string
}

export type DefectImageUrl = {
  defectId: string
  eventId: string | null
  imageId: string
  imageUrl: string
  expiresAt: string
  imageWidth: number | null
  imageHeight: number | null
  bboxNorm: number[] | null
}

/**
 * 지도 묶음 목록 한 줄. 지도 GeoJSON에 없는 값(위치 이름, 사진)만 담는다.
 *
 * <p>`imageUrl`은 사진이 아직 연결되지 않았거나 저장소를 쓸 수 없으면 null이다 —
 * 목록의 사진 한 칸이 비는 것은 오류가 아니다.
 */
export type DefectClusterItem = {
  defectId: string
  incidentCode: string
  locationLabel: string | null
  zoneId: string | null
  imageUrl: string | null
  imageUrlExpiresAt: string | null
}

export type DefectClusterDetails = {
  /** 보이지 않는 결함은 404가 아니라 여기서 빠진다. */
  items: DefectClusterItem[]
}

export type DefectMergeResult = {
  targetDefectId: string
  mergedDefectIds: string[]
  observationCount: number
  firstSeenAt: string
  lastSeenAt: string
}

// ---------------------------------------------------------------- 요청

export type DefectFilters = {
  q: string
  /**
   * 서버가 `last_seen_at >= from`으로 거는 하한이다. 필터 패널은 날짜만 받지만
   * 값은 datetime-local 문자열(`2026-08-05T00:00`)로 들고 다닌다.
   */
  from: string
  /**
   * 상한. 결함 필터 패널은 노출하지 않는다(상한은 늘 "지금"이다).
   * 요약처럼 기간을 못박아 조회하는 쪽에서만 채운다.
   */
  to: string
  workflowStatuses: WorkflowStatus[]
  defectTypes: DefectType[]
  severities: RiskLevel[]
  patrolAreaId: string
  patrolRouteId: string
  robotId: string
  /** 0.0 ~ 1.0 */
  minConfidence: number | null
  /** "minLon,minLat,maxLon,maxLat" */
  bbox: string
}

/**
 * 처음 들어왔을 때 보여줄 상태. 종결된 결함은 조치할 것이 없어 지도와 목록을
 * 가리기만 한다(시연 데이터 기준 41건 중 25건). 체크박스로 그대로 켤 수 있게
 * URL에 실어두므로, 감추는 것이 아니라 기본값을 정하는 것이다.
 */
export const DEFAULT_WORKFLOW_STATUSES: WorkflowStatus[] = [
  'NEW',
  'ACCEPTED',
  'IN_PROGRESS',
]

export const EMPTY_DEFECT_FILTERS: DefectFilters = {
  q: '',
  from: '',
  to: '',
  workflowStatuses: [],
  defectTypes: [],
  severities: [],
  patrolAreaId: '',
  patrolRouteId: '',
  robotId: '',
  minConfidence: null,
  bbox: '',
}

/**
 * "초기화"가 되돌아가는 자리. **첫 진입과 같은 화면**이어야 한다 — 조건을 다 풀어
 * 종결까지 쏟아지는 목록은 아무도 원하지 않는다.
 *
 * <p>`EMPTY_DEFECT_FILTERS`와 다른 점은 처리 상태뿐이다. 그쪽은 "조건 없음"(전체 조회)을
 * 뜻하는 값이라 URL에 `allStatus` 표식으로 실린다. 초기화에 그것을 쓰면 안 된다.
 */
export const DEFAULT_DEFECT_FILTERS: DefectFilters = {
  ...EMPTY_DEFECT_FILTERS,
  workflowStatuses: [...DEFAULT_WORKFLOW_STATUSES],
}

function sameSelection(left: string[], right: string[]): boolean {
  return (
    left.length === right.length
    && [...left].sort().join() === [...right].sort().join()
  )
}

/**
 * 기본값과 달라진 조건 묶음의 수. "필터" 버튼 배지에 찍힌다.
 *
 * <p>조건이 전부 모달 안으로 들어가면서 화면에 무엇이 걸렸는지 보여줄 것이 이 숫자
 * 하나뿐이다. 그래서 **고른 항목 수가 아니라 손댄 조건 수**를 센다 — 심각도 넷을 고른
 * 것도 "조건 하나 걸림"이다. 검색어는 검색창에 그대로 보이므로 세지 않는다.
 *
 * <p>기준은 {@link DEFAULT_DEFECT_FILTERS}다. 첫 진입의 처리 상태 셋은 손댄 것이 아니다.
 */
export function countActiveFilterGroups(filters: DefectFilters): number {
  const changed = [
    filters.from !== DEFAULT_DEFECT_FILTERS.from,
    !sameSelection(
      filters.workflowStatuses,
      DEFAULT_DEFECT_FILTERS.workflowStatuses,
    ),
    filters.defectTypes.length > 0,
    filters.severities.length > 0,
    filters.minConfidence !== null,
  ]
  return changed.filter(Boolean).length
}

/** 서버 `DefectFilter.SORTABLE_COLUMNS`가 허용하는 키만 노출한다. */
export const DEFECT_SORT_OPTIONS = [
  { value: 'lastSeenAt,desc', label: '최근 관측순' },
  { value: 'firstSeenAt,asc', label: '최초 발견순' },
  { value: 'riskScore,desc', label: '위험도순' },
  { value: 'observationCount,desc', label: '반복 관측순' },
  { value: 'latestConfidence,desc', label: '신뢰도순' },
] as const

export type DefectStatusUpdateRequest = {
  workflowStatus: WorkflowStatus
  resolution?: Resolution | null
  resolutionNote?: string | null
  reason: string
}

export type DefectUpdateRequest = {
  defectType?: DefectType
  riskLevel?: RiskLevel
  blockType?: BlockType
  longitude?: number
  latitude?: number
  locationAccuracyM?: number
  zoneId?: string
  locationLabel?: string
  reviewRequired?: boolean
  reason: string
}

export type DefectMergeRequest = {
  targetDefectId: string
  sourceDefectIds: string[]
  reason: string
}
