import { AlertTriangle, CheckCircle2, Info } from 'lucide-react'
import type { RobotLogEntry, RobotLogLevel } from './robotTypes'

interface RobotSystemLogProps {
  logs?: RobotLogEntry[]
}

const levelIcon: Record<RobotLogLevel, typeof Info> = {
  warning: AlertTriangle,
  info: Info,
  success: CheckCircle2,
}

export function RobotSystemLog({ logs = [] }: RobotSystemLogProps) {
  return (
    <article className="content-card robot-log-card">
      <div className="table-heading">
        <h3>실시간 시스템 로그</h3>
        <button className="robot-log-view-all" type="button" disabled>
          전체 보기
        </button>
      </div>

      {logs.length === 0 ? (
        <div className="robot-log-empty">
          <span className="empty-state-icon" aria-hidden="true">
            LOG
          </span>
          <p>로봇 이벤트 API가 연결되면 실시간 시스템 로그가 이곳에 표시됩니다.</p>
        </div>
      ) : (
        <ul className="robot-log-list">
          {logs.map((log) => {
            const Icon = levelIcon[log.level]
            return (
              <li key={log.id} className={`robot-log-item robot-log-item-${log.level}`}>
                <span className="robot-log-icon" aria-hidden="true">
                  <Icon size={16} />
                </span>
                <div className="robot-log-body">
                  <div className="robot-log-meta">
                    <strong>{log.robotId}</strong>
                    <span>{log.timestamp}</span>
                  </div>
                  <p>{log.message}</p>
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </article>
  )
}
