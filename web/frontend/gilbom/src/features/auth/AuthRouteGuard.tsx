import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuthentication } from './authenticationContext'
import type { AuthenticationStatus } from './authTypes'

const statusRoutes: Record<Exclude<AuthenticationStatus, 'BOOTSTRAPPING'>, string> = {
  ANONYMOUS: '/login',
  PASSWORD_CHANGE_REQUIRED: '/auth/password-change',
  MFA_ENROLLMENT_REQUIRED: '/auth/mfa-enrollment',
  MFA_REQUIRED: '/auth/mfa',
  AUTHENTICATED: '/defects',
}

function AuthenticationLoading() {
  return (
    <main className="auth-loading" aria-live="polite">
      <span className="loading-spinner" aria-hidden="true" />
      <p>인증 상태를 확인하고 있습니다.</p>
    </main>
  )
}

export function RequireAuthenticated({ children }: { children: ReactNode }) {
  const { status } = useAuthentication()
  const location = useLocation()

  if (status === 'BOOTSTRAPPING') {
    return <AuthenticationLoading />
  }
  if (status !== 'AUTHENTICATED') {
    return (
      <Navigate
        replace
        to={statusRoutes[status]}
        state={{ from: location.pathname }}
      />
    )
  }
  return children
}

export function RequireAuthenticationState({
  allowed,
  children,
}: {
  allowed: readonly AuthenticationStatus[]
  children: ReactNode
}) {
  const { status } = useAuthentication()

  if (status === 'BOOTSTRAPPING') {
    return <AuthenticationLoading />
  }
  if (!allowed.includes(status)) {
    return <Navigate replace to={statusRoutes[status]} />
  }
  return children
}
