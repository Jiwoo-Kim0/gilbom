import { useEffect, useId, useRef, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import type { ReactNode } from 'react'

interface DefectFilterDropdownProps<T extends string> {
  label: string
  options: readonly T[]
  optionLabels: Record<T, string>
  selected: T[]
  onChange: (next: T[]) => void
  disabled?: boolean
  /** 상태 점처럼 라벨 앞에 붙일 표식이 있으면 여기서 그린다. */
  renderMark?: (value: T) => ReactNode
}

/**
 * 다중 선택 조건 하나를 접어두는 드롭다운이다. 상태·유형·심각도를 모두 펼쳐 두면
 * 체크박스 14개가 사이드바를 세로로 채워 지도와 목록이 밀려나므로, 닫힌 상태에서는
 * 한 줄만 차지하고 열었을 때만 목록을 보여준다.
 *
 * 선택 자체는 부모가 URL로 관리한다. 이 컴포넌트는 열림 여부만 자기 state로 둔다.
 */
export function DefectFilterDropdown<T extends string>({
  label,
  options,
  optionLabels,
  selected,
  onChange,
  disabled = false,
  renderMark,
}: DefectFilterDropdownProps<T>) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelId = useId()

  // 첫 조회 중에는 필터 전체가 비활성이다. 그 사이 열려 있던 패널은 effect로 닫는
  // 대신 파생값으로 가려서, disabled가 풀리면 원래 상태로 돌아오게 한다.
  const expanded = open && !disabled

  // 닫혀 있을 때는 리스너를 걸지 않는다. 필터가 세 개라 항상 걸어두면 클릭마다
  // 세 번 검사하게 된다.
  useEffect(() => {
    if (!expanded) {
      return
    }

    function handlePointerDown(event: PointerEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false)
        // 포커스를 패널 안에 남기면 키보드 사용자가 어디 있는지 알 수 없다.
        triggerRef.current?.focus()
      }
    }

    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [expanded])

  const toggle = (value: T) => {
    onChange(
      selected.includes(value)
        ? selected.filter((item) => item !== value)
        : [...selected, value],
    )
  }

  // 빈 배열은 "조건 없음", 즉 전체 조회다. 닫힌 상태에서도 그 사실이 보여야 한다.
  // 선택이 셋 이상이면 라벨을 다 이어붙이면 줄이 넘치므로 개수로 줄인다.
  const selectedLabels = selected.map((value) => optionLabels[value])
  const summary = selected.length === 0
    ? '전체'
    : (selected.length <= 2
        ? selectedLabels.join(', ')
        : `${selectedLabels[0]} 외 ${selected.length - 1}개`)

  return (
    <div className="defect-filter-dropdown" ref={containerRef}>
      <span className="defect-filter-dropdown-label" id={`${panelId}-label`}>
        {label}
      </span>
      <button
        type="button"
        className={`defect-filter-dropdown-trigger${expanded ? ' is-open' : ''}`}
        ref={triggerRef}
        id={`${panelId}-trigger`}
        disabled={disabled}
        aria-expanded={expanded}
        aria-controls={panelId}
        // 버튼 안에는 "전체"처럼 값만 있어 그것만으로는 무슨 조건인지 알 수 없다.
        // 위에 있는 라벨을 함께 읽혀 "처리 상태, 전체"가 되게 한다.
        aria-labelledby={`${panelId}-label ${panelId}-trigger`}
        onClick={() => setOpen((previous) => !previous)}
      >
        <span className="defect-filter-dropdown-summary">{summary}</span>
        {selected.length > 0 && (
          <span className="defect-filter-dropdown-count">
            {selected.length}
          </span>
        )}
        <ChevronDown size={16} aria-hidden="true" />
      </button>

      {expanded && (
        <div
          className="defect-filter-dropdown-panel"
          id={panelId}
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
              className="defect-filter-dropdown-clear"
              onClick={() => onChange([])}
            >
              선택 해제
            </button>
          )}
        </div>
      )}
    </div>
  )
}
