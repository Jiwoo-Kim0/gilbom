export type MissionStatus =
  | 'CREATED'
  | 'READY'
  | 'RUNNING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED'

export type MissionFilters = {
  robotId: string
  patrolAreaId: string
  patrolRouteId: string
  status: MissionStatus | ''
  startedFrom: string
  startedTo: string
}

export type Mission = {
  missionId: string
  missionCode: string
  robotId: string
  mqttId: string
  robotName: string
  patrolAreaId: string
  patrolAreaCode: string
  patrolAreaName: string
  patrolRouteId: string
  patrolRouteCode: string
  patrolRouteName: string
  patrolRouteVersion: number
  status: MissionStatus
  scheduledStartAt: string | null
  startedAt: string | null
  endedAt: string | null
  progressPercent: number
  estimatedCompletionAt: string | null
  completionReason: string | null
  resultDurationSec: number | null
  resultIncidentCount: number | null
  resultNormalDetectionCount: number | null
  resultPayload: Record<string, unknown> | null
  createdBy: string
  updatedBy: string
  createdAt: string
  updatedAt: string
}

export type MissionListResponse = {
  content: Mission[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type MissionCreateInput = {
  robotId: string
  patrolAreaId: string
  patrolRouteId: string
  scheduledStartAt: string | null
}

export type MissionStatusHistoryItem = {
  historyId: string
  transitionSource: 'MQTT' | 'ADMIN' | 'SYSTEM'
  beforeStatus: MissionStatus | null
  afterStatus: MissionStatus
  transitionReason: string | null
  changedBy: string | null
  changedByUserCode: string | null
  sourceEventId: string | null
  occurredAt: string
  receivedAt: string
  createdAt: string
}

export type MissionStatusHistoryResponse = {
  missionId: string
  history: MissionStatusHistoryItem[]
}

export type PatrolArea = {
  patrolAreaId: string
  areaCode: string
  areaName: string
  boundary: Record<string, unknown>
  lifecycleStatus: 'ACTIVE' | 'INACTIVE' | 'ARCHIVED'
  createdAt: string
  updatedAt: string
}

export type PatrolAreaListResponse = {
  areas: PatrolArea[]
}

export type PatrolRoute = {
  patrolRouteId: string
  patrolAreaId: string
  routeCode: string
  routeName: string
  routeVersion: number
  routeGeometry: Record<string, unknown>
  lifecycleStatus: 'DRAFT' | 'ACTIVE' | 'INACTIVE' | 'ARCHIVED'
  createdAt: string
  updatedAt: string
}

export type PatrolRouteListResponse = {
  routes: PatrolRoute[]
}

export type RobotOption = {
  robotId: string
  mqttId: string
  robotName: string
  serialNumber: string
  operationalStatus: string
  connectivityStatus: string
  overallHealthStatus: string
}

export type RobotListResponse = {
  content: RobotOption[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
