import { ArrowUpDown } from 'lucide-react'

const columns = ['이미지', '결함 유형', '발생 위치', '발생 시간', '상태', '작업']

export function DefectList() {
  return (
    <article className="content-card defect-list-card">
      <div className="table-heading">
        <h3>상세 결함 리스트</h3>
        <div className="defect-list-meta">
          <span>총 —건의 항목</span>
          <button className="defect-sort-button" type="button" disabled>
            <ArrowUpDown size={14} aria-hidden="true" />
            정렬 방식
          </button>
        </div>
      </div>
      <div className="defect-table" role="table" aria-label="결함 상세 목록">
        <div className="defect-table-row defect-table-head" role="row">
          {columns.map((column) => (
            <span key={column} role="columnheader">
              {column}
            </span>
          ))}
        </div>
        <div className="defect-table-empty" role="row">
          <span className="empty-state-icon" aria-hidden="true">
            LI
          </span>
          <p>결함 목록 API 연결 후 접근 가능한 로봇의 결함만 표시합니다.</p>
        </div>
      </div>
    </article>
  )
}
