export const ADMIN_SETTINGS_MANAGE = 'ADMIN_SETTINGS_MANAGE'
export const AUDIT_LOG_VIEW = 'AUDIT_LOG_VIEW'

export type FrontendPermission =
  | typeof ADMIN_SETTINGS_MANAGE
  | typeof AUDIT_LOG_VIEW

export function hasPermission(
  permissions: readonly string[],
  requiredPermission: FrontendPermission,
): boolean {
  return permissions.includes(requiredPermission)
}
