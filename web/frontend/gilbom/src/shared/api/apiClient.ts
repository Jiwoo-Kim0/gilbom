import {
  clearCsrfToken,
  requireCsrfToken,
} from './csrfTokenStore'

const CSRF_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

export async function apiFetch(
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  const requestMethod = (
    init.method
    ?? (input instanceof Request ? input.method : 'GET')
  ).toUpperCase()
  const headers = new Headers(init.headers)

  if (CSRF_METHODS.has(requestMethod)) {
    headers.set('X-CSRF-TOKEN', await requireCsrfToken())
  }

  const response = await fetch(input, {
    ...init,
    method: requestMethod,
    headers,
    credentials: 'include',
  })

  if (response.status === 401) {
    clearCsrfToken()
  }

  return response
}
