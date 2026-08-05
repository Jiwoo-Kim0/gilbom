import { Clock, Gamepad2, Gauge, MoreVertical, Thermometer } from 'lucide-react'
import { Link } from 'react-router'
import type { RobotFleetItem, RobotStatus } from './robotTypes'

interface RobotFleetGridProps {
  robots?: RobotFleetItem[]
  loading?: boolean
}

const statusLabel: Record<RobotStatus, string> = {
  online: 'ONLINE',
  warning: 'WARNING',
  charging: 'CHARGING',
  offline: 'OFFLINE',
}

function formatMetric(value: number | null, suffix: string): string {
  return value === null ? '—' : `${value.toFixed(1)}${suffix}`
}

function formatLastReceivedAt(value: string | null): string {
  if (value === null) {
    return '수신 이력 없음'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(parsed)
}

export function RobotFleetGrid({
  robots = [],
  loading = false,
}: RobotFleetGridProps) {
  return (
    <section className="robot-fleet-section" aria-label="로봇 상세 현황">
      <div className="robot-fleet-heading-row">
        <h3 className="robot-fleet-heading">로봇 상세 현황</h3>
        {loading && <span className="robot-fleet-refreshing">최신 상태 확인 중…</span>}
      </div>

      {robots.length === 0 ? (
        <article className="content-card empty-state robot-fleet-empty">
          <span className="empty-state-icon" aria-hidden="true">
            RO
          </span>
          <h3>{loading ? '로봇 상태를 불러오는 중입니다.' : '조회 가능한 로봇이 없습니다.'}</h3>
          <p>
            {loading
              ? '배정된 로봇의 최신 운영 데이터를 확인하고 있습니다.'
              : '검색 조건과 사용자 로봇 배정 범위를 확인해주세요.'}
          </p>
        </article>
      ) : (
        <div className="robot-fleet-grid">
          {robots.map((robot) => (
            <article
              key={robot.id}
              className={`content-card robot-fleet-card robot-fleet-card-${robot.status}`}
            >
              <div className="robot-fleet-card-head">
                <div className="robot-fleet-identity">
                  <span className="robot-fleet-avatar" aria-hidden="true" />
                  <span>
                    <strong>{robot.name}</strong>
                    <small>{robot.mqttId}</small>
                  </span>
                </div>
                <button
                  className="robot-fleet-menu-button"
                  type="button"
                  aria-label={`${robot.name} 옵션`}
                  disabled
                >
                  <MoreVertical size={16} aria-hidden="true" />
                </button>
              </div>

              <span className={`robot-status-badge robot-status-badge-${robot.status}`}>
                {statusLabel[robot.status]}
              </span>

              <div className="robot-fleet-metrics">
                <div className="robot-fleet-metric">
                  <span>배터리 수준</span>
                  <progress
                    className="robot-battery-progress"
                    max={100}
                    value={
                      robot.batteryPercent === null
                        ? 0
                        : Math.max(0, Math.min(100, robot.batteryPercent))
                    }
                    aria-label={`${robot.name} 배터리 수준`}
                  />
                  <strong>
                    {robot.batteryPercent === null
                      ? '—'
                      : `${robot.batteryPercent.toFixed(0)}%`}
                  </strong>
                </div>
                <div className="robot-fleet-metric">
                  <span>Jetson 온도</span>
                  <strong>
                    <Thermometer size={14} aria-hidden="true" />
                    {formatMetric(robot.jetsonTempC, '°C')}
                  </strong>
                </div>
                <div className="robot-fleet-metric">
                  <span>현재 속도</span>
                  <strong>
                    <Gauge size={14} aria-hidden="true" />
                    {formatMetric(robot.speedMps, ' m/s')}
                  </strong>
                </div>
                <div className="robot-fleet-metric">
                  <span>마지막 통신</span>
                  <strong>
                    <Clock size={14} aria-hidden="true" />
                    {formatLastReceivedAt(robot.lastReceivedAt)}
                  </strong>
                </div>
              </div>

              <div className="robot-fleet-context">
                <span>모드 <strong>{robot.robotMode ?? '—'}</strong></span>
                <span>임무 <strong>{robot.missionStatus ?? '—'}</strong></span>
                <span>상태 <strong>{robot.overallHealthStatus}</strong></span>
              </div>
              <Link
                className="robot-command-link"
                to={`/robots/${encodeURIComponent(robot.id)}`}
              >
                <Gamepad2 size={15} aria-hidden="true" />
                상세·제어
              </Link>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
