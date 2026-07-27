export type RoleSummary = {
  roleCode: string
  roleName: string
}

export type AuthenticatedUser = {
  userId: string
  displayName: string
  roles: RoleSummary[]
  permissions: string[]
}

export type ServerLoginState =
  | 'PASSWORD_CHANGE_REQUIRED'
  | 'MFA_ENROLLMENT_REQUIRED'
  | 'MFA_REQUIRED'

export type AuthenticationStatus =
  | 'BOOTSTRAPPING'
  | 'ANONYMOUS'
  | ServerLoginState
  | 'AUTHENTICATED'

export type LoginChallenge = {
  loginState: ServerLoginState
  challengeExpiresAt: string
}

export type AuthenticationResponse = {
  user: AuthenticatedUser
  csrfToken: string
  sessionExpiresAt: string
  recoveryCodeUsed: boolean
}

export type MfaEnrollmentConfirmationResponse = AuthenticationResponse & {
  recoveryCodes: string[]
}

export type TotpEnrollment = {
  otpauthUri: string
  manualEntryKey: string
  enrollmentExpiresAt: string
}

export type MyProfile = {
  userId: string
  email: string
  displayName: string
  departmentName: string | null
  jobTitle: string | null
  officeName: string | null
  roles: RoleSummary[]
  permissions: string[]
  mfaStatus: 'ENROLLED' | 'ENROLLMENT_PENDING' | 'NOT_ENROLLED'
  passwordChangedAt: string | null
  lastLoginAt: string | null
}

export type PublicRegistrationResponse = {
  accountStatus: 'PENDING_APPROVAL'
  nextAction: 'LOGIN'
}

