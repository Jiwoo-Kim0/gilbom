import { apiJson } from '../../shared/api/apiClient.ts'
import type {
  ReportAggregateMetrics,
  ReportDefectStatusCount,
  ReportDefectTypeCount,
  ReportStatisticsSnapshot,
} from './reportTypes.ts'

type StatisticsByStatusResponse = {
  from: string
  to: string
  totalDefectCount: number
  items: ReportDefectStatusCount[]
}

type StatisticsByDefectTypeResponse = {
  from: string
  to: string
  totalDefectCount: number
  items: ReportDefectTypeCount[]
}

export type StatisticsByPeriodItem = {
  date: string
  metrics: ReportAggregateMetrics
}

type StatisticsByPeriodResponse = {
  from: string
  to: string
  granularity: 'DAY'
  items: StatisticsByPeriodItem[]
}

function periodQuery(from: string, to: string): string {
  return new URLSearchParams({ from, to }).toString()
}

function roundPercent(value: number): number {
  return Math.round(value * 100) / 100
}

export function aggregatePeriodMetrics(
  items: readonly StatisticsByPeriodItem[],
): ReportAggregateMetrics {
  const aggregate = items.reduce<ReportAggregateMetrics>(
    (total, item) => ({
      missionCount: total.missionCount + item.metrics.missionCount,
      completedMissionCount:
        total.completedMissionCount + item.metrics.completedMissionCount,
      cancelledMissionCount:
        total.cancelledMissionCount + item.metrics.cancelledMissionCount,
      failedMissionCount:
        total.failedMissionCount + item.metrics.failedMissionCount,
      completionRatePercent: 0,
      totalMissionDurationSeconds:
        total.totalMissionDurationSeconds
        + item.metrics.totalMissionDurationSeconds,
      incidentEventCount:
        total.incidentEventCount + item.metrics.incidentEventCount,
      defectCount: total.defectCount + item.metrics.defectCount,
      observationCount:
        total.observationCount + item.metrics.observationCount,
    }),
    {
      missionCount: 0,
      completedMissionCount: 0,
      cancelledMissionCount: 0,
      failedMissionCount: 0,
      completionRatePercent: 0,
      totalMissionDurationSeconds: 0,
      incidentEventCount: 0,
      defectCount: 0,
      observationCount: 0,
    },
  )

  return {
    ...aggregate,
    completionRatePercent: aggregate.missionCount === 0
      ? 0
      : roundPercent(
        aggregate.completedMissionCount / aggregate.missionCount * 100,
      ),
  }
}

export async function loadReportStatistics(
  from: string,
  to: string,
): Promise<ReportStatisticsSnapshot> {
  const query = periodQuery(from, to)
  const [period, byStatus, byDefectType] = await Promise.all([
    apiJson<StatisticsByPeriodResponse>(
      `/api/v1/statistics/by-period?${query}&granularity=DAY`,
    ),
    apiJson<StatisticsByStatusResponse>(
      `/api/v1/statistics/by-status?${query}`,
    ),
    apiJson<StatisticsByDefectTypeResponse>(
      `/api/v1/statistics/by-defect-type?${query}`,
    ),
  ])

  return {
    aggregate: aggregatePeriodMetrics(period.items),
    defectsByStatus: byStatus.items,
    defectsByType: byDefectType.items,
  }
}
