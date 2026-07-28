import {
  KeyRound,
  RefreshCw,
  ShieldAlert,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import {
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import {
  getAdminUsers,
  resetAdminUserMfa,
} from './adminApi'
import type {
  AdminUser,
  PageResponse,
} from './adminTypes'

export function AdminMfaResetPage() {
  const requestedPageRef = useRef<number | null>(null)
  const { user, refreshAuthentication } = useAuthentication()
  const [users, setUsers] = useState<PageResponse<AdminUser> | null>(null)
  const [page, setPage] = useState(0)
  const [targetUserId, setTargetUserId] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [totpCode, setTotpCode] = useState('')
  const [reason, setReason] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const targetUser = users?.content.find(
    (candidate) => candidate.userId === targetUserId,
  ) ?? null

  const load = useCallback(async (targetPage: number) => {
    try {
      const response = await getAdminUsers({
        page: targetPage,
        size: 50,
        accountStatus: 'ACTIVE',
      })
      setUsers(response)
      setTargetUserId((current) => (
        response.content.some((candidate) => (
          candidate.userId === current
          && candidate.userId !== user?.userId
          && candidate.mfaEnrolled
        ))
          ? current
          : ''
      ))
    } catch (loadError) {
      setError(loadError)
    } finally {
      setLoading(false)
    }
  }, [user?.userId])

  useEffect(() => {
    if (requestedPageRef.current === page) {
      return
    }
    requestedPageRef.current = page
    void load(page)
  }, [load, page])

  function changePage(targetPage: number) {
    setLoading(true)
    setError(null)
    setPage(targetPage)
  }

  function refresh() {
    setLoading(true)
    setError(null)
    void load(page)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (targetUser === null) {
      setError(new Error('MFA를 초기화할 다른 사용자를 선택해주세요.'))
      return
    }
    if (targetUser.userId === user?.userId) {
      setError(new Error('자기 자신의 MFA는 관리자 초기화할 수 없습니다.'))
      return
    }
    if (
      currentPassword.length < 10
      || currentPassword.length > 24
      || !/^[0-9]{6}$/.test(totpCode)
      || reason.trim().length === 0
    ) {
      setError(new Error('작업자 비밀번호, 6자리 TOTP, 초기화 사유를 확인해주세요.'))
      return
    }
    if (!window.confirm(
      `${targetUser.displayName}님의 MFA factor와 모든 세션을 폐기할까요?`,
    )) {
      return
    }

    setSubmitting(true)
    setError(null)
    setNotice(null)
    try {
      await resetAdminUserMfa(targetUser.userId, {
        currentPassword,
        totpCode,
        reason: reason.trim(),
      })
      setCurrentPassword('')
      setTotpCode('')
      setReason('')
      setTargetUserId('')
      setNotice(
        `${targetUser.displayName}님의 MFA와 모든 세션을 폐기했습니다.`,
      )
      await load(page)
    } catch (submitError) {
      setCurrentPassword('')
      setTotpCode('')
      setError(submitError)
      if (submitError instanceof ApiError && submitError.status === 401) {
        await refreshAuthentication()
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="admin-panel" aria-labelledby="mfa-reset-panel-title">
      <div className="table-heading">
        <div>
          <h3 id="mfa-reset-panel-title">MFA 관리자 초기화</h3>
          <p>긴급 복구 전용 기능입니다. 작업자 본인의 비밀번호와 TOTP를 다시 검증합니다.</p>
        </div>
        <button
          className="icon-button"
          type="button"
          onClick={refresh}
          disabled={loading}
          aria-label="사용자 목록 새로고침"
        >
          <RefreshCw size={17} />
        </button>
      </div>

      {error ? (
        <div className="page-alert" role="alert">
          {getApiErrorMessage(error)}
        </div>
      ) : null}
      {notice ? (
        <div className="page-notice" role="status">
          {notice}
        </div>
      ) : null}

      <div className="admin-mfa-layout">
        <article className="content-card admin-safety-card">
          <span className="card-icon warning" aria-hidden="true">
            <ShieldAlert size={20} />
          </span>
          <h3>초기화 전 확인</h3>
          <ul>
            <li>자기 자신의 MFA는 초기화할 수 없습니다.</li>
            <li>대상의 활성 TOTP와 복구 코드를 모두 폐기합니다.</li>
            <li>대상의 인증·대기 세션을 전부 종료합니다.</li>
            <li>재인증과 감사 기록 실패 시 변경도 롤백됩니다.</li>
          </ul>
        </article>

        <form className="content-card admin-detail-card" onSubmit={handleSubmit}>
          <div className="card-heading-row">
            <div>
              <span className="card-icon" aria-hidden="true">
                <KeyRound size={18} />
              </span>
              <h3>대상 및 작업자 재인증</h3>
            </div>
          </div>

          <label className="admin-form-field" htmlFor="mfa-reset-target">
            초기화 대상
            <select
              id="mfa-reset-target"
              value={targetUserId}
              onChange={(event) => setTargetUserId(event.target.value)}
              disabled={loading || submitting}
              required
            >
              <option value="">다른 활성 사용자를 선택하세요</option>
              {users?.content.map((candidate) => {
                const self = candidate.userId === user?.userId
                const unavailable = self || !candidate.mfaEnrolled
                return (
                  <option
                    key={candidate.userId}
                    value={candidate.userId}
                    disabled={unavailable}
                  >
                    {candidate.displayName} ({candidate.email})
                    {self ? ' — 본인' : ''}
                    {!candidate.mfaEnrolled ? ' — MFA 미등록' : ''}
                  </option>
                )
              })}
            </select>
          </label>

          {targetUser ? (
            <div className="admin-target-summary">
              <strong>{targetUser.displayName}</strong>
              <span>{targetUser.roles.map((role) => role.roleName).join(', ') || '역할 없음'}</span>
              <small>{targetUser.email}</small>
            </div>
          ) : null}

          <label className="admin-form-field" htmlFor="mfa-reset-password">
            작업자 현재 비밀번호
            <input
              id="mfa-reset-password"
              type="password"
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
              minLength={10}
              maxLength={24}
              autoComplete="current-password"
              disabled={submitting}
              required
            />
          </label>

          <label className="admin-form-field" htmlFor="mfa-reset-totp">
            작업자 TOTP
            <input
              id="mfa-reset-totp"
              value={totpCode}
              onChange={(event) => setTotpCode(
                event.target.value.replace(/\D/g, '').slice(0, 6),
              )}
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]{6}"
              maxLength={6}
              disabled={submitting}
              required
            />
          </label>

          <label className="admin-form-field" htmlFor="mfa-reset-reason">
            초기화 사유
            <textarea
              id="mfa-reset-reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              rows={3}
              maxLength={500}
              disabled={submitting}
              required
            />
            <small>{reason.length}/500</small>
          </label>

          <button
            className="danger-button admin-danger-submit"
            type="submit"
            disabled={submitting || targetUser === null}
          >
            <ShieldAlert size={17} />
            {submitting ? '검증 및 초기화 중…' : '재인증 후 MFA 초기화'}
          </button>
        </form>
      </div>

      <div className="pagination" aria-label="활성 사용자 페이지">
        <button
          className="secondary-button"
          type="button"
          onClick={() => changePage(Math.max(0, page - 1))}
          disabled={page === 0 || loading}
        >
          이전
        </button>
        <span>{page + 1} / {Math.max(users?.totalPages ?? 1, 1)}</span>
        <button
          className="secondary-button"
          type="button"
          onClick={() => changePage(page + 1)}
          disabled={
            loading
            || users === null
            || page + 1 >= users.totalPages
          }
        >
          다음
        </button>
      </div>
    </section>
  )
}
