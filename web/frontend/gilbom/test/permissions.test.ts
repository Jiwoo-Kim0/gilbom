import assert from 'node:assert/strict'
import test from 'node:test'
import {
  ADMIN_SETTINGS_MANAGE,
  AUDIT_LOG_VIEW,
  DEFECT_EXPORT,
  MISSION_MANAGE,
  REPORT_APPROVE,
  ROBOT_COMMAND_EXECUTE,
  isEnforcedPermissionCode,
} from '../src/features/auth/permissions.ts'

test('실제 기능에 연결된 권한만 역할 권한 체크리스트 대상으로 분류한다', () => {
  for (const permissionCode of [
    ADMIN_SETTINGS_MANAGE,
    AUDIT_LOG_VIEW,
    DEFECT_EXPORT,
    MISSION_MANAGE,
    REPORT_APPROVE,
    ROBOT_COMMAND_EXECUTE,
  ]) {
    assert.equal(isEnforcedPermissionCode(permissionCode), true)
  }

  assert.equal(isEnforcedPermissionCode('SYSTEM_LOG_VIEW'), false)
  assert.equal(isEnforcedPermissionCode('ROBOT_EVENT_ACKNOWLEDGE'), false)
  assert.equal(isEnforcedPermissionCode('UNKNOWN_PERMISSION'), false)
})
