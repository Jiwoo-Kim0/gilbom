import { KeyRound, ShieldCheck } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { verifyRecoveryCode, verifyTotp } from './authApi'
import { useAuthentication } from './authenticationContext'
import { AuthCardHeader, ErrorNotice, FormField } from './AuthFormParts'
import { validateTotp } from './authValidation'
import { useNavigate } from 'react-router'

type VerificationMode = 'totp' | 'recovery'

export function MfaVerificationPage() {
  const navigate = useNavigate()
  const { completeAuthentication, challengeExpiresAt } = useAuthentication()
  const [mode, setMode] = useState<VerificationMode>('totp')
  const [code, setCode] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)

    if (mode === 'totp') {
      const codeError = validateTotp(code)
      if (codeError !== null) {
        setError(new Error(codeError))
        return
      }
    } else if (code.length !== 22) {
      setError(new Error('발급받은 복구 코드 22자를 입력해주세요.'))
      return
    }

    setSubmitting(true)
    try {
      const response = mode === 'totp'
        ? await verifyTotp(code)
        : await verifyRecoveryCode(code)
      setCode('')
      completeAuthentication(response)
      navigate('/defects', { replace: true })
    } catch (requestError) {
      setCode('')
      setError(requestError)
    } finally {
      setSubmitting(false)
    }
  }

  function changeMode(nextMode: VerificationMode) {
    setMode(nextMode)
    setCode('')
    setError(null)
  }

  return (
    <section className="auth-card" aria-labelledby="mfa-title">
      <AuthCardHeader
        eyebrow="SECOND FACTOR"
        title="MFA 인증"
        description="비밀번호 확인이 완료되었습니다. 두 번째 인증 수단을 확인해주세요."
      />

      <div className="auth-mode-tabs" role="tablist" aria-label="MFA 인증 방법">
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'totp'}
          className={mode === 'totp' ? 'is-active' : ''}
          onClick={() => changeMode('totp')}
        >
          <ShieldCheck size={16} /> 인증 앱
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'recovery'}
          className={mode === 'recovery' ? 'is-active' : ''}
          onClick={() => changeMode('recovery')}
        >
          <KeyRound size={16} /> 복구 코드
        </button>
      </div>

      {challengeExpiresAt ? (
        <p className="challenge-expiry">
          인증 요청은 {new Date(challengeExpiresAt).toLocaleTimeString('ko-KR')}까지 유효합니다.
        </p>
      ) : null}
      <ErrorNotice error={error} />

      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        {mode === 'totp' ? (
          <FormField
            label="인증 코드"
            htmlFor="mfa-code"
            hint="인증 앱에 표시된 숫자 6자리"
          >
            <input
              className="totp-input"
              id="mfa-code"
              name="code"
              value={code}
              onChange={(event) => setCode(event.target.value.replace(/\D/gu, ''))}
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]{6}"
              minLength={6}
              maxLength={6}
              autoFocus
              required
            />
          </FormField>
        ) : (
          <FormField
            label="일회용 복구 코드"
            htmlFor="mfa-recovery-code"
            hint="예: 01-ABCD-EFGH-IJKL-MNOP"
          >
            <input
              className="recovery-input"
              id="mfa-recovery-code"
              name="recoveryCode"
              value={code}
              onChange={(event) => setCode(event.target.value.toUpperCase())}
              autoComplete="one-time-code"
              minLength={22}
              maxLength={22}
              autoFocus
              required
            />
          </FormField>
        )}

        <button className="primary-button auth-submit" type="submit" disabled={submitting}>
          {submitting ? '인증 중…' : '로그인 완료'}
        </button>
      </form>
    </section>
  )
}
