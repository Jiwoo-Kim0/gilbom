import {
  KeyRound,
  LogOut,
  ShieldCheck,
  UserRound,
} from 'lucide-react'
import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import { useNavigate } from 'react-router'
import { ApiError, getApiErrorMessage } from '../../shared/api/apiClient'
import {
  changeMyPassword,
  getMyProfile,
} from '../auth/authApi'
import { useAuthentication } from '../auth/authenticationContext'
import type { MyProfile } from '../auth/authTypes'
import { validatePassword } from '../auth/authValidation'

const mfaLabels: Record<MyProfile['mfaStatus'], string> = {
  ENROLLED: '등록 완료',
  ENROLLMENT_PENDING: '등록 진행 중',
  NOT_ENROLLED: '미등록',
}

function formatDateTime(value: string | null): string {
  if (value === null) {
    return '기록 없음'
  }
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function MyPage() {
  const navigate = useNavigate()
  const requestedRef = useRef(false)
  const {
    signOut,
    clearAuthentication,
    refreshAuthentication,
  } = useAuthentication()
  const [profile, setProfile] = useState<MyProfile | null>(null)
  const [profileError, setProfileError] = useState<unknown>(null)
  const [loadingProfile, setLoadingProfile] = useState(true)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [newPasswordConfirmation, setNewPasswordConfirmation] = useState('')
  const [passwordError, setPasswordError] = useState<unknown>(null)
  const [changingPassword, setChangingPassword] = useState(false)
  const [logoutError, setLogoutError] = useState<unknown>(null)
  const [loggingOut, setLoggingOut] = useState(false)

  async function loadProfile() {
    setLoadingProfile(true)
    setProfileError(null)
    try {
      setProfile(await getMyProfile())
    } catch (error) {
      setProfileError(error)
    } finally {
      setLoadingProfile(false)
    }
  }

  useEffect(() => {
    if (requestedRef.current) {
      return
    }
    requestedRef.current = true
    void loadProfile()
  }, [])

  async function handlePasswordChange(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setPasswordError(null)

    const currentPasswordError = validatePassword(currentPassword)
    const nextPasswordError = validatePassword(newPassword)
    if (currentPasswordError !== null || nextPasswordError !== null) {
      setPasswordError(new Error(currentPasswordError ?? nextPasswordError ?? '입력값을 확인해주세요.'))
      return
    }
    if (currentPassword === newPassword) {
      setPasswordError(new Error('새 비밀번호는 현재 비밀번호와 달라야 합니다.'))
      return
    }
    if (newPassword !== newPasswordConfirmation) {
      setPasswordError(new Error('새 비밀번호 확인이 일치하지 않습니다.'))
      return
    }

    setChangingPassword(true)
    try {
      await changeMyPassword({ currentPassword, newPassword })
      setCurrentPassword('')
      setNewPassword('')
      setNewPasswordConfirmation('')
      clearAuthentication()
      navigate('/login', {
        replace: true,
        state: {
          notice: '비밀번호가 변경되어 모든 세션이 종료되었습니다. 다시 로그인해주세요.',
        },
      })
    } catch (error) {
      setCurrentPassword('')
      setPasswordError(error)
      if (error instanceof ApiError && error.status === 401) {
        await refreshAuthentication()
      }
    } finally {
      setChangingPassword(false)
    }
  }

  async function handleLogout() {
    setLogoutError(null)
    setLoggingOut(true)
    try {
      await signOut()
      navigate('/login', {
        replace: true,
        state: { notice: '안전하게 로그아웃되었습니다.' },
      })
    } catch (error) {
      setLogoutError(error)
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <section className="page-stack" aria-label="마이페이지">
      {logoutError ? (
        <div className="page-alert" role="alert">
          {getApiErrorMessage(logoutError)}
        </div>
      ) : null}

      <div className="settings-grid account-settings-grid">
        <article className="content-card profile-card">
          <div className="card-heading-row">
            <div>
              <span className="card-icon" aria-hidden="true">
                <UserRound size={19} />
              </span>
              <h3>프로필</h3>
            </div>
            <button
              className="secondary-button button-with-icon"
              type="button"
              onClick={() => void handleLogout()}
              disabled={loggingOut}
            >
              <LogOut size={17} aria-hidden="true" />
              {loggingOut ? '로그아웃 중…' : '로그아웃'}
            </button>
          </div>

          {loadingProfile ? (
            <div className="profile-loading" role="status">
              <span className="loading-spinner" aria-hidden="true" />
              프로필을 불러오는 중입니다.
            </div>
          ) : profileError ? (
            <div className="inline-error" role="alert">
              <p>{getApiErrorMessage(profileError)}</p>
              <button className="secondary-button" type="button" onClick={() => void loadProfile()}>
                다시 시도
              </button>
            </div>
          ) : profile ? (
            <>
              <dl className="profile-details">
                <div>
                  <dt>이름</dt>
                  <dd>{profile.displayName}</dd>
                </div>
                <div>
                  <dt>이메일</dt>
                  <dd>{profile.email}</dd>
                </div>
                <div>
                  <dt>부서</dt>
                  <dd>{profile.departmentName ?? '미지정'}</dd>
                </div>
                <div>
                  <dt>직책</dt>
                  <dd>{profile.jobTitle ?? '미지정'}</dd>
                </div>
                <div>
                  <dt>근무지</dt>
                  <dd>{profile.officeName ?? '미지정'}</dd>
                </div>
                <div>
                  <dt>역할</dt>
                  <dd>
                    {profile.roles.length > 0
                      ? profile.roles.map((role) => role.roleName).join(', ')
                      : '역할 없음'}
                  </dd>
                </div>
              </dl>

              <div className="security-summary">
                <div>
                  <ShieldCheck size={18} aria-hidden="true" />
                  <span>MFA</span>
                  <strong>{mfaLabels[profile.mfaStatus]}</strong>
                </div>
                <div>
                  <KeyRound size={18} aria-hidden="true" />
                  <span>비밀번호 변경</span>
                  <strong>{formatDateTime(profile.passwordChangedAt)}</strong>
                </div>
                <small>최근 로그인: {formatDateTime(profile.lastLoginAt)}</small>
              </div>
            </>
          ) : null}
        </article>

        <article className="content-card password-card">
          <div className="card-heading-row">
            <div>
              <span className="card-icon" aria-hidden="true">
                <KeyRound size={19} />
              </span>
              <h3>비밀번호 변경</h3>
            </div>
          </div>
          <p className="card-description">
            변경이 완료되면 현재 기기를 포함한 모든 세션이 종료됩니다.
          </p>

          {passwordError ? (
            <div className="inline-error" role="alert">
              {getApiErrorMessage(passwordError)}
            </div>
          ) : null}

          <form className="settings-form" onSubmit={handlePasswordChange} noValidate>
            <label htmlFor="my-current-password">
              현재 비밀번호
              <input
                id="my-current-password"
                name="currentPassword"
                type="password"
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
                autoComplete="current-password"
                minLength={10}
                maxLength={24}
                required
              />
            </label>
            <label htmlFor="my-new-password">
              새 비밀번호
              <input
                id="my-new-password"
                name="newPassword"
                type="password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                autoComplete="new-password"
                minLength={10}
                maxLength={24}
                required
              />
              <small>조합 규칙 없이 10자 이상 24자 이하</small>
            </label>
            <label htmlFor="my-new-password-confirmation">
              새 비밀번호 확인
              <input
                id="my-new-password-confirmation"
                name="newPasswordConfirmation"
                type="password"
                value={newPasswordConfirmation}
                onChange={(event) => setNewPasswordConfirmation(event.target.value)}
                autoComplete="new-password"
                minLength={10}
                maxLength={24}
                required
              />
            </label>
            <button className="primary-button" type="submit" disabled={changingPassword}>
              {changingPassword ? '변경 중…' : '비밀번호 변경'}
            </button>
          </form>
        </article>
      </div>
    </section>
  )
}
