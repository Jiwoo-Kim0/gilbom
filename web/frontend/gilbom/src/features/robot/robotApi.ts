import { apiJson, jsonBody } from '../../shared/api/apiClient'
import type {
  RobotDetail,
  RobotListResponse,
  RobotMapResponse,
  RobotRegistrationRequest,
  RobotRegistrationResponse,
  RobotSummary,
} from './robotTypes'

const ROBOT_BASE_PATH = '/api/v1/robots'

export function getRobots(
  page: number,
  size: number,
  search: string,
): Promise<RobotListResponse> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  })
  const normalizedSearch = search.trim()
  if (normalizedSearch.length > 0) {
    query.set('search', normalizedSearch)
  }
  return apiJson(`${ROBOT_BASE_PATH}?${query}`)
}

export function getRobotSummary(): Promise<RobotSummary> {
  return apiJson(`${ROBOT_BASE_PATH}/summary`)
}

export function getRobotMap(): Promise<RobotMapResponse> {
  return apiJson(`${ROBOT_BASE_PATH}/map`)
}

export function registerRobot(
  input: RobotRegistrationRequest,
): Promise<RobotRegistrationResponse> {
  return apiJson(ROBOT_BASE_PATH, {
    method: 'POST',
    headers: {
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: jsonBody(input),
    csrfRetry: 'replay-safe',
  })
}

export function getRobot(robotId: string): Promise<RobotDetail> {
  return apiJson(`${ROBOT_BASE_PATH}/${encodeURIComponent(robotId)}`)
}
