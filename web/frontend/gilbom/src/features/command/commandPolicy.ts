import type { Mission } from '../mission/missionTypes'
import type {
  CommandCreateRequest,
  CommandStatus,
  CommandType,
} from './commandTypes'

export type CommandTone = 'default' | 'danger'

export type CommandDefinition = {
  commandType: CommandType
  label: string
  impact: string
  tone: CommandTone
  requiresReason: boolean
  requiresSafetyConfirmation: boolean
  safetyMessage: string | null
}

export type CommandAvailability = {
  enabled: boolean
  reason: string | null
}

export type CommandPolicyState = {
  connectivityStatus: string
  operationalStatus: string
  robotMode: string | null
  missionStatus: string | null
  hasMissionTarget: boolean
  pendingCommandTypes: readonly CommandType[]
}

export const COMMAND_DEFINITIONS: readonly CommandDefinition[] = [
  {
    commandType: 'START_MISSION',
    label: '임무 시작',
    impact: '선택된 READY 임무를 로봇에 전달하고 순찰을 시작합니다.',
    tone: 'default',
    requiresReason: false,
    requiresSafetyConfirmation: false,
    safetyMessage: null,
  },
  {
    commandType: 'PAUSE_MISSION',
    label: '일시정지',
    impact: '현재 실행 중인 임무를 일시정지합니다.',
    tone: 'default',
    requiresReason: true,
    requiresSafetyConfirmation: false,
    safetyMessage: null,
  },
  {
    commandType: 'RESUME_MISSION',
    label: '임무 재개',
    impact: '일시정지된 임무를 이어서 실행합니다.',
    tone: 'default',
    requiresReason: false,
    requiresSafetyConfirmation: true,
    safetyMessage: '로봇 주변과 예정 경로에 장애물이 없는지 확인했습니다.',
  },
  {
    commandType: 'COMPLETE_MISSION',
    label: '임무 완료',
    impact: '로봇에 완료 명령을 보내고, 안전 정지 후 완료 Event가 수신되면 COMPLETED로 확정합니다.',
    tone: 'default',
    requiresReason: false,
    requiresSafetyConfirmation: true,
    safetyMessage: '로봇 주변에 위험 요소가 없고 완료 명령 후 안전하게 정지하는 절차를 확인했습니다.',
  },
  {
    commandType: 'CANCEL_MISSION',
    label: '임무 취소',
    impact: '수행할 수 없는 현재 임무를 취소(CANCELLED)합니다.',
    tone: 'default',
    requiresReason: true,
    requiresSafetyConfirmation: false,
    safetyMessage: null,
  },
  {
    commandType: 'EMERGENCY_STOP',
    label: '긴급정지',
    impact: '로봇을 즉시 SAFE_STOP 상태로 전환합니다.',
    tone: 'danger',
    requiresReason: true,
    requiresSafetyConfirmation: false,
    safetyMessage: '긴급 상황에서만 사용하며 현장 로봇의 동작이 즉시 중단됩니다.',
  },
  {
    commandType: 'CLEAR_EMERGENCY_STOP',
    label: '긴급정지 해제',
    impact: 'SAFE_STOP을 해제해 후속 임무 제어가 가능한 상태로 전환합니다.',
    tone: 'default',
    requiresReason: true,
    requiresSafetyConfirmation: true,
    safetyMessage: '정지 원인이 제거되었고 로봇 주변의 안전 조건을 확인했습니다.',
  },
] as const

export const IN_FLIGHT_COMMAND_STATUSES: readonly CommandStatus[] = [
  'CREATED',
  'SENT',
  'ACCEPTED',
] as const

function disabled(reason: string): CommandAvailability {
  return {
    enabled: false,
    reason,
  }
}

function enabled(): CommandAvailability {
  return {
    enabled: true,
    reason: null,
  }
}

export function getCommandAvailability(
  commandType: CommandType,
  state: CommandPolicyState,
): CommandAvailability {
  if (state.connectivityStatus !== 'ONLINE') {
    return disabled('로봇이 오프라인이어서 명령을 실행할 수 없습니다.')
  }
  if (state.operationalStatus !== 'ACTIVE') {
    return disabled('현재 운영 가능한 로봇이 아닙니다.')
  }
  if (state.pendingCommandTypes.includes(commandType)) {
    return disabled('같은 명령이 처리 중입니다.')
  }

  const safeStopped = state.robotMode === 'SAFE_STOP'
  if (safeStopped) {
    if (commandType === 'CLEAR_EMERGENCY_STOP') {
      return enabled()
    }
    if (
      commandType === 'CANCEL_MISSION'
      && (
        state.missionStatus === 'READY'
        || state.missionStatus === 'RUNNING'
        || state.missionStatus === 'PAUSED'
      )
    ) {
      return enabled()
    }
    return disabled('긴급정지를 해제한 뒤 실행할 수 있습니다.')
  }
  if (commandType === 'CLEAR_EMERGENCY_STOP') {
    return disabled('로봇이 SAFE_STOP 상태가 아닙니다.')
  }

  switch (commandType) {
    case 'START_MISSION':
      if (state.missionStatus !== 'READY') {
        return disabled('READY 임무에서만 시작할 수 있습니다.')
      }
      return state.hasMissionTarget
        ? enabled()
        : disabled('시작할 Mission 정보를 불러오지 못했습니다.')
    case 'PAUSE_MISSION':
      return state.missionStatus === 'RUNNING'
        ? enabled()
        : disabled('RUNNING 임무에서만 일시정지할 수 있습니다.')
    case 'RESUME_MISSION':
      return state.missionStatus === 'PAUSED'
        ? enabled()
        : disabled('PAUSED 임무에서만 재개할 수 있습니다.')
    case 'COMPLETE_MISSION':
      return state.missionStatus === 'RUNNING'
        ? enabled()
        : disabled('RUNNING 임무만 완료할 수 있습니다. 일시정지 상태라면 먼저 재개해주세요.')
    case 'CANCEL_MISSION':
      return (
        state.missionStatus === 'READY'
        || state.missionStatus === 'RUNNING'
        || state.missionStatus === 'PAUSED'
      )
        ? enabled()
        : disabled('READY·RUNNING·PAUSED 임무만 취소할 수 있습니다.')
    case 'EMERGENCY_STOP':
      return (
        state.missionStatus === 'RUNNING'
        || state.missionStatus === 'PAUSED'
      )
        ? enabled()
        : disabled('RUNNING 또는 PAUSED 임무에서만 긴급정지할 수 있습니다.')
  }
}

export function buildCommandRequest(
  commandType: CommandType,
  mission: Mission | null,
  reason: string,
): CommandCreateRequest {
  switch (commandType) {
    case 'START_MISSION':
      if (mission === null || mission.status !== 'READY') {
        throw new Error('시작할 READY Mission 정보가 없습니다.')
      }
      return {
        commandType,
        parameters: {
          missionId: mission.missionCode,
          zoneId: mission.patrolAreaCode,
        },
      }
    case 'RESUME_MISSION':
    case 'COMPLETE_MISSION':
      return {
        commandType,
        parameters: {},
      }
    case 'PAUSE_MISSION':
    case 'CANCEL_MISSION':
    case 'EMERGENCY_STOP':
    case 'CLEAR_EMERGENCY_STOP': {
      const normalizedReason = reason.trim()
      if (normalizedReason.length === 0) {
        throw new Error('명령 사유를 입력해주세요.')
      }
      return {
        commandType,
        parameters: {
          reason: normalizedReason,
        },
      }
    }
  }
}
