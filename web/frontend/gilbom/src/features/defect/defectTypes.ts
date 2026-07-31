import type { PageResponse } from '../admin/adminTypes'

export type { PageResponse }

/**
 * 서버 `DefectDtos`와 1:1로 맞춘 결함 계약이다.
 * 필드 이름과 enum 값을 임의로 바꾸지 않는다.
 */

// ---------------------------------------------------------------- enum

/** `DefectWorkflow`의 상태. 전이 규칙은 {@link ALLOWED_TRANSITIONS} 참고. */
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
  'CONTAMINATED',
  'UPLIFTED',
  'BLOCKED',
  'MISSING_SUSPECTED',
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
 * 서버 `DefectWorkflow.ALLOWED_TRANSITIONS`를 그대로 옮긴 것이다.
 * 화면은 허용된 다음 상태만 보여주고, 최종 판정은 서버가 409로 한다.
 */
export const ALLOWED_TRANSITIONS: Record<WorkflowStatus, WorkflowStatus[]> = {
  NEW: ['ACCEPTED', 'FALSE_POSITIVE', 'REJECTED'],
  ACCEPTED: ['IN_PROGRESS', 'FALSE_POSITIVE', 'REJECTED'],
  IN_PROGRESS: ['RESOLVED', 'FALSE_POSITIVE', 'REJECTED'],
  RESOLVED: [],
  FALSE_POSITIVE: [],
  REJECTED: [],
}

/** 종결 상태는 더 전이하지 않고 내용 수정도 막힌다. */
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
  CONTAMINATED: '오염',
  UPLIFTED: '융기',
  BLOCKED: '시설물 침범',
  MISSING_SUSPECTED: '유실 의심',
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
  from: string
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

/** 서버 `DefectFilter.SORTABLE_COLUMNS`가 허용하는 키만 노출한다. */
export const DEFECT_SORT_OPTIONS = [
  { value: 'lastSeenAt,desc', label: '최근 관측순' },
  { value: 'firstSeenAt,desc', label: '최초 발견순' },
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
