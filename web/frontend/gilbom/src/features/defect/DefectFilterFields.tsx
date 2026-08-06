import { useId } from 'react'
import { fromDateInputValue, toDateInputValue } from './defectDateInput'

/**
 * 필터 모달의 단일 값 조건 둘 — 시작 날짜와 최소 AI 신뢰도다. 여러 값을 고르는 조건은
 * {@link DefectFilterOptions}가 그린다.
 *
 * <p>`id`는 {@link useId}로 뽑는다. 고정 id를 쓰면 창을 닫았다 열거나 같은 조건이 두 번
 * 그려질 때 `<label for>`가 어느 입력을 가리키는지 문서 안에서 갈린다.
 */
export function DefectDateField({
  from,
  onChange,
  disabled = false,
}: {
  from: string
  onChange: (next: string) => void
  disabled?: boolean
}) {
  const fieldId = useId()

  return (
    <div className="defect-filter-group">
      <div className="defect-filter-group-heading">
        <label htmlFor={fieldId}>시작 날짜</label>
        {from.length > 0 && (
          <button
            type="button"
            className="defect-filter-clear-date"
            disabled={disabled}
            onClick={() => onChange('')}
          >
            전체 기간
          </button>
        )}
      </div>
      <input
        id={fieldId}
        type="date"
        className="defect-filter-date-input"
        value={toDateInputValue(from)}
        disabled={disabled}
        aria-describedby={`${fieldId}-hint`}
        onChange={(event) => onChange(fromDateInputValue(event.target.value))}
      />
      {/* 서버는 이 조건을 last_seen_at에 건다. "최초 발견"으로 읽으면 오해한다. */}
      <small id={`${fieldId}-hint`}>이 날짜 이후 관측된 결함</small>
    </div>
  )
}

export function DefectConfidenceField({
  minConfidence,
  onChange,
  disabled = false,
}: {
  minConfidence: number | null
  onChange: (next: number | null) => void
  disabled?: boolean
}) {
  const fieldId = useId()

  return (
    <div className="defect-filter-group">
      <label className="defect-filter-group-heading" htmlFor={fieldId}>
        최소 AI 신뢰도
        {minConfidence !== null && ` ${Math.round(minConfidence * 100)}%`}
      </label>
      <input
        id={fieldId}
        type="range"
        min={0}
        max={100}
        step={5}
        disabled={disabled}
        value={minConfidence === null ? 0 : Math.round(minConfidence * 100)}
        onChange={(event) => {
          const percent = Number(event.target.value)
          // 0%는 "제한 없음"이므로 파라미터 자체를 보내지 않는다.
          onChange(percent === 0 ? null : percent / 100)
        }}
      />
    </div>
  )
}
