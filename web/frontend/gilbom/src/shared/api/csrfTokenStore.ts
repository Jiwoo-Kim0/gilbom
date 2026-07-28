type CsrfTokenResponse = {
  headerName: 'X-CSRF-TOKEN'
  token: string
}

// 의도적으로 모듈 메모리에만 두며 영속 브라우저 저장소나 별도 쿠키에 쓰지 않는다.
let csrfToken: string | null = null
let pendingRequest: Promise<string> | null = null
let tokenGeneration = 0

export function clearCsrfToken(): void {
  tokenGeneration += 1
  csrfToken = null
  pendingRequest = null
}

export function replaceCsrfToken(replacement: string): void {
  if (replacement.trim().length === 0) {
    throw new Error('CSRF token replacement must not be empty')
  }
  tokenGeneration += 1
  csrfToken = replacement
  pendingRequest = null
}

export async function requireCsrfToken(): Promise<string> {
  if (csrfToken !== null) {
    return csrfToken
  }
  if (pendingRequest !== null) {
    return pendingRequest
  }

  const requestGeneration = tokenGeneration
  const request = fetch('/api/v1/auth/csrf-token', {
    method: 'GET',
    credentials: 'include',
    cache: 'no-store',
    headers: {
      Accept: 'application/json',
    },
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error('CSRF token request failed')
      }
      const payload = (await response.json()) as Partial<CsrfTokenResponse>
      if (
        payload.headerName !== 'X-CSRF-TOKEN'
        || typeof payload.token !== 'string'
        || payload.token.length === 0
      ) {
        throw new Error('CSRF token response is invalid')
      }
      if (tokenGeneration !== requestGeneration) {
        throw new Error('CSRF token request was superseded')
      }
      csrfToken = payload.token
      return payload.token
    })
    .finally(() => {
      if (pendingRequest === request) {
        pendingRequest = null
      }
    })

  pendingRequest = request
  return request
}
