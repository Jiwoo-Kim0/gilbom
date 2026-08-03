import {
  apiFetch,
  apiJson,
  jsonBody,
} from '../../shared/api/apiClient'
import type {
  AuthenticatedUser,
  AuthenticationResponse,
  AuthenticationSessionState,
  LoginChallenge,
  LoginResponse,
  MfaEnrollmentConfirmationResponse,
  MyProfile,
  PublicRegistrationResponse,
  TotpEnrollment,
} from './authTypes'

const AUTH_RETRY_OPTIONS = {
  notifyUnauthorized: false,
} as const

export function getCurrentUser(): Promise<AuthenticatedUser> {
  return apiJson<AuthenticatedUser>('/api/v1/auth/me', {
    method: 'GET',
    notifyUnauthorized: false,
  })
}

export function getAuthenticationSessionState(): Promise<AuthenticationSessionState> {
  return apiJson<AuthenticationSessionState>('/api/v1/auth/session-state', {
    method: 'GET',
    notifyUnauthorized: false,
  })
}

export function checkEmailAvailability(email: string): Promise<{ available: boolean }> {
  return apiJson('/api/v1/auth/email-availability', {
    method: 'POST',
    body: jsonBody({ email }),
    ...AUTH_RETRY_OPTIONS,
  })
}

export function registerAccount(input: {
  email: string
  password: string
  displayName: string
}): Promise<PublicRegistrationResponse> {
  return apiJson('/api/v1/auth/registrations', {
    method: 'POST',
    body: jsonBody(input),
    ...AUTH_RETRY_OPTIONS,
  })
}

export function login(input: {
  email: string
  password: string
}): Promise<LoginResponse> {
  return apiJson('/api/v1/auth/login', {
    method: 'POST',
    body: jsonBody(input),
    ...AUTH_RETRY_OPTIONS,
  })
}

export function changeInitialPassword(newPassword: string): Promise<LoginChallenge> {
  return apiJson('/api/v1/auth/password/initial-change', {
    method: 'POST',
    body: jsonBody({ newPassword }),
    ...AUTH_RETRY_OPTIONS,
  })
}

export function enrollTotp(): Promise<TotpEnrollment> {
  return apiJson('/api/v1/auth/mfa/totp/enroll', {
    method: 'POST',
  })
}

export function confirmTotpEnrollment(
  code: string,
): Promise<MfaEnrollmentConfirmationResponse> {
  return apiJson('/api/v1/auth/mfa/totp/confirm', {
    method: 'POST',
    body: jsonBody({ code }),
    ...AUTH_RETRY_OPTIONS,
  })
}

export function verifyTotp(code: string): Promise<AuthenticationResponse> {
  return apiJson('/api/v1/auth/mfa/totp/verify', {
    method: 'POST',
    body: jsonBody({ code }),
    ...AUTH_RETRY_OPTIONS,
  })
}

export function verifyRecoveryCode(
  recoveryCode: string,
): Promise<AuthenticationResponse> {
  return apiJson('/api/v1/auth/mfa/recovery', {
    method: 'POST',
    body: jsonBody({ recoveryCode }),
    ...AUTH_RETRY_OPTIONS,
  })
}

export function getMyProfile(): Promise<MyProfile> {
  return apiJson('/api/v1/my/profile', {
    method: 'GET',
  })
}

export async function changeMyPassword(input: {
  currentPassword: string
  newPassword: string
}): Promise<void> {
  await apiFetch('/api/v1/my/password', {
    method: 'PATCH',
    body: jsonBody(input),
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    // 현재 비밀번호 불일치도 동일한 401이므로 호출 화면에서 세션을 재확인한다.
    notifyUnauthorized: false,
  })
}

export async function logout(): Promise<void> {
  await apiFetch('/api/v1/auth/logout', {
    method: 'POST',
    notifyUnauthorized: false,
  })
}

