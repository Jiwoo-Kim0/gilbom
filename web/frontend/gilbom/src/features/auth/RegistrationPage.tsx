import { CheckCircle2, CircleAlert } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  checkEmailAvailability,
  registerAccount,
} from './authApi'
import { AuthCardHeader, ErrorNotice, FormField } from './AuthFormParts'
import {
  validateDisplayName,
  validateEmail,
  validatePassword,
} from './authValidation'

type Availability =
  | { email: string; available: true }
  | { email: string; available: false }
  | null

export function RegistrationPage() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [availability, setAvailability] = useState<Availability>(null)
  const [checking, setChecking] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function handleAvailabilityCheck() {
    setError(null)
    const emailError = validateEmail(email)
    if (emailError !== null) {
      setError(new Error(emailError))
      return
    }
    setChecking(true)
    try {
      const result = await checkEmailAvailability(email)
      setAvailability({ email, available: result.available })
    } catch (requestError) {
      setAvailability(null)
      setError(requestError)
    } finally {
      setChecking(false)
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)

    const validationError = (
      validateEmail(email)
      ?? validateDisplayName(displayName)
      ?? validatePassword(password)
    )
    if (validationError !== null) {
      setError(new Error(validationError))
      return
    }
    if (password !== passwordConfirmation) {
      setError(new Error('비밀번호 확인이 일치하지 않습니다.'))
      return
    }
    if (
      availability === null
      || availability.email !== email
      || !availability.available
    ) {
      setError(new Error('현재 이메일의 중복 확인을 완료해주세요.'))
      return
    }

    setSubmitting(true)
    try {
      const response = await registerAccount({ email, displayName, password })
      if (response.accountStatus !== 'PENDING_APPROVAL' || response.nextAction !== 'LOGIN') {
        throw new Error('회원가입 응답 상태를 확인할 수 없습니다.')
      }
      setPassword('')
      setPasswordConfirmation('')
      navigate('/login', {
        replace: true,
        state: {
          notice: '회원가입이 접수되었습니다. 관리자 승인 후 로그인해주세요.',
        },
      })
    } catch (requestError) {
      setPassword('')
      setPasswordConfirmation('')
      setError(requestError)
    } finally {
      setSubmitting(false)
    }
  }

  const availabilityMatches = availability?.email === email

  return (
    <section className="auth-card auth-card-wide" aria-labelledby="registration-title">
      <AuthCardHeader
        eyebrow="CREATE ACCOUNT"
        title="회원가입"
        description="가입 계정은 승인 대기로 생성되며 자동 로그인되지 않습니다."
      />

      <ErrorNotice error={error} />

      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        <FormField label="이메일" htmlFor="registration-email">
          <div className="input-action-row">
            <input
              id="registration-email"
              name="email"
              type="email"
              value={email}
              onChange={(event) => {
                setEmail(event.target.value)
                setAvailability(null)
              }}
              autoComplete="username"
              maxLength={254}
              required
            />
            <button
              className="secondary-button"
              type="button"
              onClick={() => void handleAvailabilityCheck()}
              disabled={checking}
            >
              {checking ? '확인 중…' : '중복 확인'}
            </button>
          </div>
          {availabilityMatches ? (
            <p
              className={availability.available ? 'availability-ok' : 'availability-denied'}
              role="status"
            >
              {availability.available
                ? <CheckCircle2 size={15} aria-hidden="true" />
                : <CircleAlert size={15} aria-hidden="true" />}
              {availability.available
                ? '사용 가능한 이메일입니다.'
                : '이미 사용 중인 이메일입니다.'}
            </p>
          ) : null}
        </FormField>

        <FormField label="이름" htmlFor="registration-name">
          <input
            id="registration-name"
            name="displayName"
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            autoComplete="name"
            maxLength={100}
            required
          />
        </FormField>

        <div className="auth-form-columns">
          <FormField
            label="비밀번호"
            htmlFor="registration-password"
            hint="조합 규칙 없이 10자 이상 24자 이하"
          >
            <input
              id="registration-password"
              name="password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="new-password"
              minLength={10}
              maxLength={24}
              required
            />
          </FormField>
          <FormField label="비밀번호 확인" htmlFor="registration-password-confirm">
            <input
              id="registration-password-confirm"
              name="passwordConfirmation"
              type="password"
              value={passwordConfirmation}
              onChange={(event) => setPasswordConfirmation(event.target.value)}
              autoComplete="new-password"
              minLength={10}
              maxLength={24}
              required
            />
          </FormField>
        </div>

        <button className="primary-button auth-submit" type="submit" disabled={submitting}>
          {submitting ? '가입 처리 중…' : '회원가입'}
        </button>
      </form>

      <p className="auth-card-footer">
        이미 계정이 있나요? <Link to="/login">로그인</Link>
      </p>
    </section>
  )
}

