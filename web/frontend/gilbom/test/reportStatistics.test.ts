import assert from 'node:assert/strict'
import test from 'node:test'
import {
  aggregatePeriodMetrics,
} from '../src/features/report/reportStatisticsApi.ts'

test('일별 통계를 기간 snapshot으로 합치고 완료율을 다시 계산한다', () => {
  const aggregate = aggregatePeriodMetrics([
    {
      date: '2026-07-01',
      metrics: {
        missionCount: 2,
        completedMissionCount: 1,
        cancelledMissionCount: 1,
        failedMissionCount: 0,
        completionRatePercent: 50,
        totalMissionDurationSeconds: 100,
        incidentEventCount: 2,
        defectCount: 1,
        observationCount: 3,
      },
    },
    {
      date: '2026-07-02',
      metrics: {
        missionCount: 1,
        completedMissionCount: 1,
        cancelledMissionCount: 0,
        failedMissionCount: 0,
        completionRatePercent: 100,
        totalMissionDurationSeconds: 80,
        incidentEventCount: 1,
        defectCount: 2,
        observationCount: 2,
      },
    },
  ])

  assert.deepEqual(aggregate, {
    missionCount: 3,
    completedMissionCount: 2,
    cancelledMissionCount: 1,
    failedMissionCount: 0,
    completionRatePercent: 66.67,
    totalMissionDurationSeconds: 180,
    incidentEventCount: 3,
    defectCount: 3,
    observationCount: 5,
  })
})
