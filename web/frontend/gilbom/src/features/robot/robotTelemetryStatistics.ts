import type { RobotTelemetrySeriesInterval } from './robotTypes'

const DAY_MILLISECONDS = 24 * 60 * 60 * 1000
const MAXIMUM_PERIOD_MILLISECONDS = 31 * DAY_MILLISECONDS
const WEEK_MILLISECONDS = 7 * DAY_MILLISECONDS

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

export function toDateTimeLocalInput(value: Date): string {
  return [
    value.getFullYear(),
    '-',
    pad(value.getMonth() + 1),
    '-',
    pad(value.getDate()),
    'T',
    pad(value.getHours()),
    ':',
    pad(value.getMinutes()),
  ].join('')
}

export function createDefaultTelemetryStatisticsRange(
  reference: Date = new Date(),
): { from: string; to: string } {
  return {
    from: toDateTimeLocalInput(
      new Date(reference.getTime() - DAY_MILLISECONDS),
    ),
    to: toDateTimeLocalInput(reference),
  }
}

export function createRollingTelemetryStatisticsPeriod(
  reference: Date = new Date(),
): { from: string; to: string } {
  return {
    from: new Date(
      reference.getTime() - DAY_MILLISECONDS,
    ).toISOString(),
    to: reference.toISOString(),
  }
}

export function selectTelemetrySeriesInterval(
  from: string,
  to: string,
): RobotTelemetrySeriesInterval {
  const duration = Date.parse(to) - Date.parse(from)
  if (duration <= DAY_MILLISECONDS) {
    return '5m'
  }
  if (duration <= WEEK_MILLISECONDS) {
    return '15m'
  }
  return '1h'
}

export function toTelemetryStatisticsPeriod(
  fromInput: string,
  toInput: string,
): { from: string; to: string } {
  const from = new Date(fromInput)
  const to = new Date(toInput)
  const duration = to.getTime() - from.getTime()
  if (
    fromInput.length === 0
    || toInput.length === 0
    || Number.isNaN(from.getTime())
    || Number.isNaN(to.getTime())
    || duration <= 0
  ) {
    throw new Error('통계 종료 시각은 시작 시각보다 늦어야 합니다.')
  }
  if (duration > MAXIMUM_PERIOD_MILLISECONDS) {
    throw new Error('Telemetry 통계는 최대 31일까지 조회할 수 있습니다.')
  }
  return {
    from: from.toISOString(),
    to: to.toISOString(),
  }
}

export function formatTelemetryMetric(
  value: number | null,
  suffix: string,
): string {
  return value === null ? '—' : `${value.toFixed(2)}${suffix}`
}
