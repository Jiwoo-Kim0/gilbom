import { Filter, Plus } from 'lucide-react'
import { RobotFleetGrid } from './RobotFleetGrid'
import { RobotMap } from './RobotMap'
import { RobotSystemLog } from './RobotSystemLog'

export function RobotManagePage() {
  return (
    <section className="page-stack" aria-labelledby="robot-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">FLEET</p>
          <h2 id="robot-page-title">로봇 상태 모니터링</h2>
          <p>실시간 함대 위치 및 운영 데이터를 추적합니다.</p>
        </div>
        <div className="robot-page-actions">
          <button className="secondary-button" type="button" disabled>
            <Filter size={16} aria-hidden="true" />
            필터
          </button>
          <button className="primary-button" type="button" disabled>
            <Plus size={16} aria-hidden="true" />
            로봇 등록
          </button>
        </div>
      </div>

      <div className="summary-grid" aria-label="로봇 현황">
        <article className="summary-card">
          <span>전체 로봇</span>
          <strong>—</strong>
          <small>API 연결 전</small>
        </article>
        <article className="summary-card">
          <span>온라인</span>
          <strong>—</strong>
          <small>실시간 상태 예정</small>
        </article>
        <article className="summary-card">
          <span>확인 필요</span>
          <strong>—</strong>
          <small>이벤트 연동 예정</small>
        </article>
      </div>

      <RobotMap />

      <RobotFleetGrid />

      <RobotSystemLog />
    </section>
  )
}
