import { useState } from 'react'
import { Check } from 'lucide-react'

type DateRangeOption = 'today' | '7d' | '30d' | 'custom'

interface StatusFilters {
  inProgress: boolean
  received: boolean
  done: boolean
}

const dateRangeOptions: { value: DateRangeOption; label: string }[] = [
  { value: 'today', label: '오늘' },
  { value: '7d', label: '지난 7일' },
  { value: '30d', label: '지난 30일' },
]

const defaultStatusFilters: StatusFilters = {
  inProgress: true,
  received: true,
  done: true,
}

export function DefectFilter() {
  const [dateRange, setDateRange] = useState<DateRangeOption>('today')
  const [customDate, setCustomDate] = useState('')
  const [status, setStatus] = useState<StatusFilters>(defaultStatusFilters)

  const handleReset = () => {
    setDateRange('today')
    setCustomDate('')
    setStatus(defaultStatusFilters)
  }

  return (
    <form
      className="content-card defect-filter"
      aria-label="결함 필터"
      onSubmit={(event) => event.preventDefault()}
    >
      <fieldset className="defect-filter-group">
        <legend>날짜 범위</legend>
        <div className="defect-filter-date-options">
          {dateRangeOptions.map(({ value, label }) => (
            <button
              key={value}
              type="button"
              className={`defect-filter-date-option${dateRange === value ? ' is-selected' : ''}`}
              aria-pressed={dateRange === value}
              onClick={() => {
                setDateRange(value)
                setCustomDate('')
              }}
            >
              {label}
              {dateRange === value && <Check size={14} aria-hidden="true" />}
            </button>
          ))}
        </div>
        <label className="defect-filter-date-custom">
          <span>직접 선택</span>
          <input
            type="date"
            value={customDate}
            onChange={(event) => {
              setCustomDate(event.target.value)
              setDateRange('custom')
            }}
          />
        </label>
      </fieldset>

      <fieldset className="defect-filter-group">
        <legend>상태 필터</legend>
        <label className="defect-filter-checkbox">
          <input
            type="checkbox"
            checked={status.inProgress}
            onChange={(event) =>
              setStatus((prev) => ({ ...prev, inProgress: event.target.checked }))
            }
          />
          <span className="defect-status-dot defect-status-dot-progress" aria-hidden="true" />
          진행 중 (Gold)
        </label>
        <label className="defect-filter-checkbox">
          <input
            type="checkbox"
            checked={status.received}
            onChange={(event) =>
              setStatus((prev) => ({ ...prev, received: event.target.checked }))
            }
          />
          <span className="defect-status-dot defect-status-dot-received" aria-hidden="true" />
          접수됨 (Gray)
        </label>
        <label className="defect-filter-checkbox">
          <input
            type="checkbox"
            checked={status.done}
            onChange={(event) => setStatus((prev) => ({ ...prev, done: event.target.checked }))}
          />
          <span className="defect-status-dot defect-status-dot-done" aria-hidden="true" />
          완료 (Amber)
        </label>
      </fieldset>

      <button className="secondary-button defect-filter-reset" type="button" onClick={handleReset}>
        필터 초기화
      </button>
    </form>
  )
}
