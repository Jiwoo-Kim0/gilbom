import type {
  CommandResponse,
  CommandResultStatus,
  CommandStatus,
  CommandType,
} from './commandTypes'

export type CommandStatusTone =
  | 'progress'
  | 'ack'
  | 'success'
  | 'failure'
  | 'rejected'
  | 'expired'

export type CommandStatusPresentation = {
  label: string
  description: string
  tone: CommandStatusTone
  terminal: boolean
}

const COMMAND_TYPE_LABELS: Record<CommandType, string> = {
  START_MISSION: '임무 시작',
  PAUSE_MISSION: '일시정지',
  RESUME_MISSION: '임무 재개',
  COMPLETE_MISSION: '임무 종료',
  CANCEL_MISSION: '임무 취소',
  EMERGENCY_STOP: '긴급정지',
  CLEAR_EMERGENCY_STOP: '긴급정지 해제',
}

const COMMAND_STATUS_PRESENTATIONS: Record<
  CommandStatus,
  CommandStatusPresentation
> = {
  CREATED: {
    label: '요청 생성',
    description: 'Backend가 요청을 저장했으며 Broker 전송을 준비하고 있습니다.',
    tone: 'progress',
    terminal: false,
  },
  SENT: {
    label: '전송 완료',
    description: 'Broker로 전송했으며 Robot 수신 ACK를 기다리고 있습니다.',
    tone: 'progress',
    terminal: false,
  },
  ACCEPTED: {
    label: 'Robot 수신 ACK',
    description: 'Robot이 명령을 받았습니다. 아직 실행 완료 상태는 아닙니다.',
    tone: 'ack',
    terminal: false,
  },
  COMPLETED: {
    label: '실행 완료',
    description: 'Robot이 명령 실행을 완료했습니다.',
    tone: 'success',
    terminal: true,
  },
  FAILED: {
    label: '실행 실패',
    description: 'Robot이 명령을 실행했으나 업무 처리에 실패했습니다.',
    tone: 'failure',
    terminal: true,
  },
  REJECTED: {
    label: '실행 거절',
    description: 'Robot의 안전 조건 또는 업무 조건에 따라 명령이 거절되었습니다.',
    tone: 'rejected',
    terminal: true,
  },
  EXPIRED: {
    label: '응답 Timeout',
    description: '만료 시각까지 최종 Result가 확인되지 않았습니다.',
    tone: 'expired',
    terminal: true,
  },
  PUBLISH_FAILED: {
    label: 'Broker 발행 실패',
    description: '명령은 저장됐지만 Broker로 전달하지 못했습니다.',
    tone: 'failure',
    terminal: true,
  },
}

const RESULT_STATUS_LABELS: Record<CommandResultStatus, string> = {
  ACCEPTED: '수신 ACK',
  COMPLETED: '실행 완료',
  FAILED: '실행 실패',
  REJECTED: '실행 거절',
  EXPIRED: 'Robot 만료 응답',
}

export function getCommandTypeLabel(commandType: CommandType): string {
  return COMMAND_TYPE_LABELS[commandType]
}

export function getCommandStatusPresentation(
  status: CommandStatus,
): CommandStatusPresentation {
  return COMMAND_STATUS_PRESENTATIONS[status]
}

export function getCommandResultLabel(status: CommandResultStatus): string {
  return RESULT_STATUS_LABELS[status]
}

export function getCommandOperatorGuidance(
  command: CommandResponse,
): string | null {
  switch (command.commandStatus) {
    case 'FAILED':
      return '실패 원인과 현재 Robot·Mission 상태를 확인한 뒤 새 명령 여부를 판단하세요.'
    case 'REJECTED':
      return '안전 조건과 거절 사유를 해소하기 전에는 같은 명령을 반복하지 마세요.'
    case 'EXPIRED':
      return '실행 여부가 불명확할 수 있습니다. Robot·Mission 정본을 확인한 뒤 재시도하세요.'
    case 'PUBLISH_FAILED':
      return 'Robot 상태와 Broker 연결을 확인한 뒤 새 사용자 시도로 다시 요청하세요.'
    default:
      return null
  }
}

export function formatCommandDateTime(value: string | null): string {
  if (value === null) {
    return '—'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'medium',
    hour12: false,
  }).format(parsed)
}
