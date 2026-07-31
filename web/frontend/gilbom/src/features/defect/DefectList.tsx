import {
  DEFECT_SORT_OPTIONS,
  DEFECT_TYPE_LABELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type { DefectListItem, PageResponse } from './defectTypes'

const columns = [
  '결함 코드',
  '유형',
  '심각도',
  '위치',
  '최근 관측',
  '반복',
  '신뢰도',
  '상태',
]

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString('ko-KR', {
    year: '2-digit',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * 구역만 보여주면 같은 구역의 결함이 전부 같은 값으로 찍혀 구분이 안 된다.
 * 사람이 읽을 이름(있으면)과 좌표를 함께 보여준다.
 */
function formatLocation(defect: DefectListItem): string {
  const parts: string[] = []
  if (defect.locationLabel) {
    parts.push(defect.locationLabel)
  } else if (defect.zoneId) {
    parts.push(defect.zoneId)
  }
  if (defect.location) {
    parts.push(
      `${defect.location.latitude.toFixed(5)}, ${defect.location.longitude.toFixed(5)}`,
    )
  }
  return parts.length > 0 ? parts.join(' · ') : '—'
}

function formatConfidence(value: number | null): string {
  return value === null ? '—' : `${Math.round(value * 100)}%`
}

interface DefectListProps {
  page: PageResponse<DefectListItem> | null
  loading: boolean
  error: string | null
  sort: string
  selectedDefectId: string | null
  onSortChange: (sort: string) => void
  onPageChange: (page: number) => void
  onSelect: (defectId: string) => void
  onRetry: () => void
}

export function DefectList({
  page,
  loading,
  error,
  sort,
  selectedDefectId,
  onSortChange,
  onPageChange,
  onSelect,
  onRetry,
}: DefectListProps) {
  const items = page?.content ?? []
  const currentPage = page?.page ?? 0
  const totalPages = page?.totalPages ?? 0

  return (
    <article className="content-card defect-list-card">
      <div className="table-heading">
        <h3>상세 결함 리스트</h3>
        <div className="defect-list-meta">
          <span>
            {page === null
              ? '총 —건의 항목'
              : `총 ${page.totalElements}건의 항목`}
          </span>
          <label className="defect-sort-select">
            <span className="sr-only">정렬 방식</span>
            <select
              value={sort}
              disabled={loading}
              onChange={(event) => onSortChange(event.target.value)}
            >
              {DEFECT_SORT_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
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

        {error !== null && (
          <div className="defect-table-empty" role="row">
            <span className="empty-state-icon" aria-hidden="true">
              !
            </span>
            <p>{error}</p>
            <button
              className="secondary-button"
              type="button"
              onClick={onRetry}
            >
              다시 시도
            </button>
          </div>
        )}

        {error === null && loading && items.length === 0 && (
          <div className="defect-table-empty" role="row">
            <p>결함 목록을 불러오는 중입니다…</p>
          </div>
        )}

        {error === null && !loading && items.length === 0 && (
          <div className="defect-table-empty" role="row">
            <span className="empty-state-icon" aria-hidden="true">
              LI
            </span>
            <p>조건에 맞는 결함이 없습니다. 필터를 넓혀 보세요.</p>
          </div>
        )}

        {items.map((defect) => (
          <div
            key={defect.defectId}
            className={`defect-table-row defect-table-body-row${
              defect.defectId === selectedDefectId ? ' is-selected' : ''
            }`}
            role="row"
            tabIndex={0}
            onClick={() => onSelect(defect.defectId)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault()
                onSelect(defect.defectId)
              }
            }}
          >
            <span role="cell">{defect.incidentCode}</span>
            <span role="cell">
              {DEFECT_TYPE_LABELS[defect.defectType] ?? defect.defectType}
            </span>
            <span role="cell">
              <em
                className={`defect-risk defect-risk-${defect.riskLevel.toLowerCase()}`}
              >
                {RISK_LEVEL_LABELS[defect.riskLevel] ?? defect.riskLevel}
              </em>
            </span>
            <span role="cell">{formatLocation(defect)}</span>
            <span role="cell">{formatDateTime(defect.lastSeenAt)}</span>
            <span role="cell">{defect.observationCount}회</span>
            <span role="cell">{formatConfidence(defect.latestConfidence)}</span>
            <span role="cell">
              <em
                className={`defect-status defect-status-${defect.workflowStatus.toLowerCase()}`}
              >
                {WORKFLOW_STATUS_LABELS[defect.workflowStatus]
                  ?? defect.workflowStatus}
              </em>
              {defect.reviewRequired && (
                <em className="defect-review-flag" title="확인이 필요한 결함">
                  검토
                </em>
              )}
            </span>
          </div>
        ))}
      </div>

      {totalPages > 1 && (
        <nav className="defect-pagination" aria-label="결함 목록 페이지">
          <button
            className="secondary-button"
            type="button"
            disabled={loading || currentPage <= 0}
            onClick={() => onPageChange(currentPage - 1)}
          >
            이전
          </button>
          <span>
            {currentPage + 1} / {totalPages}
          </span>
          <button
            className="secondary-button"
            type="button"
            disabled={loading || currentPage + 1 >= totalPages}
            onClick={() => onPageChange(currentPage + 1)}
          >
            다음
          </button>
        </nav>
      )}
    </article>
  )
}
