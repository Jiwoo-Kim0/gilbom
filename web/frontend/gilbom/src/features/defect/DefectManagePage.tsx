import { DefectFilter } from './DefectFilter'
import { DefectList } from './DefectList'
import { DefectMap } from './DefectMap'

export function DefectManagePage() {
  return (
    <section className="page-stack" aria-labelledby="defect-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">DEFECTS</p>
          <h2 id="defect-page-title">실시간 결함 상태 모니터링</h2>
          <p>시스템에서 감지된 최신 결함 목록 및 위치 현황입니다.</p>
        </div>
      </div>
      <div className="defect-layout">
        <DefectFilter />
        <DefectMap />
      </div>
      <DefectList />
    </section>
  )
}
