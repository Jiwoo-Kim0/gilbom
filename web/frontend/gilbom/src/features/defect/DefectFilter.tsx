import { DefectFilterDropdown } from './DefectFilterDropdown'
import {
  DEFECT_TYPES,
  DEFECT_TYPE_LABELS,
  EMPTY_DEFECT_FILTERS,
  RISK_LEVELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUSES,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type { DefectFilters } from './defectTypes'

interface DefectFilterProps {
  filters: DefectFilters
  onChange: (filters: DefectFilters) => void
  disabled?: boolean
  /**
   * "필터 초기화"를 눌렀다는 신호. 검색어를 비우는 것은 `onChange`로 전달되지만,
   * 아직 올려보내지 않은(디바운스 대기 중인) 입력까지 버리려면 상위가 알아야 한다.
   */
  onReset?: () => void
}

/**
 * `filters.from`은 서버로 나갈 때 시각까지 쓰는 datetime-local 문자열인데, 화면에서는
 * 날짜만 받는다. 그래서 앞 10자만 잘라 보여주고, 고른 날짜는 그 날 0시로 되돌린다.
 * 상한(`to`)은 받지 않는다. 결함은 미래에 생기지 않으니 늘 "지금"이다.
 */
function toDateInputValue(from: string): string {
  return from.slice(0, 10)
}

function fromDateInputValue(date: string): string {
  return date === '' ? '' : `${date}T00:00`
}

/**
 * 지도 오른쪽에 세로로 붙는 조건 패널이다. 검색은 화면 맨 위 {@link DefectSearchBar}로
 * 따로 나가 있다.
 */
export function DefectFilter({
  filters,
  onChange,
  disabled = false,
  onReset,
}: DefectFilterProps) {
  const update = (partial: Partial<DefectFilters>) => {
    onChange({ ...filters, ...partial })
  }

  return (
    <form
      className="content-card defect-filter"
      aria-label="결함 필터"
      onSubmit={(event) => event.preventDefault()}
    >
      <div className="defect-filter-group">
        <div className="defect-filter-group-heading">
          <label htmlFor="defect-filter-from">시작 날짜</label>
          {filters.from.length > 0 && (
            <button
              type="button"
              className="defect-filter-clear-date"
              disabled={disabled}
              onClick={() => update({ from: '' })}
            >
              전체 기간
            </button>
          )}
        </div>
        <input
          id="defect-filter-from"
          type="date"
          className="defect-filter-date-input"
          value={toDateInputValue(filters.from)}
          disabled={disabled}
          aria-describedby="defect-filter-from-hint"
          onChange={(event) =>
            update({ from: fromDateInputValue(event.target.value) })
          }
        />
        {/* 서버는 이 조건을 last_seen_at에 건다. "최초 발견"으로 읽으면 오해한다. */}
        <small id="defect-filter-from-hint">이 날짜 이후 관측된 결함</small>
      </div>

      {/*
        조건 셋은 접어둔다. 펼쳐 두면 체크박스 14개가 패널을 다 먹어서
        신뢰도·초기화가 화면 밖으로 밀렸다.
      */}
      <DefectFilterDropdown
        label="처리 상태"
        options={WORKFLOW_STATUSES}
        optionLabels={WORKFLOW_STATUS_LABELS}
        selected={filters.workflowStatuses}
        disabled={disabled}
        onChange={(workflowStatuses) => update({ workflowStatuses })}
        renderMark={(status) => (
          <span
            className={`defect-status-dot defect-status-dot-${status.toLowerCase()}`}
            aria-hidden="true"
          />
        )}
      />

      <DefectFilterDropdown
        label="결함 유형"
        options={DEFECT_TYPES}
        optionLabels={DEFECT_TYPE_LABELS}
        selected={filters.defectTypes}
        disabled={disabled}
        onChange={(defectTypes) => update({ defectTypes })}
      />

      <DefectFilterDropdown
        label="심각도"
        options={RISK_LEVELS}
        optionLabels={RISK_LEVEL_LABELS}
        selected={filters.severities}
        disabled={disabled}
        onChange={(severities) => update({ severities })}
      />

      <div className="defect-filter-group">
        <label
          className="defect-filter-group-heading"
          htmlFor="defect-filter-min-confidence"
        >
          최소 AI 신뢰도
          {filters.minConfidence !== null
            && ` ${Math.round(filters.minConfidence * 100)}%`}
        </label>
        <input
          id="defect-filter-min-confidence"
          type="range"
          min={0}
          max={100}
          step={5}
          disabled={disabled}
          value={
            filters.minConfidence === null
              ? 0
              : Math.round(filters.minConfidence * 100)
          }
          onChange={(event) => {
            const percent = Number(event.target.value)
            // 0%는 "제한 없음"이므로 파라미터 자체를 보내지 않는다.
            update({ minConfidence: percent === 0 ? null : percent / 100 })
          }}
        />
      </div>

      {/* 검색어까지 함께 지운다. 검색창이 위에 보이므로 지워진 것도 바로 보인다. */}
      <button
        className="secondary-button defect-filter-reset"
        type="button"
        disabled={disabled}
        onClick={() => {
          onChange(EMPTY_DEFECT_FILTERS)
          onReset?.()
        }}
      >
        필터 초기화
      </button>
    </form>
  )
}
