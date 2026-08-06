import {
  DEFECT_SORT_OPTIONS,
  DEFECT_TYPE_LABELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type { DefectListItem, PageResponse } from './defectTypes'
import { seoulParts } from '../../shared/time/seoulTime.ts'

const columns = [
  '결함 코드',
  '유형',
  '심각도',
  '위치',
  '반복',
  '신뢰도',
  '상태',
]

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/**
 * `toLocaleString('ko-KR')`은 `26. 08. 01. 오전 09:30`처럼 130px을 넘겨 옆 열(반복)에
 * 달라붙었다. 표에서는 직접 조립해 폭을 줄인다.
 *
 * - 24시로 쓴다. `오전/오후`는 40px을 먹고 숫자 위치를 흔든다.
 * - **올해 것은 연도를 뺀다.** 목록 대부분이 올해라 `26.`이 모든 행에 똑같이 붙어
 *   폭만 먹었다. 해가 넘어간 결함에만 붙으니 그때는 오히려 눈에 걸린다.
 */
function formatDateTime(value: string): string {
  // 로컬 getter(getHours 등)를 쓰면 보는 사람 기계 설정에 따라 시각이 달라진다.
  const parts = seoulParts(new Date(value))
  const dayAndTime = `${pad(parts.month)}.${pad(parts.day)}`
    + ` ${pad(parts.hour)}:${pad(parts.minute)}`
  const thisYear = seoulParts(new Date()).year
  return parts.year === thisYear
    ? dayAndTime
    : `${String(parts.year).slice(2)}.${dayAndTime}`
}

function formatCoordinates(defect: DefectListItem): string | null {
  return defect.location === null
    ? null
    : `${defect.location.latitude.toFixed(5)}, ${defect.location.longitude.toFixed(5)}`
}

/** 이름과 좌표를 모두 담은 값. 열에 다 들어가지 않으므로 title로 붙인다. */
function formatLocation(defect: DefectListItem): string {
  const parts: string[] = []
  if (defect.locationLabel) {
    parts.push(defect.locationLabel)
  } else if (defect.zoneId) {
    parts.push(defect.zoneId)
  }
  const coordinates = formatCoordinates(defect)
  if (coordinates !== null) {
    parts.push(coordinates)
  }
  return parts.length > 0 ? parts.join(' · ') : '—'
}

/**
 * 표에 찍을 짧은 위치다. 이름과 좌표를 같이 넣으면 열 폭(180px 남짓)의 두 배가 되어
 * 좌표 중간에서 잘린다. **이름이 있으면 이름만** 쓰고 좌표는 title·상세 카드로 넘긴다.
 *
 * 다만 구역 코드만 있을 때는 같은 구역 결함이 전부 같은 값으로 찍혀 구분이 안 되므로,
 * 그때는 좌표를 함께 남긴다.
 */
function formatLocationShort(defect: DefectListItem): string {
  if (defect.locationLabel) {
    return defect.locationLabel
  }
  const coordinates = formatCoordinates(defect)
  if (defect.zoneId) {
    return coordinates === null
      ? defect.zoneId
      : `${defect.zoneId} · ${coordinates}`
  }
  return coordinates ?? '—'
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
  /** 병합 권한이 있을 때만 체크박스 열을 낸다. */
  selectable: boolean
  checkedIds: string[]
  onSortChange: (sort: string) => void
  onPageChange: (page: number) => void
  onSelect: (defectId: string) => void
  onToggleCheck: (defectId: string) => void
  onRetry: () => void
}

export function DefectList({
  page,
  loading,
  error,
  sort,
  selectedDefectId,
  selectable,
  checkedIds,
  onSortChange,
  onPageChange,
  onSelect,
  onToggleCheck,
  onRetry,
}: DefectListProps) {
  const items = page?.content ?? []
  const currentPage = page?.page ?? 0
  const totalPages = page?.totalPages ?? 0
  const showsFirstSeenAt = sort.startsWith('firstSeenAt,')
  const dateColumnLabel = showsFirstSeenAt ? '최초 발견' : '최근 관측'

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

      <div
        className={`defect-table${selectable ? ' is-selectable' : ''}`}
        role="table"
        aria-label="결함 상세 목록"
      >
        <div className="defect-table-row defect-table-head" role="row">
          {selectable && (
            <span role="columnheader">
              <span className="sr-only">병합 선택</span>
            </span>
          )}
          {[...columns.slice(0, 4), dateColumnLabel, ...columns.slice(4)].map((column) => (
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
            {selectable && (
              <span role="cell">
                <input
                  type="checkbox"
                  aria-label={`${defect.incidentCode} 병합 대상으로 선택`}
                  checked={checkedIds.includes(defect.defectId)}
                  // 행 클릭은 상세 열기다. 체크는 그것과 별개 동작이라 전파를 막는다.
                  onClick={(event) => event.stopPropagation()}
                  onChange={() => onToggleCheck(defect.defectId)}
                />
              </span>
            )}
            <span role="cell" className="defect-cell-code">
              {defect.incidentCode}
            </span>
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
            {/* 셀에는 이름만, 좌표까지 담은 전체 값은 title에 남긴다. */}
            <span
              role="cell"
              className="defect-cell-location"
              title={formatLocation(defect)}
            >
              {formatLocationShort(defect)}
            </span>
            <span role="cell" className="defect-cell-number">
              {formatDateTime(
                showsFirstSeenAt ? defect.firstSeenAt : defect.lastSeenAt,
              )}
            </span>
            <span role="cell" className="defect-cell-number">
              {defect.observationCount}회
            </span>
            <span role="cell" className="defect-cell-number">
              {formatConfidence(defect.latestConfidence)}
            </span>
            <span role="cell" className="defect-cell-status">
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
