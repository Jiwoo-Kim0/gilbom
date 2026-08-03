import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { login } from './authApi'
import { useAuthentication } from './authenticationContext'
import { AuthCardHeader, ErrorNotice, FormField, SuccessNotice } from './AuthFormParts'
import type { ServerLoginState } from './authTypes'
import { validateEmail, validatePassword } from './authValidation'

const stateRoutes: Record<ServerLoginState, string> = {
  PASSWORD_CHANGE_REQUIRED: '/auth/password-change',
  MFA_ENROLLMENT_REQUIRED: '/auth/mfa-enrollment',
  MFA_REQUIRED: '/auth/mfa',
}

type LoginLocationState = {
  notice?: string
}

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { acceptChallenge, completeAuthentication } = useAuthentication()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)
  const notice = (location.state as LoginLocationState | null)?.notice

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)

    const emailError = validateEmail(email)
    const passwordError = validatePassword(password)
    if (emailError !== null || passwordError !== null) {
      setError(new Error(emailError ?? passwordError ?? '입력값을 확인해주세요.'))
      return
    }

    setSubmitting(true)
    try {
      const response = await login({ email, password })
      setPassword('')
      if ('loginState' in response) {
        acceptChallenge(response)
        navigate(stateRoutes[response.loginState], { replace: true })
      } else {
        completeAuthentication(response)
        navigate('/defects', { replace: true })
      }
    } catch (requestError) {
      setPassword('')
      setError(requestError)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="auth-card" aria-labelledby="login-title">
      <AuthCardHeader
        eyebrow="WELCOME BACK"
        title="관리자 로그인"
        description="등록된 이메일과 비밀번호로 1단계 인증을 진행합니다."
      />

      {notice ? <SuccessNotice>{notice}</SuccessNotice> : null}
      <ErrorNotice error={error} />

      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        <FormField label="이메일" htmlFor="login-email">
          <input
            id="login-email"
            name="email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="username"
            maxLength={254}
            required
          />
        </FormField>

        <FormField
          label="비밀번호"
          htmlFor="login-password"
          hint="10자 이상 24자 이하"
        >
          <input
            id="login-password"
            name="password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            minLength={10}
            maxLength={24}
            required
          />
        </FormField>

        <button className="primary-button auth-submit" type="submit" disabled={submitting}>
          {submitting ? '확인 중…' : '다음'}
        </button>
      </form>

      <p className="auth-card-footer">
        계정이 없나요? <Link to="/register">회원가입</Link>
      </p>
    </section>
  )
}
