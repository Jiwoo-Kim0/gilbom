import { apiJson } from '../../shared/api/apiClient'

export type IssuedMqttCredential = {
  credentialId: string
  credentialType: string
  connection: {
    brokerUri: string
    username: string
    password: string
    clientId: string
  }
  expiresAt: string | null
}

export function issueRobotCredential(
  robotId: string,
  idempotencyKey: string,
): Promise<IssuedMqttCredential> {
  return apiJson(
    `/api/v1/robots/${encodeURIComponent(robotId)}/credentials`,
    {
      method: 'POST',
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
      csrfRetry: 'replay-safe',
    },
  )
}
