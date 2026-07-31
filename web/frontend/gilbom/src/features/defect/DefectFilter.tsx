import { Check } from 'lucide-react'
import {
  DEFECT_TYPES,
  DEFECT_TYPE_LABELS,
  EMPTY_DEFECT_FILTERS,
  RISK_LEVELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUSES,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type {
  DefectFilters,
  DefectType,
  RiskLevel,
  WorkflowStatus,
} from './defectTypes'

const dateRangeOptions: { value: string; label: string; days: number }[] = [
  { value: 'today', label: '오늘', days: 0 },
  { value: '7d', label: '지난 7일', days: 7 },
  { value: '30d', label: '지난 30일', days: 30 },
]

/** `<input type="datetime-local">`이 요구하는 로컬 시각 문자열이다. */
function toLocalInputValue(date: Date): string {
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

function rangeFor(days: number): { from: string; to: string } {
  const now = new Date()
  const from = new Date(now)
  if (days === 0) {
    from.setHours(0, 0, 0, 0)
  } else {
    from.setDate(from.getDate() - days)
  }
  return { from: toLocalInputValue(from), to: toLocalInputValue(now) }
}

function toggle<T>(values: T[], value: T): T[] {
  return values.includes(value)
    ? values.filter((item) => item !== value)
    : [...values, value]
}

interface DefectFilterProps {
  filters: DefectFilters
  onChange: (filters: DefectFilters) => void
  disabled?: boolean
}

export function DefectFilter({
  filters,
  onChange,
  disabled = false,
}: DefectFilterProps) {
  // 시작 시각만으로 판정한다. 종료는 항상 "지금"이라 매 렌더 값이 달라진다.
  const activeRange = dateRangeOptions.find(
    (option) => filters.from === rangeFor(option.days).from,
  )

  const update = (partial: Partial<DefectFilters>) => {
    onChange({ ...filters, ...partial })
  }

  return (
    <form
      className="content-card defect-filter"
      aria-label="결함 필터"
      onSubmit={(event) => event.preventDefault()}
    >
      <fieldset className="defect-filter-group" disabled={disabled}>
        <legend>검색</legend>
        <input
          type="search"
          value={filters.q}
          placeholder="결함 코드·위치 검색"
          maxLength={100}
          onChange={(event) => update({ q: event.target.value })}
        />
      </fieldset>

      <fieldset className="defect-filter-group" disabled={disabled}>
        <legend>날짜 범위</legend>
        <div className="defect-filter-date-options">
          {dateRangeOptions.map(({ value, label, days }) => (
            <button
              key={value}
              type="button"
              className={`defect-filter-date-option${activeRange?.value === value ? ' is-selected' : ''}`}
              aria-pressed={activeRange?.value === value}
              onClick={() => update(rangeFor(days))}
            >
              {label}
              {activeRange?.value === value && (
                <Check size={14} aria-hidden="true" />
              )}
            </button>
          ))}
        </div>
        <label className="defect-filter-date-custom">
          <span>시작</span>
          <input
            type="datetime-local"
            value={filters.from}
            onChange={(event) => update({ from: event.target.value })}
          />
        </label>
        <label className="defect-filter-date-custom">
          <span>종료</span>
          <input
            type="datetime-local"
            value={filters.to}
            onChange={(event) => update({ to: event.target.value })}
          />
        </label>
      </fieldset>

      <fieldset className="defect-filter-group" disabled={disabled}>
        <legend>처리 상태</legend>
        {WORKFLOW_STATUSES.map((status) => (
          <label key={status} className="defect-filter-checkbox">
            <input
              type="checkbox"
              checked={filters.workflowStatuses.includes(status)}
              onChange={() =>
                update({
                  workflowStatuses: toggle<WorkflowStatus>(
                    filters.workflowStatuses,
                    status,
                  ),
                })
              }
            />
            <span
              className={`defect-status-dot defect-status-dot-${status.toLowerCase()}`}
              aria-hidden="true"
            />
            {WORKFLOW_STATUS_LABELS[status]}
          </label>
        ))}
      </fieldset>

      <fieldset className="defect-filter-group" disabled={disabled}>
        <legend>결함 유형</legend>
        {DEFECT_TYPES.map((defectType) => (
          <label key={defectType} className="defect-filter-checkbox">
            <input
              type="checkbox"
              checked={filters.defectTypes.includes(defectType)}
              onChange={() =>
                update({
                  defectTypes: toggle<DefectType>(
                    filters.defectTypes,
                    defectType,
                  ),
                })
              }
            />
            {DEFECT_TYPE_LABELS[defectType]}
          </label>
        ))}
      </fieldset>

      <fieldset className="defect-filter-group" disabled={disabled}>
        <legend>심각도</legend>
        {RISK_LEVELS.map((riskLevel) => (
          <label key={riskLevel} className="defect-filter-checkbox">
            <input
              type="checkbox"
              checked={filters.severities.includes(riskLevel)}
              onChange={() =>
                update({
                  severities: toggle<RiskLevel>(filters.severities, riskLevel),
                })
              }
            />
            {RISK_LEVEL_LABELS[riskLevel]}
          </label>
        ))}
      </fieldset>

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
