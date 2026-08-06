import {
  CheckCircle2,
  RefreshCw,
  UserCheck,
  XCircle,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import {
  approveRegistration,
  getAdminRoles,
  getRegistrationRequests,
  rejectRegistration,
} from './adminApi'
import type {
  AdminRole,
  AdminUser,
  PageResponse,
} from './adminTypes'
import { SEOUL_TIME_ZONE } from '../../shared/time/seoulTime.ts'

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: SEOUL_TIME_ZONE,
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function AdminApprovalsPage() {
  const requestedPageRef = useRef<number | null>(null)
  const [requests, setRequests] = useState<PageResponse<AdminUser> | null>(null)
  const [roles, setRoles] = useState<AdminRole[]>([])
  const [page, setPage] = useState(0)
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null)
  const [selectedRoleIds, setSelectedRoleIds] = useState<string[]>([])
  const [primaryRoleId, setPrimaryRoleId] = useState('')
  const [reason, setReason] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState<'approve' | 'reject' | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const activeRoles = useMemo(
    () => roles.filter((role) => role.active),
    [roles],
  )
  const selectedUser = requests?.content.find(
    (user) => user.userId === selectedUserId,
  ) ?? null

  const load = useCallback(async (targetPage: number) => {
    try {
      const [requestPage, roleList] = await Promise.all([
        getRegistrationRequests(targetPage),
        getAdminRoles(),
      ])
      setRequests(requestPage)
      setRoles(roleList)
      setSelectedUserId((current) => (
        requestPage.content.some((user) => user.userId === current)
          ? current
          : (requestPage.content.at(0)?.userId ?? null)
      ))
    } catch (loadError) {
      setError(loadError)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (requestedPageRef.current === page) {
      return
    }
    requestedPageRef.current = page
    void load(page)
  }, [load, page])

  function selectUser(userId: string) {
    setSelectedUserId(userId)
    setSelectedRoleIds([])
    setPrimaryRoleId('')
    setReason('')
    setError(null)
    setNotice(null)
  }

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

  async function handleApprove() {
    if (selectedUser === null) {
      return
    }
    if (selectedRoleIds.length === 0) {
      setError(new Error('승인할 계정에는 활성 역할을 하나 이상 부여해야 합니다.'))
      return
    }
    if (reason.trim().length === 0) {
      setError(new Error('승인 사유를 입력해주세요.'))
      return
    }

    setSubmitting('approve')
    setError(null)
    setNotice(null)
    try {
      await approveRegistration(selectedUser.userId, {
        roleIds: selectedRoleIds,
        primaryRoleId: primaryRoleId || null,
        reason: reason.trim(),
      })
      setNotice(`${selectedUser.displayName}님의 가입 요청을 승인했습니다.`)
      setSelectedRoleIds([])
      setPrimaryRoleId('')
      setReason('')
      await load(page)
    } catch (submitError) {
      setError(submitError)
    } finally {
      setSubmitting(null)
    }
  }

  async function handleReject() {
    if (selectedUser === null) {
      return
    }
    if (reason.trim().length === 0) {
      setError(new Error('거절 사유를 입력해주세요.'))
      return
    }
    if (!window.confirm(`${selectedUser.displayName}님의 가입 요청을 거절할까요?`)) {
      return
    }

    setSubmitting('reject')
    setError(null)
    setNotice(null)
    try {
      await rejectRegistration(selectedUser.userId, reason.trim())
      setNotice(`${selectedUser.displayName}님의 가입 요청을 거절했습니다.`)
      setSelectedRoleIds([])
      setPrimaryRoleId('')
      setReason('')
      await load(page)
    } catch (submitError) {
      setError(submitError)
    } finally {
      setSubmitting(null)
    }
  }

  return (
    <section className="admin-panel" aria-labelledby="approval-panel-title">
      <div className="table-heading">
        <div>
          <h3 id="approval-panel-title">가입 요청</h3>
          <p>승인 시 역할 부여, 계정 활성화, 감사 기록이 한 트랜잭션으로 처리됩니다.</p>
        </div>
        <button
          className="icon-button"
          type="button"
          onClick={refresh}
          disabled={loading}
          aria-label="가입 요청 새로고침"
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

      {loading && requests === null ? (
        <div className="admin-loading" role="status">
          <span className="loading-spinner" aria-hidden="true" />
          가입 요청을 불러오는 중입니다.
        </div>
      ) : requests?.content.length === 0 ? (
        <div className="content-card empty-state">
          <span className="empty-state-icon" aria-hidden="true">
            <UserCheck size={22} />
          </span>
          <h3>대기 중인 가입 요청이 없습니다.</h3>
          <p>새 요청이 접수되면 이 화면에서 역할과 승인 사유를 지정할 수 있습니다.</p>
        </div>
      ) : (
        <>
          <div className="admin-master-detail">
            <div className="content-card admin-list-card">
              <div className="admin-list-meta">
                <strong>{requests?.totalElements ?? 0}건</strong>
                <span>승인 대기</span>
              </div>
              <div className="admin-user-list">
                {requests?.content.map((user) => (
                  <button
                    className={`admin-user-row${
                      selectedUserId === user.userId ? ' is-selected' : ''
                    }`}
                    key={user.userId}
                    type="button"
                    onClick={() => selectUser(user.userId)}
                  >
                    <span>
                      <strong>{user.displayName}</strong>
                      <small>{user.email}</small>
                    </span>
                    <time dateTime={user.createdAt}>
                      {formatDateTime(user.createdAt)}
                    </time>
                  </button>
                ))}
              </div>
            </div>

            {selectedUser ? (
              <div className="content-card admin-detail-card">
                <div>
                  <span className="status-badge">PENDING APPROVAL</span>
                  <h3>{selectedUser.displayName}</h3>
                  <p>{selectedUser.email} · {selectedUser.userCode}</p>
                </div>

                <fieldset className="admin-fieldset">
                  <legend>부여할 역할</legend>
                  <div className="admin-checkbox-grid">
                    {activeRoles.map((role) => (
                      <label key={role.roleId}>
                        <input
                          type="checkbox"
                          checked={selectedRoleIds.includes(role.roleId)}
                          onChange={() => toggleRole(role.roleId)}
                          disabled={submitting !== null}
                        />
                        <span>
                          <strong>{role.roleName}</strong>
                          <small>{role.roleCode}</small>
                        </span>
                      </label>
                    ))}
                  </div>
                </fieldset>

                <label className="admin-form-field" htmlFor="approval-primary-role">
                  주 역할
                  <select
                    id="approval-primary-role"
                    value={primaryRoleId}
                    onChange={(event) => setPrimaryRoleId(event.target.value)}
                    disabled={submitting !== null}
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

                <label className="admin-form-field" htmlFor="approval-reason">
                  처리 사유
                  <textarea
                    id="approval-reason"
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                    maxLength={500}
                    rows={3}
                    disabled={submitting !== null}
                    required
                  />
                  <small>{reason.length}/500</small>
                </label>

                <div className="admin-action-row">
                  <button
                    className="danger-button"
                    type="button"
                    onClick={() => void handleReject()}
                    disabled={submitting !== null}
                  >
                    <XCircle size={17} />
                    {submitting === 'reject' ? '거절 중…' : '거절'}
                  </button>
                  <button
                    className="primary-button"
                    type="button"
                    onClick={() => void handleApprove()}
                    disabled={submitting !== null}
                  >
                    <CheckCircle2 size={17} />
                    {submitting === 'approve' ? '승인 중…' : '역할 부여 후 승인'}
                  </button>
                </div>
              </div>
            ) : null}
          </div>

          <div className="pagination" aria-label="가입 요청 페이지">
            <button
              className="secondary-button"
              type="button"
              onClick={() => changePage(Math.max(0, page - 1))}
              disabled={page === 0 || loading}
            >
              이전
            </button>
            <span>{page + 1} / {Math.max(requests?.totalPages ?? 1, 1)}</span>
            <button
              className="secondary-button"
              type="button"
              onClick={() => changePage(page + 1)}
              disabled={
                loading
                || requests === null
                || page + 1 >= requests.totalPages
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
