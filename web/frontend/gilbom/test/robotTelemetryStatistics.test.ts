import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createDefaultTelemetryStatisticsRange,
  createRollingTelemetryStatisticsPeriod,
  formatTelemetryMetric,
  selectTelemetrySeriesInterval,
  toTelemetryStatisticsPeriod,
} from '../src/features/robot/robotTelemetryStatistics.ts'

test('기본 Telemetry 통계 조회 기간은 기준 시각 직전 24시간이다', () => {
  const range = createDefaultTelemetryStatisticsRange(
    new Date('2026-07-30T12:30:00Z'),
  )
  const period = toTelemetryStatisticsPeriod(range.from, range.to)

  assert.equal(
    Date.parse(period.to) - Date.parse(period.from),
    24 * 60 * 60 * 1000,
  )
})

test('자동 갱신 기간은 SSE 처리 시각까지의 정확한 최근 24시간이다', () => {
  const period = createRollingTelemetryStatisticsPeriod(
    new Date('2026-07-30T12:30:45.123Z'),
  )

  assert.deepEqual(period, {
    from: '2026-07-29T12:30:45.123Z',
    to: '2026-07-30T12:30:45.123Z',
  })
})

test('시계열 기간에 따라 최대 5천 구간 안의 집계 간격을 선택한다', () => {
  assert.equal(
    selectTelemetrySeriesInterval(
      '2026-07-30T00:00:00Z',
      '2026-07-31T00:00:00Z',
    ),
    '5m',
  )
  assert.equal(
    selectTelemetrySeriesInterval(
      '2026-07-24T00:00:00Z',
      '2026-07-31T00:00:00Z',
    ),
    '15m',
  )
  assert.equal(
    selectTelemetrySeriesInterval(
      '2026-07-01T00:00:00Z',
      '2026-07-31T00:00:00Z',
    ),
    '1h',
  )
})

test('역전 기간과 31일 초과 기간을 차단한다', () => {
  assert.throws(
    () => toTelemetryStatisticsPeriod(
      '2026-07-30T18:00',
      '2026-07-30T18:00',
    ),
    /종료 시각/,
  )
  assert.throws(
    () => toTelemetryStatisticsPeriod(
      '2026-06-01T00:00',
      '2026-07-03T00:00',
    ),
    /31일/,
  )
})

test('Telemetry 수치와 결측값을 구분해 표시한다', () => {
  assert.equal(formatTelemetryMetric(70, '%'), '70.00%')
  assert.equal(formatTelemetryMetric(2.345, 'm/s'), '2.35m/s')
  assert.equal(formatTelemetryMetric(null, '°C'), '—')
})
