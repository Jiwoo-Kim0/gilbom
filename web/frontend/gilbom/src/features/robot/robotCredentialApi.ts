import { apiJson, jsonBody } from '../../shared/api/apiClient'

export type IssuedMqttCredential = {
  robotId: string
  mqttId: string
  credentialId: string
  credentialType: string
  mqtt: {
    brokerUri: string
    username: string
    password: string
    clientId: string
  }
  imageUpload: {
    url: string
    apiKey: string
  }
  expiresAt: string | null
}

export type MqttCredentialRegistryStatus = {
  robotId: string
  mqttId: string
  registryStatus: 'IN_SYNC' | 'MISSING' | 'MISMATCH' | 'ORPHANED'
  driftDetected: boolean
  activeCredentialId: string | null
  activeCredentialVersion: number | null
  checkedAt: string
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

export function reconcileRobotCredentialRegistry(
  robotId: string,
  reason: string,
): Promise<MqttCredentialRegistryStatus> {
  return apiJson(
    `/api/v1/robots/${encodeURIComponent(robotId)}`
      + '/credentials/registry-reconcile',
    {
      method: 'POST',
      body: jsonBody({ reason }),
      csrfRetry: 'replay-safe',
    },
  )
}
