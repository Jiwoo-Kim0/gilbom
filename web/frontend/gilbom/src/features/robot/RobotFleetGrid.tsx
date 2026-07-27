import { Clock, Gauge, MoreVertical, Thermometer } from 'lucide-react'
import type { RobotFleetItem, RobotStatus } from './robotTypes'

interface RobotFleetGridProps {
  robots?: RobotFleetItem[]
}

const statusLabel: Record<RobotStatus, string> = {
  online: 'ONLINE',
  warning: 'WARNING',
  charging: 'CHARGING',
  offline: 'OFFLINE',
}

export function RobotFleetGrid({ robots = [] }: RobotFleetGridProps) {
  return (
    <section className="robot-fleet-section" aria-label="함대 상세 현황">
      <h3 className="robot-fleet-heading">함대 상세 현황</h3>

      {robots.length === 0 ? (
        <article className="content-card empty-state robot-fleet-empty">
          <span className="empty-state-icon" aria-hidden="true">
            RO
          </span>
          <h3>로봇 상세 현황을 연결할 준비가 되었습니다.</h3>
          <p>로봇 상태 API가 연결되면 배터리·온도·속도·가동 시간을 이 카드에서 확인할 수 있습니다.</p>
        </article>
      ) : (
        <div className="robot-fleet-grid">
          {robots.map((robot) => (
            <article
              key={robot.id}
              className={`content-card robot-fleet-card robot-fleet-card-${robot.status}`}
            >
              <div className="robot-fleet-card-head">
                <span className="robot-fleet-id">
                  <span className="robot-fleet-avatar" aria-hidden="true" />
                  {robot.id}
                </span>
                <button
                  className="robot-fleet-menu-button"
                  type="button"
                  aria-label={`${robot.id} 옵션`}
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
                  <div className="robot-battery-bar">
                    <span
                      className="robot-battery-bar-fill"
                      style={{ width: `${Math.max(0, Math.min(100, robot.batteryPercent))}%` }}
                    />
                  </div>
                  <strong>{robot.batteryPercent}%</strong>
                </div>
                <div className="robot-fleet-metric">
                  <span>CPU 온도</span>
                  <strong>
                    <Thermometer size={14} aria-hidden="true" />
                    {robot.cpuTempC.toFixed(1)}°C
                  </strong>
                </div>
                <div className="robot-fleet-metric">
                  <span>현재 속도</span>
                  <strong>
                    <Gauge size={14} aria-hidden="true" />
                    {robot.speedMps.toFixed(1)} m/s
                  </strong>
                </div>
                <div className="robot-fleet-metric">
                  <span>가동 시간</span>
                  <strong>
                    <Clock size={14} aria-hidden="true" />
                    {robot.uptimeLabel}
                  </strong>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
