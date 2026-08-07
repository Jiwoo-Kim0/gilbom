import assert from 'node:assert/strict'
import test from 'node:test'
import {
  getCommandOperatorGuidance,
  getCommandStatusPresentation,
  getCommandTypeLabel,
} from '../src/features/command/commandPresentation.ts'
import type {
  CommandResponse,
  CommandStatus,
} from '../src/features/command/commandTypes.ts'

function commandWithStatus(commandStatus: CommandStatus): CommandResponse {
  return {
    commandId: '10000000-0000-4000-8000-000000000001',
    robotId: '20000000-0000-4000-8000-000000000002',
    mqttId: 'RBT-001',
    commandType: 'EMERGENCY_STOP',
    commandStatus,
    parameters: {
      missionId: null,
      zoneId: null,
      reason: '장애물 감지',
    },
    requestedByType: 'ADMIN',
    requestedByUserId: '30000000-0000-4000-8000-000000000003',
    requestedByUserCode: 'admin01',
    issuedAt: '2026-07-30T07:00:00Z',
    expiresAt: '2026-07-30T07:00:10Z',
    sentAt: '2026-07-30T07:00:01Z',
    acceptedAt: null,
    completedAt: null,
    publishAttemptCount: 1,
    lastErrorCode: null,
    createdAt: '2026-07-30T07:00:00Z',
    updatedAt: '2026-07-30T07:00:01Z',
  }
}

test('ACK를 최종 성공과 구분하고 완료만 성공 종결로 표시한다', () => {
  const accepted = getCommandStatusPresentation('ACCEPTED')
  const completed = getCommandStatusPresentation('COMPLETED')

  assert.equal(accepted.label, '로봇 수신 확인')
  assert.equal(accepted.tone, 'ack')
  assert.equal(accepted.terminal, false)
  assert.equal(completed.tone, 'success')
  assert.equal(completed.terminal, true)
})

test('실패·거절·Timeout 상태를 서로 다른 의미로 분류한다', () => {
  assert.equal(getCommandStatusPresentation('FAILED').tone, 'failure')
  assert.equal(getCommandStatusPresentation('REJECTED').tone, 'rejected')
  assert.equal(getCommandStatusPresentation('EXPIRED').tone, 'expired')
  assert.match(
    getCommandStatusPresentation('EXPIRED').description,
    /최종 결과/,
  )
})

test('Timeout과 발행 실패는 무조건 재시도하지 않도록 안내한다', () => {
  assert.match(
    getCommandOperatorGuidance(commandWithStatus('EXPIRED')) ?? '',
    /실행 여부가 불명확/,
  )
  assert.match(
    getCommandOperatorGuidance(commandWithStatus('PUBLISH_FAILED')) ?? '',
    /메시지 중계 서버 연결/,
  )
  assert.equal(
    getCommandOperatorGuidance(commandWithStatus('COMPLETED')),
    null,
  )
})

test('명령 유형을 관리자용 한글 라벨로 표시한다', () => {
  assert.equal(getCommandTypeLabel('START_MISSION'), '임무 시작')
  assert.equal(getCommandTypeLabel('EMERGENCY_STOP'), '긴급정지')
})
