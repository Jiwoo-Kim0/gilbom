import { Check, Copy, ExternalLink, KeyRound, Smartphone } from 'lucide-react'
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import { Navigate, useNavigate } from 'react-router'
import { confirmTotpEnrollment, enrollTotp } from './authApi'
import { useAuthentication } from './authenticationContext'
import { AuthCardHeader, ErrorNotice, FormField } from './AuthFormParts'
import type { TotpEnrollment } from './authTypes'
import { validateTotp } from './authValidation'
import { SEOUL_TIME_ZONE } from '../../shared/time/seoulTime.ts'

export function MfaEnrollmentPage() {
  const navigate = useNavigate()
  const { status, completeAuthentication } = useAuthentication()
  const requestedRef = useRef(false)
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null)
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([])
  const [code, setCode] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [copied, setCopied] = useState<'secret' | 'codes' | null>(null)

  const requestEnrollment = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setEnrollment(await enrollTotp())
    } catch (requestError) {
      setError(requestError)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (requestedRef.current) {
      return
    }
    requestedRef.current = true
    void requestEnrollment()
  }, [requestEnrollment])

  async function copyText(value: string, target: 'secret' | 'codes') {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(target)
      window.setTimeout(() => setCopied(null), 1800)
    } catch {
      setError(new Error('클립보드에 복사하지 못했습니다. 직접 선택해 복사해주세요.'))
    }
  }

  async function handleConfirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    const codeError = validateTotp(code)
    if (codeError !== null) {
      setError(new Error(codeError))
      return
    }

    setSubmitting(true)
    try {
      const response = await confirmTotpEnrollment(code)
      setCode('')
      setRecoveryCodes(response.recoveryCodes)
      completeAuthentication(response)
    } catch (requestError) {
      setCode('')
      setError(requestError)
    } finally {
      setSubmitting(false)
    }
  }

  if (status === 'AUTHENTICATED' && recoveryCodes.length === 0) {
    return <Navigate replace to="/defects" />
  }

  if (recoveryCodes.length > 0) {
    return (
      <section className="auth-card auth-card-wide" aria-labelledby="recovery-codes-title">
        <AuthCardHeader
          eyebrow="RECOVERY CODES"
          title="복구 코드를 안전하게 보관하세요"
          description="각 코드는 한 번만 사용할 수 있으며 이 화면을 벗어나면 다시 표시되지 않습니다."
        />

        <div className="recovery-code-grid" aria-label="일회용 복구 코드 10개">
          {recoveryCodes.map((recoveryCode) => (
            <code key={recoveryCode}>{recoveryCode}</code>
          ))}
        </div>

        <div className="auth-actions">
          <button
            className="secondary-button"
            type="button"
            onClick={() => void copyText(recoveryCodes.join('\n'), 'codes')}
          >
            {copied === 'codes' ? <Check size={16} /> : <Copy size={16} />}
            {copied === 'codes' ? '복사됨' : '전체 복사'}
          </button>
          <button
            className="primary-button"
            type="button"
            onClick={() => navigate('/defects', { replace: true })}
          >
            안전하게 보관했습니다
          </button>
        </div>
      </section>
    )
  }

  return (
    <section className="auth-card auth-card-wide" aria-labelledby="mfa-enrollment-title">
      <AuthCardHeader
        eyebrow="MFA ENROLLMENT"
        title="인증 앱 등록"
        description="인증 앱에 계정을 추가한 다음 표시되는 6자리 코드를 확인합니다."
      />

      <ErrorNotice error={error} />

      {loading ? (
        <div className="auth-inline-loading" role="status">
          <span className="loading-spinner" aria-hidden="true" />
          등록 정보를 안전하게 생성하고 있습니다.
        </div>
      ) : enrollment ? (
        <div className="mfa-enrollment-grid">
          <div className="mfa-step">
            <span className="mfa-step-icon" aria-hidden="true">
              <Smartphone size={21} />
            </span>
            <div>
              <strong>1. 인증 앱에 등록</strong>
              <p>앱에서 링크를 열거나 아래 키를 수동으로 입력해주세요.</p>
              <a
                className="secondary-button mfa-app-link"
                href={enrollment.otpauthUri}
              >
                인증 앱에서 열기 <ExternalLink size={15} />
              </a>
            </div>
          </div>

          <div className="secret-box">
            <span>
              <KeyRound size={17} aria-hidden="true" />
              수동 입력키
            </span>
            <code>{enrollment.manualEntryKey}</code>
            <button
              type="button"
              onClick={() => void copyText(enrollment.manualEntryKey, 'secret')}
              aria-label="수동 입력키 복사"
            >
              {copied === 'secret' ? <Check size={17} /> : <Copy size={17} />}
            </button>
          </div>

          <form className="auth-form mfa-confirm-form" onSubmit={handleConfirm} noValidate>
            <FormField
              label="2. 인증 코드 확인"
              htmlFor="mfa-enrollment-code"
              hint={`등록 정보 유효 시각: ${new Date(enrollment.enrollmentExpiresAt).toLocaleTimeString('ko-KR', { timeZone: SEOUL_TIME_ZONE })}`}
            >
              <input
                className="totp-input"
                id="mfa-enrollment-code"
                name="code"
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/gu, ''))}
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                minLength={6}
                maxLength={6}
                required
              />
            </FormField>
            <button className="primary-button auth-submit" type="submit" disabled={submitting}>
              {submitting ? '확인 중…' : 'MFA 등록 완료'}
            </button>
          </form>
        </div>
      ) : (
        <button className="secondary-button" type="button" onClick={() => void requestEnrollment()}>
          등록 정보 다시 받기
        </button>
      )}
    </section>
  )
}
