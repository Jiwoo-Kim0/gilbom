import { createContext, useContext } from 'react'
import type {
  AuthenticatedUser,
  AuthenticationStatus,
  LoginChallenge,
  AuthenticationResponse,
} from './authTypes'

export type AuthenticationContextValue = {
  status: AuthenticationStatus
  user: AuthenticatedUser | null
  challengeExpiresAt: string | null
  sessionExpiresAt: string | null
  acceptChallenge: (challenge: LoginChallenge) => void
  completeAuthentication: (response: AuthenticationResponse) => void
  refreshAuthentication: () => Promise<boolean>
  signOut: () => Promise<void>
  clearAuthentication: () => void
}

export const AuthenticationContext =
  createContext<AuthenticationContextValue | null>(null)

export function useAuthentication(): AuthenticationContextValue {
  const context = useContext(AuthenticationContext)
  if (context === null) {
    throw new Error('useAuthentication must be used inside AuthenticationProvider')
  }
  return context
}

