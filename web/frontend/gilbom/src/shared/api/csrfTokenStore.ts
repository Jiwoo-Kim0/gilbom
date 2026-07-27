type CsrfTokenResponse = {
  headerName: 'X-CSRF-TOKEN'
  token: string
}

let csrfToken: string | null = null
let pendingRequest: Promise<string> | null = null

export function clearCsrfToken(): void {
  csrfToken = null
  pendingRequest = null
}

export function replaceCsrfToken(replacement: string): void {
  if (replacement.length === 0) {
    throw new Error('CSRF token replacement must not be empty')
  }
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

  pendingRequest = fetch('/api/v1/auth/csrf-token', {
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
      csrfToken = payload.token
      return payload.token
    })
    .finally(() => {
      pendingRequest = null
    })

  return pendingRequest
}
