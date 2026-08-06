import type { ReactNode } from 'react'

interface DefectFilterOptionsProps<T extends string> {
  /** 스크린리더가 이 묶음을 뭐라고 읽을지. 위에 붙는 제목과 같은 말을 쓴다. */
  label: string
  options: readonly T[]
  optionLabels: Record<T, string>
  selected: T[]
  onChange: (next: T[]) => void
  /** 유형 색 점처럼 라벨 앞에 붙일 표식이 있으면 여기서 그린다. */
  renderMark?: (value: T) => ReactNode
}

/**
 * 다중 선택 조건 하나의 선택지 목록이다. 가로로 흘러 누르는 칸이 넓다.
 *
 * <p>보기에는 토글 버튼이지만 요소는 `<input type="checkbox">` 그대로다. 버튼으로
 * 바꾸면 "여러 개 고를 수 있다"와 눌린 상태를 `aria-pressed`로 다시 만들어야 한다.
 * 체크박스는 그것을 공짜로 준다 — 숨긴 것은 네모 표식뿐이고 포커스는 살아 있다.
 *
 * <p>빈 배열은 "조건 없음", 즉 전체 조회다. 그래서 "선택 해제"가 곧 전체 보기다.
 */
export function DefectFilterOptions<T extends string>({
  label,
  options,
  optionLabels,
  selected,
  onChange,
  renderMark,
}: DefectFilterOptionsProps<T>) {
  const toggle = (value: T) => {
    onChange(
      selected.includes(value)
        ? selected.filter((item) => item !== value)
        : [...selected, value],
    )
  }

  return (
    <div
      className="defect-filter-options"
      role="group"
      aria-label={label}
    >
      {options.map((value) => (
        <label key={value} className="defect-filter-checkbox">
          <input
            type="checkbox"
            checked={selected.includes(value)}
            onChange={() => toggle(value)}
          />
          {renderMark?.(value)}
          {optionLabels[value]}
        </label>
      ))}
      {selected.length > 0 && (
        <button
          type="button"
          className="defect-filter-options-clear"
          onClick={() => onChange([])}
        >
          선택 해제
        </button>
      )}
    </div>
  )
}
