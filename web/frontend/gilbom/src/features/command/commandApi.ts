import {
  apiJson,
  jsonBody,
} from '../../shared/api/apiClient.ts'
import type {
  CommandCreateRequest,
  CommandListResponse,
  CommandResponse,
  CommandResultListResponse,
} from './commandTypes.ts'

const COMMAND_BASE_PATH = '/api/v1/commands'
const ROBOT_BASE_PATH = '/api/v1/robots'
const REPLAY_SAFE_COMMAND = {
  csrfRetry: 'replay-safe',
} as const

type UuidFactory = () => string

function createUuid(): string {
  if (
    typeof globalThis.crypto === 'undefined'
    || typeof globalThis.crypto.randomUUID !== 'function'
  ) {
    throw new Error('이 브라우저에서는 안전한 UUID를 생성할 수 없습니다.')
  }
  return globalThis.crypto.randomUUID()
}

function commandFingerprint(
  robotId: string,
  input: CommandCreateRequest,
): string {
  return JSON.stringify({
    robotId,
    commandType: input.commandType,
    parameters: Object.fromEntries(
      Object.entries(input.parameters)
        .sort(([left], [right]) => left.localeCompare(right)),
    ),
  })
}

/**
 * 한 번의 사용자 명령 의도를 추적한다. 네트워크·503·CSRF 재시도 중에는 같은
 * 키를 유지하고, 대상이나 입력이 바뀌거나 요청이 성공한 뒤에만 새 키를 만든다.
 */
export class CommandCreateAttempt {
  private readonly uuidFactory: UuidFactory
  private fingerprint: string | null = null
  private idempotencyKey: string | null = null

  constructor(uuidFactory: UuidFactory = createUuid) {
    this.uuidFactory = uuidFactory
  }

  keyFor(robotId: string, input: CommandCreateRequest): string {
    const nextFingerprint = commandFingerprint(robotId, input)
    if (
      this.idempotencyKey === null
      || this.fingerprint !== nextFingerprint
    ) {
      this.fingerprint = nextFingerprint
      this.idempotencyKey = this.uuidFactory()
    }
    return this.idempotencyKey
  }

  markCompleted(idempotencyKey: string): void {
    if (this.idempotencyKey !== idempotencyKey) {
      return
    }
    this.reset()
  }

  reset(): void {
    this.fingerprint = null
    this.idempotencyKey = null
  }
}

function robotCommandPath(robotId: string): string {
  return `${ROBOT_BASE_PATH}/${encodeURIComponent(robotId)}/commands`
}

function commandPath(commandId: string): string {
  return `${COMMAND_BASE_PATH}/${encodeURIComponent(commandId)}`
}

export function listRobotCommands(
  robotId: string,
  page = 0,
  size = 20,
): Promise<CommandListResponse> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  })
  return apiJson(`${robotCommandPath(robotId)}?${query}`)
}

export async function createRobotCommand(
  robotId: string,
  input: CommandCreateRequest,
  attempt: CommandCreateAttempt,
): Promise<CommandResponse> {
  const idempotencyKey = attempt.keyFor(robotId, input)
  const command = await apiJson<CommandResponse>(robotCommandPath(robotId), {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    body: jsonBody(input),
    ...REPLAY_SAFE_COMMAND,
  })
  attempt.markCompleted(idempotencyKey)
  return command
}

export function getRobotCommand(
  commandId: string,
): Promise<CommandResponse> {
  return apiJson(commandPath(commandId))
}

export function getRobotCommandResults(
  commandId: string,
): Promise<CommandResultListResponse> {
  return apiJson(`${commandPath(commandId)}/results`)
}
