export const ADMIN_SETTINGS_MANAGE = 'ADMIN_SETTINGS_MANAGE'
export const AUDIT_LOG_VIEW = 'AUDIT_LOG_VIEW'
export const ROBOT_STATUS_VIEW = 'ROBOT_STATUS_VIEW'
export const MISSION_VIEW = 'MISSION_VIEW'
export const MISSION_MANAGE = 'MISSION_MANAGE'
export const REPORT_VIEW = 'REPORT_VIEW'
export const REPORT_CREATE = 'REPORT_CREATE'
export const REPORT_UPDATE = 'REPORT_UPDATE'
export const REPORT_APPROVE = 'REPORT_APPROVE'

export type FrontendPermission =
  | typeof ADMIN_SETTINGS_MANAGE
  | typeof AUDIT_LOG_VIEW
  | typeof ROBOT_STATUS_VIEW
  | typeof MISSION_VIEW
  | typeof MISSION_MANAGE
  | typeof REPORT_VIEW
  | typeof REPORT_CREATE
  | typeof REPORT_UPDATE
  | typeof REPORT_APPROVE

export function hasPermission(
  permissions: readonly string[],
  requiredPermission: FrontendPermission,
): boolean {
  return permissions.includes(requiredPermission)
}
