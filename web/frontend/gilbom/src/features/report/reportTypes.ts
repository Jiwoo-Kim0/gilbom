export type ReportStatus =
  | 'DRAFT'
  | 'IN_REVIEW'
  | 'APPROVED'
  | 'REJECTED'

export type ReportType = 'PERIODIC' | 'ISSUE'

export type ReportGenerationMethod =
  | 'MANUAL'
  | 'TEMPLATE'
  | 'LLM'
  | 'SYSTEM'

export type ReportCreatorType = 'USER' | 'SYSTEM'

export type DefectWorkflowStatus =
  | 'NEW'
  | 'ACCEPTED'
  | 'IN_PROGRESS'
  | 'RESOLVED'
  | 'FALSE_POSITIVE'
  | 'REJECTED'

export type DefectType =
  | 'DAMAGED'
  | 'INSTALLATION_INVALID'
  | 'UNKNOWN_ANOMALY'

export type ReportFilterCriteria = {
  patrolAreaId?: string | null
  robotIds?: string[]
  defectTypes?: DefectType[]
  workflowStatuses?: DefectWorkflowStatus[]
  /**
   * 지정하면 그 결함 한 건만 근거로 삼는 단건 보고서가 된다.
   * 통계는 좁히지 않고 기간·구역 전체를 유지한다.
   */
  defectId?: string
}

export type ReportAggregateMetrics = {
  missionCount: number
  completedMissionCount: number
  cancelledMissionCount: number
  failedMissionCount: number
  completionRatePercent: number
  totalMissionDurationSeconds: number
  incidentEventCount: number
  defectCount: number
  observationCount: number
}

export type ReportDefectStatusCount = {
  workflowStatus: DefectWorkflowStatus
  defectCount: number
}

export type ReportDefectTypeCount = {
  defectType: DefectType
  defectCount: number
}

export type ReportStatisticsSnapshot = {
  aggregate: ReportAggregateMetrics
  defectsByStatus: ReportDefectStatusCount[]
  defectsByType: ReportDefectTypeCount[]
}

type ManualReportCreateRequest = {
  generationMethod: 'MANUAL' | 'TEMPLATE'
  reportType: ReportType
  title: string
  summary?: string | null
  periodFrom: string
  periodTo: string
  content: string
  statistics: ReportStatisticsSnapshot
  filterCriteria?: ReportFilterCriteria
}

type LlmReportCreateRequest = {
  generationMethod: 'LLM'
  reportType: ReportType
  periodFrom: string
  periodTo: string
  filterCriteria?: ReportFilterCriteria
}

export type ReportCreateRequest =
  | ManualReportCreateRequest
  | LlmReportCreateRequest

type ReportMutableSnapshot = {
  title: string
  summary: string | null
  periodFrom: string
  periodTo: string
  content: string
  statistics: ReportStatisticsSnapshot
}

type AtLeastOne<T> = {
  [Key in keyof T]: Pick<T, Key> & Partial<Omit<T, Key>>
}[keyof T]

export type ReportUpdateChanges = AtLeastOne<ReportMutableSnapshot> & {
  changeNote?: string | null
}

export type ReportUpdateRequest = ReportUpdateChanges & {
  expectedVersionNo: number
}

export type ReportTransitionRequest = {
  expectedVersionNo: number
}

export type ReportDecisionRequest = {
  expectedVersionNo: number
  reason?: string
}

export type ReportRejectionRequest = {
  expectedVersionNo: number
  reason: string
}

export type ReportListItem = {
  reportId: string
  reportCode: string
  title: string
  summary: string | null
  reportType: ReportType
  reportStatus: ReportStatus
  generationMethod: ReportGenerationMethod
  periodFrom: string
  periodTo: string
  currentVersionNo: number
  approvedVersionNo: number | null
  creatorType: ReportCreatorType
  createdBy: string | null
  approvedBy: string | null
  approvedAt: string | null
  createdAt: string
  updatedAt: string
}

export type ReportListResponse = {
  content: ReportListItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type ReportVersionSummary = {
  reportId: string
  versionNo: number
  title: string
  summary: string | null
  periodFrom: string
  periodTo: string
  llmModel: string | null
  promptVersion: string | null
  changeNote: string | null
  creatorType: ReportCreatorType
  createdBy: string | null
  createdAt: string
}

export type ReportVersion = ReportVersionSummary & {
  content: string
  statistics: ReportStatisticsSnapshot
}

export type ReportVersionListResponse = {
  content: ReportVersionSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type ReportDetail = ReportListItem & {
  filterCriteria: ReportFilterCriteria
  currentVersion: ReportVersion
}

export type ReportSortField = 'createdAt' | 'updatedAt' | 'title'
export type ReportSortDirection = 'asc' | 'desc'
export type ReportSort = `${ReportSortField},${ReportSortDirection}`

export type ReportListFilters = {
  page?: number
  size?: number
  search?: string
  statuses?: ReportStatus[]
  reportType?: ReportType
  generationMethod?: ReportGenerationMethod
  sort?: ReportSort
}
