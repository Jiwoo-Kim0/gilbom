import type {
  MissionStatus,
  RobotOption,
} from './missionTypes.ts'

const DELETABLE_MISSION_STATUSES: ReadonlySet<MissionStatus> = new Set([
  'CREATED',
])

export function isMissionDeletable(status: MissionStatus): boolean {
  return DELETABLE_MISSION_STATUSES.has(status)
}

export function isRobotMissionAssignable(robot: RobotOption): boolean {
  return robot.operationalStatus === 'ACTIVE' && robot.missionAssignable
}
