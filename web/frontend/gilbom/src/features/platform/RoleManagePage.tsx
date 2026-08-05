import {
  Plus,
  RefreshCw,
  Save,
  Shield,
  Trash2,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { isEnforcedPermissionCode } from '../auth/permissions'
import {
  createAdminRole,
  deactivateAdminRole,
  getAdminPermissions,
  getAdminRoles,
  replaceAdminRolePermissions,
  updateAdminRole,
} from '../admin/adminApi'
import type {
  AdminPermission,
  AdminRole,
} from '../admin/adminTypes'

type Submission =
  | 'create'
  | 'metadata'
  | 'permissions'
  | 'deactivate'
  | null

export function RoleManagePage() {
  const requestedRef = useRef(false)
  const [roles, setRoles] = useState<AdminRole[]>([])
  const [permissions, setPermissions] = useState<AdminPermission[]>([])
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null)
  const [roleName, setRoleName] = useState('')
  const [description, setDescription] = useState('')
  const [displayOrder, setDisplayOrder] = useState(0)
  const [metadataReason, setMetadataReason] = useState('')
  const [selectedPermissionIds, setSelectedPermissionIds] = useState<string[]>([])
  const [permissionReason, setPermissionReason] = useState('')
  const [createRoleCode, setCreateRoleCode] = useState('')
  const [createRoleName, setCreateRoleName] = useState('')
  const [createDescription, setCreateDescription] = useState('')
  const [createDisplayOrder, setCreateDisplayOrder] = useState(100)
  const [createReason, setCreateReason] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState<Submission>(null)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const selectedRole = useMemo(
    () => roles.find((role) => role.roleId === selectedRoleId) ?? null,
    [roles, selectedRoleId],
  )

  const permissionsByResource = useMemo(() => {
    const grouped = new Map<string, AdminPermission[]>()
    for (const permission of permissions) {
      if (!isEnforcedPermissionCode(permission.permissionCode)) {
        continue
      }
      const group = grouped.get(permission.resourceType) ?? []
      group.push(permission)
      grouped.set(permission.resourceType, group)
    }
    return [...grouped.entries()]
  }, [permissions])

  const hydrateRoleEditor = useCallback((
    role: AdminRole,
    permissionList: AdminPermission[],
  ) => {
    setRoleName(role.roleName)
    setDescription(role.description)
    setDisplayOrder(role.displayOrder)
    setMetadataReason('')
    setPermissionReason('')
    setSelectedPermissionIds(
      permissionList
        .filter((permission) => (
          role.permissionCodes.includes(permission.permissionCode)
        ))
        .map((permission) => permission.permissionId),
    )
  }, [])

  const load = useCallback(async (preferredRoleId?: string) => {
    try {
      const [roleList, permissionList] = await Promise.all([
        getAdminRoles(),
        getAdminPermissions(),
      ])
      setRoles(roleList)
      setPermissions(permissionList)
      const selected = roleList.find((role) => (
        role.roleId === preferredRoleId
      )) ?? roleList.find((role) => role.active) ?? roleList.at(0) ?? null
      setSelectedRoleId(selected?.roleId ?? null)
      if (selected !== null) {
        hydrateRoleEditor(selected, permissionList)
      }
    } catch (loadError) {
      setError(loadError)
    } finally {
      setLoading(false)
    }
  }, [hydrateRoleEditor])

  useEffect(() => {
    if (requestedRef.current) {
      return
    }
    requestedRef.current = true
    void load()
  }, [load])

  function togglePermission(permissionId: string) {
    setSelectedPermissionIds((current) => (
      current.includes(permissionId)
        ? current.filter((candidate) => candidate !== permissionId)
        : [...current, permissionId]
    ))
  }

  function selectRole(role: AdminRole) {
    setSelectedRoleId(role.roleId)
    hydrateRoleEditor(role, permissions)
    setError(null)
    setNotice(null)
  }

  function refresh() {
    setLoading(true)
    setError(null)
    void load(selectedRoleId ?? undefined)
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (
      createRoleCode.trim().length === 0
      || createRoleName.trim().length === 0
      || createDescription.trim().length === 0
      || createReason.trim().length === 0
    ) {
      setError(new Error('새 역할의 필수 입력값과 생성 사유를 모두 입력해주세요.'))
      return
    }

    setSubmitting('create')
    setError(null)
    setNotice(null)
    try {
      const created = await createAdminRole({
        roleCode: createRoleCode.trim(),
        roleName: createRoleName.trim(),
        description: createDescription.trim(),
        displayOrder: createDisplayOrder,
        reason: createReason.trim(),
      })
      setCreateRoleCode('')
      setCreateRoleName('')
      setCreateDescription('')
      setCreateDisplayOrder(100)
      setCreateReason('')
      setNotice(`${created.roleName} 역할을 생성했습니다.`)
      await load(created.roleId)
    } catch (submitError) {
      setError(submitError)
    } finally {
      setSubmitting(null)
    }
  }

  async function handleMetadataSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (selectedRole === null) {
      return
    }
    if (
      roleName.trim().length === 0
      || description.trim().length === 0
      || metadataReason.trim().length === 0
    ) {
      setError(new Error('역할 이름, 설명, 변경 사유를 모두 입력해주세요.'))
      return
    }

    setSubmitting('metadata')
    setError(null)
    setNotice(null)
    try {
      const updated = await updateAdminRole(selectedRole.roleId, {
        roleName: roleName.trim(),
        description: description.trim(),
        displayOrder,
        reason: metadataReason.trim(),
      })
      setNotice(`${updated.roleName} 역할 정보를 저장했습니다.`)
      await load(updated.roleId)
    } catch (submitError) {
      setError(submitError)
    } finally {
      setSubmitting(null)
    }
  }

  async function handlePermissionSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (selectedRole === null) {
      return
    }
    if (permissionReason.trim().length === 0) {
      setError(new Error('권한 변경 사유를 입력해주세요.'))
      return
    }

    setSubmitting('permissions')
    setError(null)
    setNotice(null)
    try {
      await replaceAdminRolePermissions(
        selectedRole.roleId,
        selectedPermissionIds,
        permissionReason.trim(),
      )
      setNotice(`${selectedRole.roleName} 역할의 권한을 저장했습니다.`)
      await load(selectedRole.roleId)
    } catch (submitError) {
      setError(submitError)
    } finally {
      setSubmitting(null)
    }
  }

  async function handleDeactivate() {
    if (selectedRole === null || selectedRole.system) {
      return
    }
    if (metadataReason.trim().length === 0) {
      setError(new Error('역할 비활성화 사유를 역할 변경 사유에 입력해주세요.'))
      return
    }
    if (!window.confirm(`${selectedRole.roleName} 역할을 비활성화할까요?`)) {
      return
    }

    setSubmitting('deactivate')
    setError(null)
    setNotice(null)
    try {
      await deactivateAdminRole(selectedRole.roleId, metadataReason.trim())
      setNotice(`${selectedRole.roleName} 역할을 비활성화했습니다.`)
      await load()
    } catch (submitError) {
      setError(submitError)
    } finally {
      setSubmitting(null)
    }
  }

  return (
    <section className="admin-panel" aria-labelledby="role-panel-title">
      <div className="table-heading">
        <div>
          <h3 id="role-panel-title">역할과 기능 권한</h3>
          <p>역할 정보와 permission 변경은 각각 독립된 감사 대상 작업으로 저장됩니다.</p>
        </div>
        <button
          className="icon-button"
          type="button"
          onClick={refresh}
          disabled={loading}
          aria-label="역할 목록 새로고침"
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

      <details className="content-card admin-create-card">
        <summary>
          <Plus size={17} aria-hidden="true" />
          사용자 정의 역할 만들기
        </summary>
        <form className="admin-form-grid" onSubmit={handleCreate}>
          <label className="admin-form-field" htmlFor="create-role-code">
            역할 코드
            <input
              id="create-role-code"
              value={createRoleCode}
              onChange={(event) => setCreateRoleCode(event.target.value.toUpperCase())}
              pattern="^[A-Z][A-Z0-9_]{1,49}$"
              maxLength={50}
              placeholder="FIELD_MANAGER"
              required
            />
          </label>
          <label className="admin-form-field" htmlFor="create-role-name">
            역할 이름
            <input
              id="create-role-name"
              value={createRoleName}
              onChange={(event) => setCreateRoleName(event.target.value)}
              maxLength={100}
              required
            />
          </label>
          <label className="admin-form-field" htmlFor="create-role-order">
            표시 순서
            <input
              id="create-role-order"
              type="number"
              value={createDisplayOrder}
              onChange={(event) => setCreateDisplayOrder(Number(event.target.value))}
              min={0}
              max={10000}
              required
            />
          </label>
          <label className="admin-form-field admin-form-wide" htmlFor="create-role-description">
            설명
            <textarea
              id="create-role-description"
              value={createDescription}
              onChange={(event) => setCreateDescription(event.target.value)}
              rows={2}
              maxLength={500}
              required
            />
          </label>
          <label className="admin-form-field admin-form-wide" htmlFor="create-role-reason">
            생성 사유
            <textarea
              id="create-role-reason"
              value={createReason}
              onChange={(event) => setCreateReason(event.target.value)}
              rows={2}
              maxLength={500}
              required
            />
          </label>
          <button
            className="primary-button admin-form-submit"
            type="submit"
            disabled={submitting !== null}
          >
            <Plus size={17} />
            {submitting === 'create' ? '생성 중…' : '역할 생성'}
          </button>
        </form>
      </details>

      {loading && roles.length === 0 ? (
        <div className="admin-loading" role="status">
          <span className="loading-spinner" aria-hidden="true" />
          역할과 권한을 불러오는 중입니다.
        </div>
      ) : (
        <div className="admin-master-detail admin-role-layout">
          <div className="content-card admin-list-card">
            <div className="admin-list-meta">
              <strong>{roles.length}개 역할</strong>
              <span>시스템·사용자 정의</span>
            </div>
            <div className="admin-role-list">
              {roles.map((role) => (
                <button
                  className={`admin-role-row${
                    selectedRoleId === role.roleId ? ' is-selected' : ''
                  }`}
                  key={role.roleId}
                  type="button"
                  onClick={() => selectRole(role)}
                >
                  <span>
                    <strong>{role.roleName}</strong>
                    <small>{role.roleCode}</small>
                  </span>
                  <span className={`status-dot ${role.active ? 'is-active' : ''}`}>
                    {role.active ? '활성' : '비활성'}
                  </span>
                </button>
              ))}
            </div>
          </div>

          {selectedRole ? (
            <div className="admin-role-detail">
              <form className="content-card admin-detail-card" onSubmit={handleMetadataSave}>
                <div className="card-heading-row">
                  <div>
                    <span className="card-icon" aria-hidden="true">
                      <Shield size={18} />
                    </span>
                    <h3>{selectedRole.roleCode}</h3>
                  </div>
                  <span className="status-badge">
                    {selectedRole.system ? 'SYSTEM' : 'CUSTOM'}
                  </span>
                </div>

                <div className="admin-form-grid">
                  <label className="admin-form-field" htmlFor="role-name">
                    역할 이름
                    <input
                      id="role-name"
                      value={roleName}
                      onChange={(event) => setRoleName(event.target.value)}
                      maxLength={100}
                      disabled={!selectedRole.active || submitting !== null}
                      required
                    />
                  </label>
                  <label className="admin-form-field" htmlFor="role-order">
                    표시 순서
                    <input
                      id="role-order"
                      type="number"
                      value={displayOrder}
                      onChange={(event) => setDisplayOrder(Number(event.target.value))}
                      min={0}
                      max={10000}
                      disabled={!selectedRole.active || submitting !== null}
                      required
                    />
                  </label>
                  <label className="admin-form-field admin-form-wide" htmlFor="role-description">
                    설명
                    <textarea
                      id="role-description"
                      value={description}
                      onChange={(event) => setDescription(event.target.value)}
                      rows={3}
                      maxLength={500}
                      disabled={!selectedRole.active || submitting !== null}
                      required
                    />
                  </label>
                  <label className="admin-form-field admin-form-wide" htmlFor="role-metadata-reason">
                    역할 정보 변경 사유
                    <textarea
                      id="role-metadata-reason"
                      value={metadataReason}
                      onChange={(event) => setMetadataReason(event.target.value)}
                      rows={2}
                      maxLength={500}
                      disabled={!selectedRole.active || submitting !== null}
                      required
                    />
                  </label>
                </div>

                <div className="admin-action-row">
                  {!selectedRole.system && selectedRole.active ? (
                    <button
                      className="danger-button"
                      type="button"
                      onClick={() => void handleDeactivate()}
                      disabled={submitting !== null}
                    >
                      <Trash2 size={17} />
                      {submitting === 'deactivate' ? '비활성화 중…' : '역할 비활성화'}
                    </button>
                  ) : <span />}
                  <button
                    className="primary-button"
                    type="submit"
                    disabled={!selectedRole.active || submitting !== null}
                  >
                    <Save size={17} />
                    {submitting === 'metadata' ? '저장 중…' : '역할 정보 저장'}
                  </button>
                </div>
              </form>

              <form className="content-card admin-detail-card" onSubmit={handlePermissionSave}>
                <div>
                  <h3>기능 권한</h3>
                  <p>
                    실제 기능에서 검증되는 permission만 표시하며 서버가 최종 인가합니다.
                  </p>
                </div>

                <div className="permission-groups">
                  {permissionsByResource.map(([resourceType, items]) => (
                    <fieldset className="permission-group" key={resourceType}>
                      <legend>{resourceType}</legend>
                      {items.map((permission) => {
                        const adminOnlyPermission =
                          permission.permissionCode === 'ADMIN_SETTINGS_MANAGE'
                          && selectedRole.roleCode !== 'ADMIN'
                        return (
                          <label key={permission.permissionId}>
                            <input
                              type="checkbox"
                              checked={selectedPermissionIds.includes(permission.permissionId)}
                              onChange={() => togglePermission(permission.permissionId)}
                              disabled={
                                !selectedRole.active
                                || !permission.active
                                || adminOnlyPermission
                                || submitting !== null
                              }
                            />
                            <span>
                              <strong>{permission.permissionName}</strong>
                              <small>{permission.permissionCode}</small>
                            </span>
                          </label>
                        )
                      })}
                    </fieldset>
                  ))}
                </div>

                <label className="admin-form-field" htmlFor="role-permission-reason">
                  권한 변경 사유
                  <textarea
                    id="role-permission-reason"
                    value={permissionReason}
                    onChange={(event) => setPermissionReason(event.target.value)}
                    rows={2}
                    maxLength={500}
                    disabled={!selectedRole.active || submitting !== null}
                    required
                  />
                </label>
                <div className="admin-action-row admin-action-end">
                  <button
                    className="primary-button"
                    type="submit"
                    disabled={!selectedRole.active || submitting !== null}
                  >
                    <Save size={17} />
                    {submitting === 'permissions' ? '저장 중…' : '기능 권한 저장'}
                  </button>
                </div>
              </form>
            </div>
          ) : null}
        </div>
      )}
    </section>
  )
}
