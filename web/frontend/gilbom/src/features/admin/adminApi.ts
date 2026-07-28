import {
  apiFetch,
  apiJson,
  jsonBody,
} from '../../shared/api/apiClient'
import type {
  AdminAccountStatus,
  AdminPermission,
  AdminRole,
  AdminUser,
  PageResponse,
} from './adminTypes'

const ADMIN_BASE_PATH = '/api/v1/admin'

export function getRegistrationRequests(
  page = 0,
  size = 20,
): Promise<PageResponse<AdminUser>> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  })
  return apiJson(`${ADMIN_BASE_PATH}/registration-requests?${query}`)
}

export function approveRegistration(
  userId: string,
  input: {
    roleIds: string[]
    primaryRoleId: string | null
    reason: string
  },
): Promise<AdminUser> {
  return apiJson(
    `${ADMIN_BASE_PATH}/registration-requests/${encodeURIComponent(userId)}/approve`,
    {
      method: 'POST',
      body: jsonBody(input),
    },
  )
}

export async function rejectRegistration(
  userId: string,
  reason: string,
): Promise<void> {
  await apiFetch(
    `${ADMIN_BASE_PATH}/registration-requests/${encodeURIComponent(userId)}/reject`,
    {
      method: 'POST',
      body: jsonBody({ reason }),
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
    },
  )
}

export function getAdminUsers({
  page = 0,
  size = 20,
  accountStatus,
}: {
  page?: number
  size?: number
  accountStatus?: AdminAccountStatus
} = {}): Promise<PageResponse<AdminUser>> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  })
  if (accountStatus !== undefined) {
    query.set('accountStatus', accountStatus)
  }
  return apiJson(`${ADMIN_BASE_PATH}/users?${query}`)
}

export function getAdminRoles(): Promise<AdminRole[]> {
  return apiJson(`${ADMIN_BASE_PATH}/roles`)
}

export function replaceAdminUserRoles(
  userId: string,
  input: {
    roleIds: string[]
    primaryRoleId: string | null
    reason: string
  },
): Promise<AdminUser> {
  return apiJson(
    `${ADMIN_BASE_PATH}/users/${encodeURIComponent(userId)}/roles`,
    {
      method: 'PUT',
      body: jsonBody(input),
    },
  )
}

export function getAdminPermissions(): Promise<AdminPermission[]> {
  return apiJson(`${ADMIN_BASE_PATH}/permissions`)
}

export function createAdminRole(input: {
  roleCode: string
  roleName: string
  description: string
  displayOrder: number
  reason: string
}): Promise<AdminRole> {
  return apiJson(`${ADMIN_BASE_PATH}/roles`, {
    method: 'POST',
    body: jsonBody(input),
  })
}

export function updateAdminRole(
  roleId: string,
  input: {
    roleName: string
    description: string
    displayOrder: number
    reason: string
  },
): Promise<AdminRole> {
  return apiJson(
    `${ADMIN_BASE_PATH}/roles/${encodeURIComponent(roleId)}`,
    {
      method: 'PATCH',
      body: jsonBody(input),
    },
  )
}

export async function deactivateAdminRole(
  roleId: string,
  reason: string,
): Promise<void> {
  await apiFetch(
    `${ADMIN_BASE_PATH}/roles/${encodeURIComponent(roleId)}`,
    {
      method: 'DELETE',
      body: jsonBody({ reason }),
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
    },
  )
}

export function replaceAdminRolePermissions(
  roleId: string,
  permissionIds: string[],
  reason: string,
): Promise<AdminPermission[]> {
  return apiJson(
    `${ADMIN_BASE_PATH}/roles/${encodeURIComponent(roleId)}/permissions`,
    {
      method: 'PUT',
      body: jsonBody({ permissionIds, reason }),
    },
  )
}

export async function resetAdminUserMfa(
  userId: string,
  input: {
    currentPassword: string
    totpCode: string
    reason: string
  },
): Promise<void> {
  await apiFetch(
    `${ADMIN_BASE_PATH}/users/${encodeURIComponent(userId)}/mfa/reset`,
    {
      method: 'POST',
      body: jsonBody(input),
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      // 작업자 재인증 실패의 401은 현재 세션 만료와 구분해 화면에서 처리한다.
      notifyUnauthorized: false,
    },
  )
}
