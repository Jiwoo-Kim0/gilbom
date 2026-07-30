import assert from 'node:assert/strict'
import test from 'node:test'
import {
  parseReportRealtimeEvent,
} from '../src/features/report/useReportRealtime.ts'

test('보고서 SSE 최소 payload를 검증한다', () => {
  const event = parseReportRealtimeEvent(JSON.stringify({
    reportId: '28000000-0000-4000-8000-000000000081',
    reportStatus: 'IN_REVIEW',
    currentVersionNo: 3,
    deleted: false,
    occurredAt: '2026-07-30T10:00:00Z',
  }))

  assert.deepEqual(event, {
    reportId: '28000000-0000-4000-8000-000000000081',
    reportStatus: 'IN_REVIEW',
    currentVersionNo: 3,
    deleted: false,
    occurredAt: '2026-07-30T10:00:00Z',
  })
})

test('상태·버전이 잘못된 SSE payload는 화면 갱신에 사용하지 않는다', () => {
  assert.equal(parseReportRealtimeEvent(JSON.stringify({
    reportId: '28000000-0000-4000-8000-000000000081',
    reportStatus: 'UNKNOWN',
    currentVersionNo: 0,
    deleted: false,
    occurredAt: '2026-07-30T10:00:00Z',
  })), null)
  assert.equal(parseReportRealtimeEvent('not-json'), null)
})
