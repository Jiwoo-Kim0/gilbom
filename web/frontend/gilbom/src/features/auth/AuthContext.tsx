import {
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  type ReactNode,
} from 'react'
import {
  clearCsrfToken,
  replaceCsrfToken,
  requireCsrfToken,
} from '../../shared/api/csrfTokenStore'
import {
  ApiError,
  subscribeToUnauthorized,
} from '../../shared/api/apiClient'
import {
  getAuthenticationSessionState,
  getCurrentUser,
  logout as requestLogout,
} from './authApi'
import type {
  AuthenticatedUser,
  AuthenticationResponse,
  AuthenticationStatus,
  LoginChallenge,
  ServerLoginState,
} from './authTypes'
import {
  AuthenticationContext,
  type AuthenticationContextValue,
} from './authenticationContext'

type AuthenticationState = {
  status: AuthenticationStatus
  user: AuthenticatedUser | null
  challengeExpiresAt: string | null
  sessionExpiresAt: string | null
}

type AuthenticationAction =
  | { type: 'RESTORE_AUTHENTICATED'; user: AuthenticatedUser }
  | { type: 'RESTORE_ANONYMOUS' }
  | { type: 'RESTORE_CHALLENGE'; challenge: LoginChallenge }
  | { type: 'CHALLENGE_RECEIVED'; challenge: LoginChallenge }
  | { type: 'AUTHENTICATION_COMPLETED'; response: AuthenticationResponse }
  | { type: 'SIGNED_OUT' }

const initialState: AuthenticationState = {
  status: 'BOOTSTRAPPING',
  user: null,
  challengeExpiresAt: null,
  sessionExpiresAt: null,
}

const challengeTransitions: Record<
  Exclude<AuthenticationStatus, 'BOOTSTRAPPING' | 'AUTHENTICATED'>,
  readonly ServerLoginState[]
> = {
  ANONYMOUS: [
    'PASSWORD_CHANGE_REQUIRED',
    'MFA_ENROLLMENT_REQUIRED',
    'MFA_REQUIRED',
  ],
  PASSWORD_CHANGE_REQUIRED: ['MFA_ENROLLMENT_REQUIRED'],
  MFA_ENROLLMENT_REQUIRED: [],
  MFA_REQUIRED: [],
}

function reducer(
  state: AuthenticationState,
  action: AuthenticationAction,
): AuthenticationState {
  switch (action.type) {
    case 'RESTORE_AUTHENTICATED':
      return {
        status: 'AUTHENTICATED',
        user: action.user,
        challengeExpiresAt: null,
        sessionExpiresAt: null,
      }
    case 'RESTORE_ANONYMOUS':
    case 'SIGNED_OUT':
      return {
        status: 'ANONYMOUS',
        user: null,
        challengeExpiresAt: null,
        sessionExpiresAt: null,
      }
    case 'RESTORE_CHALLENGE':
      return {
        status: action.challenge.loginState,
        user: null,
        challengeExpiresAt: action.challenge.challengeExpiresAt,
        sessionExpiresAt: null,
      }
    case 'CHALLENGE_RECEIVED': {
      if (state.status === 'BOOTSTRAPPING' || state.status === 'AUTHENTICATED') {
        return state
      }
      if (!challengeTransitions[state.status].includes(action.challenge.loginState)) {
        return state
      }
      return {
        status: action.challenge.loginState,
        user: null,
        challengeExpiresAt: action.challenge.challengeExpiresAt,
        sessionExpiresAt: null,
      }
    }
    case 'AUTHENTICATION_COMPLETED':
      if (
        state.status !== 'MFA_REQUIRED'
        && state.status !== 'MFA_ENROLLMENT_REQUIRED'
      ) {
        return state
      }
      return {
        status: 'AUTHENTICATED',
        user: action.response.user,
        challengeExpiresAt: null,
        sessionExpiresAt: action.response.sessionExpiresAt,
      }
  }
}

export function AuthenticationProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState)
  const restoredRef = useRef(false)

  const clearAuthentication = useCallback(() => {
    clearCsrfToken()
    dispatch({ type: 'SIGNED_OUT' })
  }, [])

  const refreshAuthentication = useCallback(async (): Promise<boolean> => {
    try {
      const sessionState = await getAuthenticationSessionState()
      if (sessionState.authenticationState === 'AUTHENTICATED') {
        const user = await getCurrentUser()
        dispatch({ type: 'RESTORE_AUTHENTICATED', user })
        return true
      }
      if (
        (
          sessionState.authenticationState === 'PASSWORD_CHANGE_REQUIRED'
          || sessionState.authenticationState === 'MFA_ENROLLMENT_REQUIRED'
          || sessionState.authenticationState === 'MFA_REQUIRED'
        )
        && sessionState.challengeExpiresAt !== null
      ) {
        dispatch({
          type: 'RESTORE_CHALLENGE',
          challenge: {
            loginState: sessionState.authenticationState,
            challengeExpiresAt: sessionState.challengeExpiresAt,
          },
        })
        return false
      }
      clearCsrfToken()
      dispatch({ type: 'RESTORE_ANONYMOUS' })
      return false
    } catch {
      clearCsrfToken()
      dispatch({ type: 'RESTORE_ANONYMOUS' })
      return false
    }
  }, [])

  useEffect(() => {
    if (restoredRef.current) {
      return
    }
    restoredRef.current = true
    void refreshAuthentication()
  }, [refreshAuthentication])

  useEffect(
    () => subscribeToUnauthorized(clearAuthentication),
    [clearAuthentication],
  )

  useEffect(() => {
    if (state.challengeExpiresAt === null) {
      return
    }
    const remaining = Date.parse(state.challengeExpiresAt) - Date.now()
    if (remaining <= 0) {
      clearAuthentication()
      return
    }
    const timer = window.setTimeout(
      clearAuthentication,
      Math.min(remaining, 2_147_483_647),
    )
    return () => window.clearTimeout(timer)
  }, [clearAuthentication, state.challengeExpiresAt])

  const acceptChallenge = useCallback((challenge: LoginChallenge) => {
    dispatch({ type: 'CHALLENGE_RECEIVED', challenge })
  }, [])

  const completeAuthentication = useCallback(
    (response: AuthenticationResponse) => {
      replaceCsrfToken(response.csrfToken)
      dispatch({ type: 'AUTHENTICATION_COMPLETED', response })
    },
    [],
  )

  const signOut = useCallback(async () => {
    try {
      await requestLogout()
      clearAuthentication()
      try {
        // 폐기된 인증 세션의 토큰 대신 새 익명 세션에 결합된 토큰을 즉시 준비한다.
        await requireCsrfToken()
      } catch {
        // 로그아웃 자체는 완료됐다. 다음 상태 변경 요청이 토큰 발급을 다시 시도한다.
        clearCsrfToken()
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        clearAuthentication()
      }
      throw error
    }
  }, [clearAuthentication])

  const value = useMemo<AuthenticationContextValue>(() => ({
    ...state,
    acceptChallenge,
    completeAuthentication,
    refreshAuthentication,
    signOut,
    clearAuthentication,
  }), [
    state,
    acceptChallenge,
    completeAuthentication,
    refreshAuthentication,
    signOut,
    clearAuthentication,
  ])

  return (
    <AuthenticationContext.Provider value={value}>
      {children}
    </AuthenticationContext.Provider>
  )
}
