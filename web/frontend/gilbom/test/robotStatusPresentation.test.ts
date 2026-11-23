import assert from 'node:assert/strict'
import test from 'node:test'
import { getRobotStatusLabel } from '../src/features/robot/robotStatusPresentation.ts'

test('로봇 상태 영문 코드를 사용자용 한글로 변환한다', () => {
  assert.equal(getRobotStatusLabel('Running'), '진행')
  assert.equal(getRobotStatusLabel('Faild'), '실패')
  assert.equal(getRobotStatusLabel('paused'), '정지')
  assert.equal(getRobotStatusLabel('emergencyStop'), '긴급 정지')
  assert.equal(getRobotStatusLabel('emegencyStop'), '긴급 정지')
  assert.equal(getRobotStatusLabel('SAFE_STOP'), '긴급 정지')
  assert.equal(getRobotStatusLabel('connected'), '실시간 연결됨')
})

test('빈 상태와 알 수 없는 신규 상태를 안전하게 표시한다', () => {
  assert.equal(getRobotStatusLabel(null), '—')
  assert.equal(getRobotStatusLabel('', '상태 없음'), '상태 없음')
  assert.equal(getRobotStatusLabel('NEW_MODE'), 'NEW_MODE')
})
