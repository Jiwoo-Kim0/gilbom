import { useEffect, useId, useRef, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import type { ReactNode } from 'react'

interface DefectFilterChipProps {
  /** 무슨 조건인지. 알약 안에 그대로 찍히고 스크린리더도 이것을 먼저 읽는다. */
  label: string
  /** 지금 걸린 값 요약. 조건이 없으면 "전체"처럼 그 사실이 보이는 말이어야 한다. */
  summary: string
  /** 기본값이 아닌 조건이 걸렸는가. 알약 색으로 드러낸다. */
  active?: boolean
  /** 다중 선택의 개수. 0이면 배지를 그리지 않는다. */
  count?: number
  disabled?: boolean
  children: ReactNode
}

/**
 * 검색줄 오른쪽에 늘어서는 조건 알약 하나다. 닫혀 있을 때는 `label`과 `summary`만
 * 차지하고, 누르면 그 자리에 패널이 얹힌다.
 *
 * <p>조건 패널이 지도 옆 사이드바이던 시절에는 세로로 쌓인 드롭다운이었다. 조건이
 * 위로 올라오면서 가로 한 줄에 다섯 개가 들어가야 해 라벨을 버튼 안으로 넣었다.
 *
 * <p>열림 여부만 자기 state다. 고른 값은 부모가 URL로 관리한다.
 */
export function DefectFilterChip({
  label,
  summary,
  active = false,
  count = 0,
  disabled = false,
  children,
}: DefectFilterChipProps) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelId = useId()

  // 첫 조회 중에는 조건 전체가 비활성이다. 그 사이 열려 있던 패널은 effect로 닫는
  // 대신 파생값으로 가려서, disabled가 풀리면 원래 상태로 돌아오게 한다.
  const expanded = open && !disabled

  // 닫혀 있을 때는 리스너를 걸지 않는다. 알약이 다섯 개라 항상 걸어두면 클릭마다
  // 다섯 번 검사하게 된다.
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

  return (
    <div className="defect-filter-chip" ref={containerRef}>
      <button
        type="button"
        className={
          'defect-filter-chip-trigger'
          + (active ? ' is-active' : '')
          + (expanded ? ' is-open' : '')
        }
        ref={triggerRef}
        id={panelId}
        disabled={disabled}
        aria-expanded={expanded}
        aria-controls={`${panelId}-panel`}
        onClick={() => setOpen((previous) => !previous)}
      >
        <span className="defect-filter-chip-label">{label}</span>
        <span className="defect-filter-chip-value">{summary}</span>
        {count > 0 && (
          <span className="defect-filter-chip-count">{count}</span>
        )}
        <ChevronDown size={15} aria-hidden="true" />
      </button>

      {expanded && (
        <div
          className="defect-filter-chip-panel"
          id={`${panelId}-panel`}
          // 패널 안을 눌러 조건을 바꿔도 알약은 열린 채 남는다. 여러 개를 연달아
          // 고르는 동안 패널이 닫히면 그때마다 다시 열어야 한다.
        >
          {children}
        </div>
      )}
    </div>
  )
}
