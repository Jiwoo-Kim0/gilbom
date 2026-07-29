import { apiJson } from '../../shared/api/apiClient'
import type {
  RobotListResponse,
  RobotMapResponse,
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
