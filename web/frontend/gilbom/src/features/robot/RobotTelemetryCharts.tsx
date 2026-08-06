import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type {
  RobotTelemetrySeries,
  RobotTelemetrySeriesInterval,
  RobotTelemetrySeriesPoint,
} from './robotTypes'

type NumericSeriesKey =
  | 'averageBatteryPercent'
  | 'minimumBatteryPercent'
  | 'averageJetsonTemperatureC'
  | 'maximumJetsonTemperatureC'
  | 'averageSpeedMps'
  | 'maximumSpeedMps'

type TelemetryChartLine = {
  dataKey: NumericSeriesKey
  name: string
  color: string
  dashed?: boolean
}

type TelemetryLineChartProps = {
  title: string
  description: string
  unit: string
  points: TelemetryChartPoint[]
  lines: TelemetryChartLine[]
  domain?: [number | 'auto', number | 'auto']
}

type TelemetryChartPoint = Pick<
  RobotTelemetrySeriesPoint,
  NumericSeriesKey
> & {
  bucketTimestamp: number
}

const intervalMilliseconds: Record<
  RobotTelemetrySeriesInterval,
  number
> = {
  '1m': 60_000,
  '5m': 5 * 60_000,
  '15m': 15 * 60_000,
  '1h': 60 * 60_000,
}

const bucketFormatter = new Intl.DateTimeFormat('ko-KR', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

function formatBucket(value: string): string {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : bucketFormatter.format(parsed)
}

function toChartPoints(
  points: RobotTelemetrySeriesPoint[],
  interval: RobotTelemetrySeriesInterval,
): TelemetryChartPoint[] {
  const result: TelemetryChartPoint[] = []
  const intervalDuration = intervalMilliseconds[interval]
  let previousTimestamp: number | null = null
  for (const point of points) {
    const bucketTimestamp = Date.parse(point.bucketAt)
    if (
      previousTimestamp !== null
      && bucketTimestamp - previousTimestamp > intervalDuration * 1.5
    ) {
      result.push({
        bucketTimestamp: previousTimestamp + intervalDuration,
        averageBatteryPercent: null,
        minimumBatteryPercent: null,
        averageJetsonTemperatureC: null,
        maximumJetsonTemperatureC: null,
        averageSpeedMps: null,
        maximumSpeedMps: null,
      })
    }
    result.push({
      bucketTimestamp,
      averageBatteryPercent: point.averageBatteryPercent,
      minimumBatteryPercent: point.minimumBatteryPercent,
      averageJetsonTemperatureC: point.averageJetsonTemperatureC,
      maximumJetsonTemperatureC: point.maximumJetsonTemperatureC,
      averageSpeedMps: point.averageSpeedMps,
      maximumSpeedMps: point.maximumSpeedMps,
    })
    previousTimestamp = bucketTimestamp
  }
  return result
}

function TelemetryLineChart({
  title,
  description,
  unit,
  points,
  lines,
  domain,
}: TelemetryLineChartProps) {
  return (
    <section
      className="robot-telemetry-chart"
      aria-label={`${title} 시계열 그래프`}
    >
      <div className="robot-telemetry-chart-heading">
        <h4>{title}</h4>
        <p>{description}</p>
      </div>
      <ResponsiveContainer width="100%" height={250}>
        <LineChart
          data={points}
          margin={{
            top: 8,
            right: 12,
            bottom: 4,
            left: 0,
          }}
          accessibilityLayer
          syncId="robot-telemetry-series"
        >
          <CartesianGrid strokeDasharray="3 3" vertical={false} />
          <XAxis
            dataKey="bucketTimestamp"
            type="number"
            scale="time"
            domain={['dataMin', 'dataMax']}
            minTickGap={30}
            tickFormatter={(value) =>
              formatBucket(new Date(Number(value)).toISOString())}
          />
          <YAxis
            domain={domain}
            unit={unit}
            width={58}
          />
          <Tooltip
            labelFormatter={(label) =>
              formatBucket(new Date(Number(label)).toISOString())}
            formatter={(value, name) => [
              value === undefined
                ? '—'
                : `${Number(value).toFixed(2)}${unit}`,
              name,
            ]}
          />
          <Legend />
          {lines.map((line) => (
            <Line
              key={line.dataKey}
              type="monotone"
              dataKey={line.dataKey}
              name={line.name}
              stroke={line.color}
              strokeWidth={2}
              strokeDasharray={line.dashed ? '5 4' : undefined}
              dot={false}
              activeDot={{ r: 4 }}
              connectNulls={false}
              isAnimationActive={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </section>
  )
}

export function RobotTelemetryCharts({
  series,
}: {
  series: RobotTelemetrySeries
}) {
  if (series.points.length === 0) {
    return (
      <p className="robot-telemetry-statistics-empty">
        이 기간에는 그래프로 표시할 Telemetry 구간이 없습니다.
      </p>
    )
  }

  const chartPoints = toChartPoints(series.points, series.interval)

  return (
    <div className="robot-telemetry-charts">
      <div className="robot-telemetry-charts-heading">
        <div>
          <h4>Telemetry 추이</h4>
          <p>
            {series.interval} 구간 평균과 경계값 · 총
            {' '}
            {series.points.length}개 지점
          </p>
        </div>
      </div>
      <div className="robot-telemetry-charts-grid">
        <TelemetryLineChart
          title="배터리"
          description="유효 배터리 표본의 평균·최저"
          unit="%"
          points={chartPoints}
          domain={[0, 100]}
          lines={[
            {
              dataKey: 'averageBatteryPercent',
              name: '평균',
              color: '#2f855a',
            },
            {
              dataKey: 'minimumBatteryPercent',
              name: '최저',
              color: '#d97706',
              dashed: true,
            },
          ]}
        />
        <TelemetryLineChart
          title="Jetson 온도"
          description="구간별 평균·최고 온도"
          unit="°C"
          points={chartPoints}
          domain={['auto', 'auto']}
          lines={[
            {
              dataKey: 'averageJetsonTemperatureC',
              name: '평균',
              color: '#2563eb',
            },
            {
              dataKey: 'maximumJetsonTemperatureC',
              name: '최고',
              color: '#dc2626',
              dashed: true,
            },
          ]}
        />
        <TelemetryLineChart
          title="이동 속도"
          description="구간별 평균·최고 속도"
          unit="m/s"
          points={chartPoints}
          domain={[0, 'auto']}
          lines={[
            {
              dataKey: 'averageSpeedMps',
              name: '평균',
              color: '#7c3aed',
            },
            {
              dataKey: 'maximumSpeedMps',
              name: '최고',
              color: '#0891b2',
              dashed: true,
            },
          ]}
        />
      </div>
    </div>
  )
}
