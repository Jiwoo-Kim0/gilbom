import {
  apiFetch,
  apiJson,
  jsonBody,
} from '../../shared/api/apiClient.ts'
import type {
  Mission,
  MissionCreateInput,
  MissionFilters,
  MissionListResponse,
  MissionMapResponse,
  MissionUpdateInput,
  MissionStatusHistoryResponse,
  PatrolArea,
  PatrolAreaCodeAvailability,
  PatrolAreaCreateInput,
  PatrolAreaLifecycleStatus,
  PatrolAreaListResponse,
  PatrolAreaUpdateInput,
  PatrolRouteListResponse,
  RobotListResponse,
} from './missionTypes.ts'

const MISSION_BASE_PATH = '/api/v1/missions'

function appendInstant(
  query: URLSearchParams,
  name: string,
  value: string,
): void {
  if (value.length > 0) {
    query.set(name, new Date(value).toISOString())
  }
}

export function getMissions(
  page: number,
  size: number,
  filters: MissionFilters,
): Promise<MissionListResponse> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
    scope: filters.scope,
  })

  if (filters.robotId.length > 0) {
    query.set('robotId', filters.robotId)
  }
  if (filters.patrolAreaId.length > 0) {
    query.set('patrolAreaId', filters.patrolAreaId)
  }
  if (filters.status.length > 0) {
    query.set('status', filters.status)
  }
  appendInstant(query, 'startedFrom', filters.startedFrom)
  appendInstant(query, 'startedTo', filters.startedTo)

  return apiJson(`${MISSION_BASE_PATH}?${query}`)
}

export function getMission(missionId: string): Promise<Mission> {
  return apiJson(`${MISSION_BASE_PATH}/${encodeURIComponent(missionId)}`)
}

export function getMissionHistory(
  missionId: string,
): Promise<MissionStatusHistoryResponse> {
  return apiJson(
    `${MISSION_BASE_PATH}/${encodeURIComponent(missionId)}/history`,
  )
}

export function getMissionMap(
  missionId: string,
): Promise<MissionMapResponse> {
  return apiJson(
    `${MISSION_BASE_PATH}/${encodeURIComponent(missionId)}/map?limit=5000`,
  )
}

export function createMission(input: MissionCreateInput): Promise<Mission> {
  return apiJson(MISSION_BASE_PATH, {
    method: 'POST',
    body: jsonBody(input),
  })
}

export function updateMission(
  missionId: string,
  input: MissionUpdateInput,
): Promise<Mission> {
  return apiJson(`${MISSION_BASE_PATH}/${encodeURIComponent(missionId)}`, {
    method: 'PATCH',
    body: jsonBody(input),
  })
}

export async function deleteMission(missionId: string): Promise<void> {
  await apiFetch(
    `${MISSION_BASE_PATH}/${encodeURIComponent(missionId)}`,
    { method: 'DELETE' },
  )
}

export function getMissionRobotOptions(): Promise<RobotListResponse> {
  return apiJson('/api/v1/robots?page=0&size=100')
}

export function getPatrolAreas(): Promise<PatrolAreaListResponse> {
  return apiJson('/api/v1/patrol-areas')
}

export function checkPatrolAreaCodeAvailability(
  areaCode: string,
): Promise<PatrolAreaCodeAvailability> {
  const query = new URLSearchParams({ areaCode })
  return apiJson(`/api/v1/patrol-areas/code-availability?${query}`)
}

export function createPatrolArea(
  input: PatrolAreaCreateInput,
): Promise<PatrolArea> {
  return apiJson('/api/v1/patrol-areas', {
    method: 'POST',
    body: jsonBody(input),
  })
}

export function updatePatrolArea(
  patrolAreaId: string,
  input: PatrolAreaUpdateInput,
): Promise<PatrolArea> {
  return apiJson(
    `/api/v1/patrol-areas/${encodeURIComponent(patrolAreaId)}`,
    {
      method: 'PATCH',
      body: jsonBody(input),
    },
  )
}

export function changePatrolAreaStatus(
  patrolAreaId: string,
  lifecycleStatus: PatrolAreaLifecycleStatus,
): Promise<PatrolArea> {
  return apiJson(
    `/api/v1/patrol-areas/${encodeURIComponent(patrolAreaId)}/status`,
    {
      method: 'PATCH',
      body: jsonBody({ lifecycleStatus }),
    },
  )
}

export function getPatrolRoutes(
  patrolAreaId: string,
): Promise<PatrolRouteListResponse> {
  return apiJson(
    `/api/v1/patrol-areas/${encodeURIComponent(patrolAreaId)}/routes`,
  )
}
