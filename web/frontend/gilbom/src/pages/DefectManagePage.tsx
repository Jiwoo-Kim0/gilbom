import { DefectFilter } from '../features/defect/DefectFilter'
import { DefectList } from '../features/defect/DefectList'
import { DefectMap } from '../features/defect/DefectMap'

export function DefectManagePage() {
  return (
    <section className="page-stack" aria-labelledby="defect-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">DEFECTS</p>
          <h2 id="defect-page-title">결함 탐지 현황</h2>
          <p>필터, 목록, 지도 영역을 분리해 각 기능을 독립적으로 확장할 수 있습니다.</p>
        </div>
      </div>
      <DefectFilter />
      <div className="defect-grid">
        <DefectList />
        <DefectMap />
      </div>
    </section>
  )
}
