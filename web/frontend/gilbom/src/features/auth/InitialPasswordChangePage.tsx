import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { changeInitialPassword } from './authApi'
import { useAuthentication } from './authenticationContext'
import { AuthCardHeader, ErrorNotice, FormField } from './AuthFormParts'
import { validatePassword } from './authValidation'
import { SEOUL_TIME_ZONE } from '../../shared/time/seoulTime.ts'

export function InitialPasswordChangePage() {
  const navigate = useNavigate()
  const { acceptChallenge, challengeExpiresAt } = useAuthentication()
  const [newPassword, setNewPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)

    const passwordError = validatePassword(newPassword)
    if (passwordError !== null) {
      setError(new Error(passwordError))
      return
    }
    if (newPassword !== confirmation) {
      setError(new Error('비밀번호 확인이 일치하지 않습니다.'))
      return
    }

    setSubmitting(true)
    try {
      const challenge = await changeInitialPassword(newPassword)
      setNewPassword('')
      setConfirmation('')
      acceptChallenge(challenge)
      navigate('/auth/mfa-enrollment', { replace: true })
    } catch (requestError) {
      setNewPassword('')
      setConfirmation('')
      setError(requestError)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="auth-card" aria-labelledby="initial-password-title">
      <AuthCardHeader
        eyebrow="PASSWORD UPDATE"
        title="초기 비밀번호 변경"
        description="초기 비밀번호는 계속 사용할 수 없습니다. 새 비밀번호를 설정해주세요."
      />

      {challengeExpiresAt ? (
        <p className="challenge-expiry">
          이 단계는 {new Date(challengeExpiresAt).toLocaleTimeString('ko-KR', { timeZone: SEOUL_TIME_ZONE })}까지 유효합니다.
        </p>
      ) : null}
      <ErrorNotice error={error} />

      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        <FormField
          label="새 비밀번호"
          htmlFor="initial-new-password"
          hint="조합 규칙 없이 10자 이상 24자 이하"
        >
          <input
            id="initial-new-password"
            name="newPassword"
            type="password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            autoComplete="new-password"
            minLength={10}
            maxLength={24}
            required
          />
        </FormField>
        <FormField label="새 비밀번호 확인" htmlFor="initial-new-password-confirm">
          <input
            id="initial-new-password-confirm"
            name="newPasswordConfirmation"
            type="password"
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
            autoComplete="new-password"
            minLength={10}
            maxLength={24}
            required
          />
        </FormField>

        <button className="primary-button auth-submit" type="submit" disabled={submitting}>
          {submitting ? '변경 중…' : '비밀번호 변경 후 MFA 등록'}
        </button>
      </form>
    </section>
  )
}
