import {
  RefreshCw,
  Save,
  ShieldCheck,
  UsersRound,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import {
  getAdminRoles,
  getAdminUsers,
  replaceAdminUserRoles,
} from './adminApi'
import type {
  AdminRole,
  AdminUser,
  PageResponse,
} from './adminTypes'

const USER_PAGE_SIZE = 50

export function AdminUserRolesPage() {
  const requestedPageRef = useRef<number | null>(null)
  const selectedUserIdRef = useRef<string | null>(null)
  const { user } = useAuthentication()
  const [users, setUsers] = useState<PageResponse<AdminUser> | null>(null)
  const [roles, setRoles] = useState<AdminRole[]>([])
  const [page, setPage] = useState(0)
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null)
  const [selectedRoleIds, setSelectedRoleIds] = useState<string[]>([])
  const [primaryRoleId, setPrimaryRoleId] = useState('')
  const [reason, setReason] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const activeRoles = useMemo(
    () => roles.filter((role) => role.active),
    [roles],
  )
  const manageableUsers = useMemo(
    () => users?.content.filter((candidate) => candidate.userId !== user?.userId)
      ?? [],
    [user?.userId, users],
  )
  const selectedUser = manageableUsers.find(
    (candidate) => candidate.userId === selectedUserId,
  ) ?? null

  function applySelection(nextUser: AdminUser | null) {
    selectedUserIdRef.current = nextUser?.userId ?? null
    setSelectedUserId(nextUser?.userId ?? null)
    setSelectedRoleIds(nextUser?.roles.map((role) => role.roleId) ?? [])
    setPrimaryRoleId(
      nextUser?.roles.find((role) => role.primary)?.roleId ?? '',
    )
    setReason('')
  }

  const load = useCallback(async (targetPage: number) => {
    try {
      const [userPage, roleList] = await Promise.all([
        getAdminUsers({
          page: targetPage,
          size: USER_PAGE_SIZE,
          accountStatus: 'ACTIVE',
        }),
        getAdminRoles(),
      ])
      const otherUsers = userPage.content.filter(
        (candidate) => candidate.userId !== user?.userId,
      )
      const nextUser = otherUsers.find(
        (candidate) => candidate.userId === selectedUserIdRef.current,
      ) ?? otherUsers.at(0) ?? null
      setUsers(userPage)
      setRoles(roleList)
      applySelection(nextUser)
      setError(null)
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
    setNotice(null)
    setPage(targetPage)
  }

  function refresh() {
    setLoading(true)
    setError(null)
    setNotice(null)
    void load(page)
  }

  function selectUser(userId: string) {
    const nextUser = manageableUsers.find(
      (candidate) => candidate.userId === userId,
    ) ?? null
    applySelection(nextUser)
    setError(null)
    setNotice(null)
  }

  function toggleRole(roleId: string) {
    setSelectedRoleIds((current) => {
      if (current.includes(roleId)) {
        if (primaryRoleId === roleId) {
          setPrimaryRoleId('')
        }
        return current.filter((candidate) => candidate !== roleId)
      }
      return [...current, roleId]
    })
  }

  async function handleSave() {
    if (selectedUser === null) {
      return
    }
    if (reason.trim().length === 0) {
      setError(new Error('역할 변경 사유를 입력해주세요.'))
      return
    }

    const roleNames = activeRoles
      .filter((role) => selectedRoleIds.includes(role.roleId))
      .map((role) => role.roleName)
    const roleDescription = roleNames.length > 0
      ? roleNames.join(', ')
      : '역할 없음'
    if (!window.confirm(
      `${selectedUser.displayName}님의 역할을 “${roleDescription}”(으)로 변경할까요?\n변경 후 대상 사용자의 기존 세션은 종료됩니다.`,
    )) {
      return
    }

    setSubmitting(true)
    setError(null)
    setNotice(null)
    try {
      const updatedUser = await replaceAdminUserRoles(selectedUser.userId, {
        roleIds: selectedRoleIds,
        primaryRoleId: primaryRoleId || null,
        reason: reason.trim(),
      })
      setUsers((current) => (
        current === null
          ? current
          : {
              ...current,
              content: current.content.map((candidate) => (
                candidate.userId === updatedUser.userId
                  ? updatedUser
                  : candidate
              )),
            }
      ))
      setReason('')
      setNotice(
        `${updatedUser.displayName}님의 역할을 변경하고 기존 세션을 폐기했습니다.`,
      )
    } catch (submitError) {
      setError(submitError)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="admin-panel" aria-labelledby="user-roles-panel-title">
      <div className="table-heading">
        <div>
          <h3 id="user-roles-panel-title">사용자 역할 관리</h3>
          <p>
            승인된 다른 사용자의 활성 역할을 교체합니다. 변경 내용과 사유는 감사
            로그에 기록되고 대상 사용자의 기존 세션은 종료됩니다.
          </p>
        </div>
        <button
          className="icon-button"
          type="button"
          onClick={refresh}
          disabled={loading || submitting}
          aria-label="사용자와 역할 목록 새로고침"
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

      {loading && users === null ? (
        <div className="admin-loading" role="status">
          <span className="loading-spinner" aria-hidden="true" />
          활성 사용자와 역할을 불러오는 중입니다.
        </div>
      ) : manageableUsers.length === 0 ? (
        <div className="content-card empty-state">
          <span className="empty-state-icon" aria-hidden="true">
            <UsersRound size={22} />
          </span>
          <h3>역할을 변경할 다른 활성 사용자가 없습니다.</h3>
          <p>가입 승인 후 활성화된 사용자가 이 화면에 표시됩니다.</p>
        </div>
      ) : (
        <>
          <div className="admin-master-detail">
            <div className="content-card admin-list-card">
              <div className="admin-list-meta">
                <strong>{manageableUsers.length}명</strong>
                <span>현재 페이지 · 본인 제외</span>
              </div>
              <div className="admin-user-list">
                {manageableUsers.map((candidate) => (
                  <button
                    className={`admin-user-row${
                      selectedUserId === candidate.userId ? ' is-selected' : ''
                    }`}
                    key={candidate.userId}
                    type="button"
                    onClick={() => selectUser(candidate.userId)}
                    disabled={submitting}
                  >
                    <span>
                      <strong>{candidate.displayName}</strong>
                      <small>{candidate.email}</small>
                    </span>
                    <span className="admin-user-role-codes">
                      {candidate.roles.map((role) => role.roleCode).join(', ')
                        || '역할 없음'}
                    </span>
                  </button>
                ))}
              </div>
            </div>

            {selectedUser ? (
              <div className="content-card admin-detail-card">
                <div>
                  <span className="status-badge">ACTIVE USER</span>
                  <h3>{selectedUser.displayName}</h3>
                  <p>{selectedUser.email} · {selectedUser.userCode}</p>
                </div>

                <div className="admin-target-summary">
                  <strong>현재 역할</strong>
                  <span>
                    {selectedUser.roles.map((role) => (
                      `${role.roleName}${role.primary ? ' (주 역할)' : ''}`
                    )).join(', ') || '역할 없음'}
                  </span>
                  <small>
                    마지막 활성 ADMIN 보호 규칙은 서버와 DB에서 최종 검증합니다.
                  </small>
                </div>

                <fieldset className="admin-fieldset">
                  <legend>변경할 역할</legend>
                  <div className="admin-checkbox-grid">
                    {activeRoles.map((role) => (
                      <label key={role.roleId}>
                        <input
                          type="checkbox"
                          checked={selectedRoleIds.includes(role.roleId)}
                          onChange={() => toggleRole(role.roleId)}
                          disabled={submitting}
                        />
                        <span>
                          <strong>{role.roleName}</strong>
                          <small>{role.roleCode}</small>
                        </span>
                      </label>
                    ))}
                  </div>
                </fieldset>

                <label className="admin-form-field" htmlFor="user-primary-role">
                  주 역할
                  <select
                    id="user-primary-role"
                    value={primaryRoleId}
                    onChange={(event) => setPrimaryRoleId(event.target.value)}
                    disabled={submitting}
                  >
                    <option value="">자동 선택</option>
                    {activeRoles
                      .filter((role) => selectedRoleIds.includes(role.roleId))
                      .map((role) => (
                        <option key={role.roleId} value={role.roleId}>
                          {role.roleName}
                        </option>
                      ))}
                  </select>
                </label>

                <label className="admin-form-field" htmlFor="user-role-reason">
                  변경 사유
                  <textarea
                    id="user-role-reason"
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                    rows={3}
                    maxLength={500}
                    disabled={submitting}
                    required
                  />
                  <small>{reason.length}/500</small>
                </label>

                <div className="admin-action-row admin-action-end">
                  <button
                    className="primary-button"
                    type="button"
                    onClick={() => void handleSave()}
                    disabled={submitting}
                  >
                    {submitting ? (
                      <span className="loading-spinner" aria-hidden="true" />
                    ) : (
                      <ShieldCheck size={17} />
                    )}
                    {submitting ? '역할 변경 중…' : '역할 변경 저장'}
                    {!submitting ? <Save size={15} aria-hidden="true" /> : null}
                  </button>
                </div>
              </div>
            ) : null}
          </div>

          <div className="pagination" aria-label="활성 사용자 페이지">
            <button
              className="secondary-button"
              type="button"
              onClick={() => changePage(Math.max(0, page - 1))}
              disabled={page === 0 || loading || submitting}
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
                || submitting
                || users === null
                || page + 1 >= users.totalPages
              }
            >
              다음
            </button>
          </div>
        </>
      )}
    </section>
  )
}
