import type { ReactNode } from 'react'

interface DefectFilterOptionsProps<T extends string> {
  /** 스크린리더가 이 묶음을 뭐라고 읽을지. 알약 라벨과 같은 말을 쓴다. */
  label: string
  options: readonly T[]
  optionLabels: Record<T, string>
  selected: T[]
  onChange: (next: T[]) => void
  /** 상태 점처럼 라벨 앞에 붙일 표식이 있으면 여기서 그린다. */
  renderMark?: (value: T) => ReactNode
  /**
   * `list`는 알약 패널 안에서 세로로 쌓고, `grid`는 상세 필터 모달에서 가로로 흘린다.
   * 고르는 방식(체크박스)은 같고 모양만 다르다 — 모달에서도 키보드·스크린리더가
   * 그대로 동작해야 해서 버튼으로 바꾸지 않았다.
   */
  variant?: 'list' | 'grid'
}

/**
 * 다중 선택 조건 하나의 선택지 목록이다. 알약 패널과 상세 필터 모달이 같은 것을 쓴다.
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
  variant = 'list',
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
      className={`defect-filter-options is-${variant}`}
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
