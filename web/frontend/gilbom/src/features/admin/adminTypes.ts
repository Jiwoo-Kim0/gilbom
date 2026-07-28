export type AdminAccountStatus =
  | 'PENDING_APPROVAL'
  | 'ACTIVE'
  | 'DISABLED'
  | 'REJECTED'
  | 'DELETED'

export type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type AdminAssignedRole = {
  roleId: string
  roleCode: string
  roleName: string
  primary: boolean
}

export type AdminUser = {
  userId: string
  userCode: string
  email: string
  displayName: string
  departmentName: string | null
  jobTitle: string | null
  officeName: string | null
  accountStatus: AdminAccountStatus
  active: boolean
  mfaEnrolled: boolean
  roles: AdminAssignedRole[]
  createdAt: string
  updatedAt: string
}

export type AdminRole = {
  roleId: string
  roleCode: string
  roleName: string
  description: string
  system: boolean
  active: boolean
  displayOrder: number
  permissionCodes: string[]
  createdAt: string
  updatedAt: string
}

export type AdminPermission = {
  permissionId: string
  permissionCode: string
  permissionName: string
  description: string
  resourceType: string
  system: boolean
  active: boolean
  displayOrder: number
}
