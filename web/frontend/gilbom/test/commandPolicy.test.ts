import assert from 'node:assert/strict'
import test from 'node:test'
import {
  COMMAND_DEFINITIONS,
  buildCommandRequest,
  getCommandAvailability,
  type CommandPolicyState,
} from '../src/features/command/commandPolicy.ts'
import type { CommandType } from '../src/features/command/commandTypes.ts'
import type { Mission } from '../src/features/mission/missionTypes.ts'

const BASE_STATE: CommandPolicyState = {
  connectivityStatus: 'ONLINE',
  operationalStatus: 'ACTIVE',
  robotMode: 'AUTONOMOUS',
  missionStatus: 'READY',
  hasMissionTarget: true,
  pendingCommandTypes: [],
}

const ALL_COMMANDS: readonly CommandType[] = [
  'START_MISSION',
  'PAUSE_MISSION',
  'RESUME_MISSION',
  'COMPLETE_MISSION',
  'CANCEL_MISSION',
  'EMERGENCY_STOP',
  'CLEAR_EMERGENCY_STOP',
]

const READY_MISSION: Mission = {
  missionId: '10000000-0000-4000-8000-000000000001',
  missionCode: 'MIS-20260730-001',
  robotId: '20000000-0000-4000-8000-000000000002',
  mqttId: 'RBT-001',
  robotName: '길봄 1호',
  patrolAreaId: '30000000-0000-4000-8000-000000000003',
  patrolAreaCode: 'A-01',
  patrolAreaName: 'A 구역',
  patrolRouteId: '40000000-0000-4000-8000-000000000004',
  patrolRouteCode: 'ROUTE-001',
  patrolRouteName: 'A 경로',
  patrolRouteVersion: 1,
  plannedDurationSec: 600,
  elapsedSec: null,
  remainingDurationSec: 600,
  progressObservedAt: null,
  progressStale: false,
  status: 'READY',
  scheduledStartAt: null,
  startedAt: null,
  endedAt: null,
  progressPercent: 0,
  estimatedCompletionAt: null,
  completionReason: null,
  resultDurationSec: null,
  resultIncidentCount: null,
  resultNormalDetectionCount: null,
  resultPayload: null,
  createdBy: '50000000-0000-4000-8000-000000000005',
  updatedBy: '50000000-0000-4000-8000-000000000005',
  createdAt: '2026-07-30T00:00:00Z',
  updatedAt: '2026-07-30T00:00:00Z',
}

test('Mission 상태별 허용 명령을 구분한다', () => {
  const readyEnabled = ALL_COMMANDS.filter((commandType) => (
    getCommandAvailability(commandType, BASE_STATE).enabled
  ))
  assert.deepEqual(readyEnabled, ['START_MISSION', 'CANCEL_MISSION'])

  const runningEnabled = ALL_COMMANDS.filter((commandType) => (
    getCommandAvailability(commandType, {
      ...BASE_STATE,
      missionStatus: 'RUNNING',
    }).enabled
  ))
  assert.deepEqual(runningEnabled, [
    'PAUSE_MISSION',
    'COMPLETE_MISSION',
    'CANCEL_MISSION',
    'EMERGENCY_STOP',
  ])

  const pausedEnabled = ALL_COMMANDS.filter((commandType) => (
    getCommandAvailability(commandType, {
      ...BASE_STATE,
      missionStatus: 'PAUSED',
    }).enabled
  ))
  assert.deepEqual(pausedEnabled, [
    'RESUME_MISSION',
    'CANCEL_MISSION',
    'EMERGENCY_STOP',
  ])
})

test('PAUSED 임무는 재개 전 완료할 수 없다', () => {
  const availability = getCommandAvailability('COMPLETE_MISSION', {
    ...BASE_STATE,
    missionStatus: 'PAUSED',
  })

  assert.equal(availability.enabled, false)
  assert.match(availability.reason ?? '', /먼저 재개/)
})

test('완료 확인문은 로봇 정지 전 절차를 과거 완료형으로 오해시키지 않는다', () => {
  const definition = COMMAND_DEFINITIONS.find((item) => (
    item.commandType === 'COMPLETE_MISSION'
  ))

  assert.ok(definition)
  assert.equal(definition.requiresSafetyConfirmation, true)
  assert.match(definition.impact, /완료 Event/)
  assert.match(definition.safetyMessage ?? '', /완료 명령 후/)
  assert.doesNotMatch(definition.safetyMessage ?? '', /정지했고/)
})

test('Offline과 동일 명령 처리 중에는 실행을 차단한다', () => {
  for (const commandType of ALL_COMMANDS) {
    assert.equal(getCommandAvailability(commandType, {
      ...BASE_STATE,
      connectivityStatus: 'OFFLINE',
    }).enabled, false)
  }

  const pending = getCommandAvailability('START_MISSION', {
    ...BASE_STATE,
    pendingCommandTypes: ['START_MISSION'],
  })
  assert.equal(pending.enabled, false)
  assert.match(pending.reason ?? '', /처리 중/)
})

test('SAFE_STOP에서는 임무 취소와 긴급정지 해제만 허용한다', () => {
  const enabled = ALL_COMMANDS.filter((commandType) => (
    getCommandAvailability(commandType, {
      ...BASE_STATE,
      robotMode: 'SAFE_STOP',
      missionStatus: 'PAUSED',
    }).enabled
  ))
  assert.deepEqual(enabled, ['CANCEL_MISSION', 'CLEAR_EMERGENCY_STOP'])
})

test('활성 임무가 없는 SAFE_STOP에서는 임무 취소를 허용하지 않는다', () => {
  const availability = getCommandAvailability('CANCEL_MISSION', {
    ...BASE_STATE,
    robotMode: 'SAFE_STOP',
    missionStatus: null,
    hasMissionTarget: false,
  })

  assert.equal(availability.enabled, false)
})

test('START는 Mission 업무 코드와 Area 코드를 사용하고 사유는 정규화한다', () => {
  assert.deepEqual(
    buildCommandRequest('COMPLETE_MISSION', READY_MISSION, ''),
    {
      commandType: 'COMPLETE_MISSION',
      parameters: {},
    },
  )
  assert.deepEqual(
    buildCommandRequest('START_MISSION', READY_MISSION, ''),
    {
      commandType: 'START_MISSION',
      parameters: {
        missionId: 'MIS-20260730-001',
        zoneId: 'A-01',
      },
    },
  )
  assert.deepEqual(
    buildCommandRequest('EMERGENCY_STOP', READY_MISSION, '  장애물 감지  '),
    {
      commandType: 'EMERGENCY_STOP',
      parameters: {
        reason: '장애물 감지',
      },
    },
  )
})
