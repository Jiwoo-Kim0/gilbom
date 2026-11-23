import {
  ArrowLeft,
  RefreshCw,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useState,
} from 'react'
import {
  Link,
  useParams,
} from 'react-router'
import {
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import { ROBOT_COMMAND_VIEW } from '../auth/permissions'
import { CommandControlPage } from '../command/CommandControlPage'
import { getRobot } from './robotApi'
import { getRobotStatusLabel } from './robotStatusPresentation'
import { RobotTelemetryStatisticsCard } from './RobotTelemetryStatisticsCard'
import type {
  RobotDetail,
  RobotRealtimeEvent,
} from './robotTypes'
import { useRobotRealtime } from './useRobotRealtime'

function formatMetric(value: number | null, suffix: string): string {
  return value === null ? '—' : `${value.toFixed(1)}${suffix}`
}

function formatDateTime(value: string | null): string {
  if (value === null) {
    return '—'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'medium',
    hour12: false,
  }).format(parsed)
}

function detailErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 404) {
    return '로봇을 찾을 수 없거나 접근 권한이 없습니다.'
  }
  return getApiErrorMessage(error)
}

export function RobotDetailPage() {
  const { robotId } = useParams<{ robotId: string }>()
  const { user } = useAuthentication()
  const [robot, setRobot] = useState<RobotDetail | null>(null)
  const [commandRealtimeRevision, setCommandRealtimeRevision] = useState(0)
  const [telemetryRealtimeRevision, setTelemetryRealtimeRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const canViewCommands = user?.permissions.includes(ROBOT_COMMAND_VIEW) ?? false

  const loadRobot = useCallback(async () => {
    if (robotId === undefined) {
      setRobot(null)
      setError(new Error('로봇 식별자가 없습니다.'))
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      setRobot(await getRobot(robotId))
    } catch (nextError) {
      setRobot(null)
      setError(nextError)
    } finally {
      setLoading(false)
    }
  }, [robotId])

  const refreshRobotSnapshot = useCallback(async (
    event?: RobotRealtimeEvent,
  ) => {
    if (robotId === undefined) {
      return
    }
    if (event !== undefined && event.robotId !== robotId) {
      return
    }
    if (
      event === undefined
      || event.eventType === 'COMMAND_STATUS_CHANGED'
    ) {
      setCommandRealtimeRevision((current) => current + 1)
    }
    if (
      event === undefined
      || event.eventType === 'ROBOT_TELEMETRY_UPDATED'
    ) {
      setTelemetryRealtimeRevision((current) => current + 1)
    }
    try {
      setRobot(await getRobot(robotId))
      setError(null)
    } catch (nextError) {
      setError(nextError)
    }
  }, [robotId])

  const { status: streamStatus } = useRobotRealtime(refreshRobotSnapshot)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadRobot()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [loadRobot])

  return (
    <section className="page-stack" aria-labelledby="robot-detail-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">ROBOT DETAIL</p>
          <h2 id="robot-detail-title">{robot?.robotName ?? '로봇 상세'}</h2>
          <p>
            {robot === null
              ? '로봇 상태와 제어 권한을 확인합니다.'
              : `${robot.mqttId} · ${robot.serialNumber}`}
          </p>
        </div>
        <div className="robot-page-actions">
          <button
            className="secondary-button"
            type="button"
            onClick={() => void loadRobot()}
            disabled={loading}
          >
            <RefreshCw size={16} aria-hidden="true" />
            새로고침
          </button>
          <Link className="secondary-button" to="/robots">
            <ArrowLeft size={16} aria-hidden="true" />
            로봇 목록
          </Link>
        </div>
      </div>

      {error ? (
        <div className="page-alert" role="alert">
          {detailErrorMessage(error)}
        </div>
      ) : null}

      {loading ? (
        <article className="content-card">
          <p aria-live="polite">로봇 상세 상태를 불러오는 중입니다.</p>
        </article>
      ) : robot === null ? null : (
        <>
          <article className="content-card robot-detail-card">
            <div className="robot-detail-card-heading">
              <div>
                <p className="eyebrow">CURRENT STATE</p>
                <h3>현재 상태</h3>
              </div>
              <span
                className={`robot-status-badge robot-status-badge-${
                  robot.connectivityStatus === 'ONLINE' ? 'online' : 'offline'
                }`}
              >
                {getRobotStatusLabel(robot.connectivityStatus)}
              </span>
            </div>
            <dl className="robot-detail-grid">
              <div>
                <dt>운영 상태</dt>
                <dd>{getRobotStatusLabel(robot.operationalStatus)}</dd>
              </div>
              <div>
                <dt>전체 상태</dt>
                <dd>{getRobotStatusLabel(robot.overallHealthStatus)}</dd>
              </div>
              <div>
                <dt>로봇 모드</dt>
                <dd>{getRobotStatusLabel(robot.robotMode)}</dd>
              </div>
              <div>
                <dt>임무 상태</dt>
                <dd>{getRobotStatusLabel(robot.missionStatus)}</dd>
              </div>
              <div>
                <dt>배터리</dt>
                <dd>{formatMetric(robot.batteryPercent, '%')}</dd>
              </div>
              <div>
                <dt>Jetson 온도</dt>
                <dd>{formatMetric(robot.jetsonTempC, '°C')}</dd>
              </div>
              <div>
                <dt>현재 속도</dt>
                <dd>{formatMetric(robot.speedMps, ' m/s')}</dd>
              </div>
              <div>
                <dt>마지막 통신</dt>
                <dd>{formatDateTime(robot.lastReceivedAt)}</dd>
              </div>
            </dl>
          </article>

          <RobotTelemetryStatisticsCard
            key={robot.robotId}
            robotId={robot.robotId}
            realtimeRevision={telemetryRealtimeRevision}
          />

          {canViewCommands ? (
            <CommandControlPage
              robot={robot}
              streamStatus={streamStatus}
              realtimeRevision={commandRealtimeRevision}
            />
          ) : null}
        </>
      )}
    </section>
  )
}
