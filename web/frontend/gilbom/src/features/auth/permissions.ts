export const ADMIN_SETTINGS_MANAGE = 'ADMIN_SETTINGS_MANAGE'
export const AUDIT_LOG_VIEW = 'AUDIT_LOG_VIEW'
export const ROBOT_STATUS_VIEW = 'ROBOT_STATUS_VIEW'
export const MISSION_VIEW = 'MISSION_VIEW'
export const MISSION_MANAGE = 'MISSION_MANAGE'

export type FrontendPermission =
  | typeof ADMIN_SETTINGS_MANAGE
  | typeof AUDIT_LOG_VIEW
  | typeof ROBOT_STATUS_VIEW
  | typeof MISSION_VIEW
  | typeof MISSION_MANAGE

export function hasPermission(
  permissions: readonly string[],
  requiredPermission: FrontendPermission,
): boolean {
  return permissions.includes(requiredPermission)
}
