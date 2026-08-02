import assert from 'node:assert/strict'
import test from 'node:test'
import {
  isMissionDeletable,
  isRobotMissionAssignable,
} from '../src/features/mission/missionEligibility.ts'
import type {
  MissionStatus,
  RobotOption,
} from '../src/features/mission/missionTypes.ts'

const robot: RobotOption = {
  robotId: 'robot-1',
  mqttId: 'RBT-001',
  robotName: '순찰 로봇',
  serialNumber: 'SN-001',
  operationalStatus: 'ACTIVE',
  connectivityStatus: 'ONLINE',
  missionStatus: 'COMPLETED',
  missionAssignable: true,
  overallHealthStatus: 'NORMAL',
}

test('종료 Mission 뒤 서버가 재배정 가능으로 판정한 ACTIVE Robot을 후보로 표시한다', () => {
  assert.equal(isRobotMissionAssignable(robot), true)
  assert.equal(isRobotMissionAssignable({
    ...robot,
    missionStatus: 'RUNNING',
    missionAssignable: false,
  }), false)
  assert.equal(isRobotMissionAssignable({
    ...robot,
    operationalStatus: 'DISABLED',
  }), false)
})

test('실행 이력이 없는 CREATED Mission만 화면에서 삭제할 수 있다', () => {
  const preserved: MissionStatus[] = [
    'READY',
    'RUNNING',
    'PAUSED',
    'COMPLETED',
    'CANCELLED',
    'FAILED',
  ]

  assert.equal(isMissionDeletable('CREATED'), true)
  assert.equal(preserved.some(isMissionDeletable), false)
})
