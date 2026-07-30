export type MissionStatus =
  | 'CREATED'
  | 'READY'
  | 'RUNNING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED'

export type GeoJsonCoordinate = [longitude: number, latitude: number]

export type GeoJsonPolygon = {
  type: 'Polygon'
  coordinates: GeoJsonCoordinate[][]
}

export type GeoJsonMultiPolygon = {
  type: 'MultiPolygon'
  coordinates: GeoJsonCoordinate[][][]
}

export type PatrolAreaBoundary = GeoJsonPolygon | GeoJsonMultiPolygon

export type MissionFilters = {
  robotId: string
  patrolAreaId: string
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
  plannedDurationSec: number
  elapsedSec: number | null
  remainingDurationSec: number
  progressObservedAt: string | null
  progressStale: boolean
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
  /** API·DB FK 호환 전용. 화면에서 사용자에게 노출하지 않는다. */
  patrolRouteId: string
  plannedDurationSec: number
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

export type MissionTrajectoryPoint = {
  messageId: string
  sampledAt: string
  receivedAt: string
  latitude: number
  longitude: number
  headingDeg: number | null
  moving: boolean
  speedMps: number | null
}

export type MissionMapCurrentPosition = {
  latitude: number
  longitude: number
  headingDeg: number | null
  moving: boolean | null
  speedMps: number | null
  connectivityStatus: 'ONLINE' | 'OFFLINE'
  observedAt: string | null
}

export type MissionMapResponse = {
  missionId: string
  patrolAreaId: string
  patrolAreaBoundary: PatrolAreaBoundary
  trajectory: MissionTrajectoryPoint[]
  currentPosition: MissionMapCurrentPosition | null
}

export type PatrolArea = {
  patrolAreaId: string
  areaCode: string
  areaName: string
  boundary: PatrolAreaBoundary
  lifecycleStatus: 'ACTIVE' | 'INACTIVE' | 'ARCHIVED'
  createdAt: string
  updatedAt: string
}

export type PatrolAreaCreateInput = {
  areaCode: string
  areaName: string
  boundary: PatrolAreaBoundary
  lifecycleStatus: 'ACTIVE' | 'INACTIVE'
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
