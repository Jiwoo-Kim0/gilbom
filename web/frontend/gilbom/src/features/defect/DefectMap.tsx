const legendItems = [
  { tone: 'progress', label: '진행 중' },
  { tone: 'received', label: '접수됨' },
  { tone: 'done', label: '완료' },
] as const

export function DefectMap() {
  return (
    <article className="content-card defect-map-card" aria-label="결함 지도">
      <div className="defect-map-legend">
        {legendItems.map(({ tone, label }) => (
          <span key={tone} className={`defect-legend-item defect-legend-item-${tone}`}>
            <span className="defect-status-dot" aria-hidden="true" />
            {label} (—)
          </span>
        ))}
      </div>
      <div className="defect-map-placeholder map-placeholder empty-state">
        <span className="empty-state-icon" aria-hidden="true">
          MAP
        </span>
        <h3>결함 지도</h3>
        <p>Naver Maps 연동 후 결함 위치 핀이 이 영역에 표시됩니다.</p>
      </div>
    </article>
  )
}
