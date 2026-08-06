import {
  Activity,
  RefreshCw,
} from 'lucide-react'
import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import {
  getRobotTelemetrySeries,
  getRobotTelemetryStatistics,
} from './robotApi'
import {
  createDefaultTelemetryStatisticsRange,
  createRollingTelemetryStatisticsPeriod,
  formatTelemetryMetric,
  selectTelemetrySeriesInterval,
  toTelemetryStatisticsPeriod,
} from './robotTelemetryStatistics'
import type {
  RobotTelemetrySeries,
  RobotTelemetryStatistics,
} from './robotTypes'
import './robot-telemetry-statistics.css'
import { SEOUL_TIME_ZONE } from '../../shared/time/seoulTime.ts'

type RobotTelemetryStatisticsCardProps = {
  robotId: string
  realtimeRevision: number
}

const REALTIME_REFRESH_INTERVAL_MILLISECONDS = 5_000
const RobotTelemetryCharts = lazy(async () => {
  const module = await import('./RobotTelemetryCharts')
  return { default: module.RobotTelemetryCharts }
})

function formatDateTime(value: string | null): string {
  if (value === null) {
    return '—'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: SEOUL_TIME_ZONE,
    dateStyle: 'medium',
    timeStyle: 'medium',
    hour12: false,
  }).format(parsed)
}

export function RobotTelemetryStatisticsCard({
  robotId,
  realtimeRevision,
}: RobotTelemetryStatisticsCardProps) {
  const [initialReference] = useState(() => new Date())
  const [initialRange] = useState(() =>
    createDefaultTelemetryStatisticsRange(initialReference))
  const [fromInput, setFromInput] = useState(initialRange.from)
  const [toInput, setToInput] = useState(initialRange.to)
  const [requestedPeriod, setRequestedPeriod] = useState(() =>
    createRollingTelemetryStatisticsPeriod(initialReference))
  const [statistics, setStatistics] =
    useState<RobotTelemetryStatistics | null>(null)
  const [series, setSeries] = useState<RobotTelemetrySeries | null>(null)
  const [liveMode, setLiveMode] = useState(true)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const realtimeRefreshTimerRef = useRef<number | null>(null)
  const lastHandledRealtimeRevisionRef = useRef(realtimeRevision)

  const clearRealtimeRefreshTimer = useCallback(() => {
    if (realtimeRefreshTimerRef.current !== null) {
      window.clearTimeout(realtimeRefreshTimerRef.current)
      realtimeRefreshTimerRef.current = null
    }
  }, [])

  const requestRollingPeriod = useCallback(() => {
    const reference = new Date()
    const nextRange = createDefaultTelemetryStatisticsRange(reference)
    setFromInput(nextRange.from)
    setToInput(nextRange.to)
    setRequestedPeriod(
      createRollingTelemetryStatisticsPeriod(reference),
    )
  }, [])

  useEffect(() => {
    let active = true
    const timer = window.setTimeout(() => {
      setLoading(true)
      setError(null)
      const interval = selectTelemetrySeriesInterval(
        requestedPeriod.from,
        requestedPeriod.to,
      )
      void Promise.all([
        getRobotTelemetryStatistics(
          robotId,
          requestedPeriod.from,
          requestedPeriod.to,
        ),
        getRobotTelemetrySeries(
          robotId,
          requestedPeriod.from,
          requestedPeriod.to,
          interval,
        ),
      ]).then(([statisticsResponse, seriesResponse]) => {
        if (active) {
          setStatistics(statisticsResponse)
          setSeries(seriesResponse)
        }
      }).catch((nextError: unknown) => {
        if (active) {
          setError(nextError)
        }
      }).finally(() => {
        if (active) {
          setLoading(false)
        }
      })
    }, 0)
    return () => {
      active = false
      window.clearTimeout(timer)
    }
  }, [requestedPeriod, robotId])

  useEffect(() => {
    if (!liveMode) {
      lastHandledRealtimeRevisionRef.current = realtimeRevision
      return
    }
    if (
      realtimeRevision === lastHandledRealtimeRevisionRef.current
      || realtimeRefreshTimerRef.current !== null
    ) {
      return
    }
    lastHandledRealtimeRevisionRef.current = realtimeRevision
    realtimeRefreshTimerRef.current = window.setTimeout(() => {
      realtimeRefreshTimerRef.current = null
      requestRollingPeriod()
    }, REALTIME_REFRESH_INTERVAL_MILLISECONDS)
  }, [liveMode, realtimeRevision, requestRollingPeriod])

  useEffect(() => () => {
    clearRealtimeRefreshTimer()
  }, [clearRealtimeRefreshTimer, robotId])

  function pauseLiveMode() {
    clearRealtimeRefreshTimer()
    setLiveMode(false)
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    try {
      pauseLiveMode()
      setError(null)
      setRequestedPeriod(toTelemetryStatisticsPeriod(fromInput, toInput))
    } catch (nextError) {
      setError(nextError)
    }
  }

  function handleEnableLiveMode() {
    clearRealtimeRefreshTimer()
    lastHandledRealtimeRevisionRef.current = realtimeRevision
    setLiveMode(true)
    setError(null)
    requestRollingPeriod()
  }

  return (
    <article className="content-card robot-telemetry-statistics">
      <div className="robot-telemetry-statistics-heading">
        <div>
          <p className="eyebrow">TELEMETRY STATISTICS</p>
          <h3>
            <Activity size={19} aria-hidden="true" />
            로봇 Telemetry 통계
          </h3>
          <p>
            MQTT v2 측정 이력의 배터리·온도·속도·GPS 품질을 집계합니다.
          </p>
          <p
            className={`robot-telemetry-statistics-live-status ${
              liveMode ? 'is-live' : ''
            }`}
          >
            {liveMode
              ? '자동 갱신 중 · SSE 수신 후 최대 5초 이내 DB 통계를 다시 조회합니다.'
              : '고정 기간 조회 중 · 자동 갱신이 일시 중지되었습니다.'}
          </p>
        </div>
        <form
          className="robot-telemetry-statistics-filter"
          onSubmit={handleSubmit}
        >
          <label htmlFor="robot-telemetry-statistics-from">
            시작
            <input
              id="robot-telemetry-statistics-from"
              type="datetime-local"
              value={fromInput}
              onChange={(event) => {
                pauseLiveMode()
                setFromInput(event.target.value)
              }}
              disabled={loading}
              required
            />
          </label>
          <label htmlFor="robot-telemetry-statistics-to">
            종료
            <input
              id="robot-telemetry-statistics-to"
              type="datetime-local"
              value={toInput}
              onChange={(event) => {
                pauseLiveMode()
                setToInput(event.target.value)
              }}
              disabled={loading}
              required
            />
          </label>
          <button
            className="secondary-button"
            type="submit"
            disabled={loading}
          >
            <RefreshCw
              size={15}
              className={loading ? 'is-spinning' : ''}
              aria-hidden="true"
            />
            {loading ? '조회 중' : '조회'}
          </button>
          <button
            className="secondary-button"
            type="button"
            onClick={handleEnableLiveMode}
            disabled={loading && liveMode}
          >
            24시간 자동 갱신
          </button>
        </form>
      </div>

      {error ? (
        <div className="page-alert" role="alert">
          Telemetry 통계를 불러오지 못했습니다. {getApiErrorMessage(error)}
        </div>
      ) : null}

      {statistics ? (
        <>
          <dl className="robot-telemetry-statistics-grid" aria-live="polite">
            <div>
              <dt>Telemetry 표본</dt>
              <dd>{statistics.sampleCount}건</dd>
            </div>
            <div>
              <dt>배터리 평균 · 최저</dt>
              <dd>
                {formatTelemetryMetric(
                  statistics.averageBatteryPercent,
                  '%',
                )}
                {' · '}
                {formatTelemetryMetric(
                  statistics.minimumBatteryPercent,
                  '%',
                )}
              </dd>
              <small>유효 표본 {statistics.validBatterySampleCount}건</small>
            </div>
            <div>
              <dt>Jetson 평균 · 최고</dt>
              <dd>
                {formatTelemetryMetric(
                  statistics.averageJetsonTemperatureC,
                  '°C',
                )}
                {' · '}
                {formatTelemetryMetric(
                  statistics.maximumJetsonTemperatureC,
                  '°C',
                )}
              </dd>
            </div>
            <div>
              <dt>속도 평균 · 최고</dt>
              <dd>
                {formatTelemetryMetric(
                  statistics.averageSpeedMps,
                  'm/s',
                )}
                {' · '}
                {formatTelemetryMetric(
                  statistics.maximumSpeedMps,
                  'm/s',
                )}
              </dd>
            </div>
            <div>
              <dt>GPS 유효율</dt>
              <dd>{statistics.gpsValidRatePercent}%</dd>
            </div>
            <div>
              <dt>GPS 유효 · 무효</dt>
              <dd>
                {statistics.validGpsSampleCount}
                {' · '}
                {statistics.invalidGpsSampleCount}건
              </dd>
            </div>
            <div className="robot-telemetry-statistics-wide">
              <dt>마지막 측정</dt>
              <dd>{formatDateTime(statistics.lastSampledAt)}</dd>
            </div>
          </dl>
          {statistics.sampleCount === 0 ? (
            <p className="robot-telemetry-statistics-empty">
              이 기간에 수신된 Telemetry가 없습니다. Mock Robot 또는 실제
              기기의 MQTT Telemetry 발행 상태를 확인해주세요.
            </p>
          ) : null}
          {series ? (
            <Suspense
              fallback={(
                <p className="robot-telemetry-statistics-empty">
                  Telemetry 그래프를 준비하는 중입니다.
                </p>
              )}
            >
              <RobotTelemetryCharts series={series} />
            </Suspense>
          ) : null}
        </>
      ) : loading ? (
        <p className="robot-telemetry-statistics-empty" aria-live="polite">
          Telemetry 통계를 불러오는 중입니다.
        </p>
      ) : null}
    </article>
  )
}
