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
}

/**
 * 지도 오른쪽에 세로로 붙는 조건 패널이다. 검색은 화면 맨 위 {@link DefectSearchBar}로
 * 따로 나가 있고, 기간 조건은 쓰지 않는다(전체 기간을 본다).
 */
export function DefectFilter({
  filters,
  onChange,
  disabled = false,
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

      <fieldset className="defect-filter-group" disabled={disabled}>
        <legend>
          최소 AI 신뢰도
          {filters.minConfidence !== null
            && ` ${Math.round(filters.minConfidence * 100)}%`}
        </legend>
        <input
          type="range"
          min={0}
          max={100}
          step={5}
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
      </fieldset>

      {/* 검색어까지 함께 지운다. 검색창이 위에 보이므로 지워진 것도 바로 보인다. */}
      <button
        className="secondary-button defect-filter-reset"
        type="button"
        disabled={disabled}
        onClick={() => onChange(EMPTY_DEFECT_FILTERS)}
      >
        필터 초기화
      </button>
    </form>
  )
}
