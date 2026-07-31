export type CommandType =
  | 'START_MISSION'
  | 'PAUSE_MISSION'
  | 'RESUME_MISSION'
  | 'CANCEL_MISSION'
  | 'EMERGENCY_STOP'
  | 'CLEAR_EMERGENCY_STOP'

export type CommandStatus =
  | 'CREATED'
  | 'SENT'
  | 'ACCEPTED'
  | 'COMPLETED'
  | 'FAILED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'PUBLISH_FAILED'

export type CommandResultStatus =
  | 'ACCEPTED'
  | 'COMPLETED'
  | 'FAILED'
  | 'REJECTED'
  | 'EXPIRED'

export type CommandParameters = {
  missionId: string | null
  zoneId: string | null
  reason: string | null
}

type StartMissionCommandCreateRequest = {
  commandType: 'START_MISSION'
  parameters: {
    missionId: string
    zoneId: string
  }
}

type ResumeMissionCommandCreateRequest = {
  commandType: 'RESUME_MISSION'
  parameters: Record<string, never>
}

type ReasonCommandType = Exclude<
  CommandType,
  'START_MISSION' | 'RESUME_MISSION'
>

type ReasonCommandCreateRequest = {
  commandType: ReasonCommandType
  parameters: {
    reason: string
  }
}

export type CommandCreateRequest =
  | StartMissionCommandCreateRequest
  | ResumeMissionCommandCreateRequest
  | ReasonCommandCreateRequest

export type CommandResponse = {
  commandId: string
  robotId: string
  mqttId: string
  commandType: CommandType
  commandStatus: CommandStatus
  parameters: CommandParameters
  requestedByType: 'ADMIN' | 'SYSTEM'
  requestedByUserId: string | null
  requestedByUserCode: string | null
  issuedAt: string
  expiresAt: string
  sentAt: string | null
  acceptedAt: string | null
  completedAt: string | null
  publishAttemptCount: number
  lastErrorCode: string | null
  createdAt: string
  updatedAt: string
}

export type CommandListResponse = {
  content: CommandResponse[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type CommandResultResponse = {
  resultId: string
  commandId: string
  robotId: string
  resultStatus: CommandResultStatus
  robotReceivedAt: string
  robotCompletedAt: string | null
  reasonCode: string
  resultingMode: string
  resultingReasonCode: string
  serverReceivedAt: string
}

export type CommandResultListResponse = {
  results: CommandResultResponse[]
}
