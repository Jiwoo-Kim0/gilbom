export const ADMIN_SETTINGS_MANAGE = 'ADMIN_SETTINGS_MANAGE'
export const AUDIT_LOG_VIEW = 'AUDIT_LOG_VIEW'
export const ROBOT_STATUS_VIEW = 'ROBOT_STATUS_VIEW'
export const ROBOT_REGISTER = 'ROBOT_REGISTER'
export const ROBOT_COMMAND_VIEW = 'ROBOT_COMMAND_VIEW'
export const ROBOT_COMMAND_EXECUTE = 'ROBOT_COMMAND_EXECUTE'
export const MISSION_VIEW = 'MISSION_VIEW'
export const MISSION_MANAGE = 'MISSION_MANAGE'
export const REPORT_VIEW = 'REPORT_VIEW'
export const REPORT_CREATE = 'REPORT_CREATE'
export const REPORT_UPDATE = 'REPORT_UPDATE'
export const REPORT_APPROVE = 'REPORT_APPROVE'
export const DEFECT_STATUS_VIEW = 'DEFECT_STATUS_VIEW'
export const DEFECT_STATUS_UPDATE = 'DEFECT_STATUS_UPDATE'
export const DEFECT_MERGE = 'DEFECT_MERGE'
export const DEFECT_EXPORT = 'DEFECT_EXPORT'

export type FrontendPermission =
  | typeof ADMIN_SETTINGS_MANAGE
  | typeof AUDIT_LOG_VIEW
  | typeof ROBOT_STATUS_VIEW
  | typeof ROBOT_REGISTER
  | typeof ROBOT_COMMAND_VIEW
  | typeof ROBOT_COMMAND_EXECUTE
  | typeof MISSION_VIEW
  | typeof MISSION_MANAGE
  | typeof REPORT_VIEW
  | typeof REPORT_CREATE
  | typeof REPORT_UPDATE
  | typeof REPORT_APPROVE
  | typeof DEFECT_STATUS_VIEW
  | typeof DEFECT_STATUS_UPDATE
  | typeof DEFECT_MERGE
  | typeof DEFECT_EXPORT

export function hasPermission(
  permissions: readonly string[],
  requiredPermission: FrontendPermission,
): boolean {
  return permissions.includes(requiredPermission)
}
