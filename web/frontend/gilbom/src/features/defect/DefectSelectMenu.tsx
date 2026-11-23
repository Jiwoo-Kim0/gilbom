import { useEffect, useId, useRef, useState } from 'react'
import { Check, ChevronDown } from 'lucide-react'
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react'

export interface DefectSelectOption {
  value: string
  label: string
}

interface DefectSelectMenuProps {
  /** 스크린리더가 읽을 이름. "정렬 방식", "처리 상태 변경"처럼 무엇을 고르는지. */
  label: string
  options: readonly DefectSelectOption[]
  value: string
  onChange: (value: string) => void
  disabled?: boolean
  /** 여는 버튼 왼쪽 아이콘. */
  icon?: ReactNode
  /**
   * `pill`은 카드 머리줄에 놓는 좁은 알약, `block`은 폼 칸처럼 가로를 다 쓰는 모양이다.
   */
  variant?: 'pill' | 'block'
  /** 목록을 버튼의 어느 쪽 끝에 맞출지. 오른쪽 끝에 놓인 버튼은 `end`. */
  align?: 'start' | 'end'
  /** 값이 비었을 때 버튼에 흐리게 보일 말. */
  placeholder?: string
}

/** 목록 한 칸 높이 + 안쪽 여백. 위로 뒤집을지 정할 때 쓰는 어림값이다. */
const OPTION_HEIGHT_PX = 37
const PANEL_PADDING_PX = 12
const PANEL_MAX_HEIGHT_PX = 240

/**
 * 위아래 어느 쪽에 얼마나 자리가 있는지. 화면뿐 아니라 **스크롤하는 조상**(상세 카드가
 * 그렇다)까지 본다 — 카드 안에서 아래로 펼치면 카드 밖이라 잘린다.
 */
function spaceAround(trigger: HTMLElement): { above: number; below: number } {
  let top = 0
  let bottom = window.innerHeight
  for (
    let node = trigger.parentElement;
    node !== null;
    node = node.parentElement
  ) {
    const overflowY = getComputedStyle(node).overflowY
    if (overflowY === 'auto' || overflowY === 'scroll') {
      const rect = node.getBoundingClientRect()
      top = Math.max(top, rect.top)
      bottom = Math.min(bottom, rect.bottom)
      break
    }
  }
  const rect = trigger.getBoundingClientRect()
  return { above: rect.top - top, below: bottom - rect.bottom }
}

/**
 * 값 하나를 고르는 목록. 네이티브 `<select>`를 대신한다.
 *
 * <p>운영체제 기본 `<select>`는 이 화면의 다른 것들과 결이 맞지 않고, 지금 고른 값이
 * 작은 글씨로만 보였다. 대신 직접 그리면 `<select>`가 공짜로 주던 것을 다시 만들어야
 * 하므로 아래를 갖췄다.
 *
 * <ul>
 *   <li>`role="listbox"`/`role="option"` + `aria-selected`
 *   <li>포커스는 **여는 버튼에 그대로 두고** `aria-activedescendant`로 짚은 항목을 가리킨다.
 *       목록으로 포커스를 옮기면 닫을 때 되돌리는 처리가 지저분해진다
 *   <li>↑↓·Home·End 이동, Enter·Space 고름, Esc 닫기, Tab이면 닫고 흘려보냄
 *   <li>바깥을 누르면 닫힘, 아래에 자리가 모자라면 위로 뒤집음
 * </ul>
 */
export function DefectSelectMenu({
  label,
  options,
  value,
  onChange,
  disabled = false,
  icon,
  variant = 'pill',
  align = 'start',
  placeholder,
}: DefectSelectMenuProps) {
  const [open, setOpen] = useState(false)
  /** 키보드로 짚고 있는 항목. 열 때는 지금 고른 것에서 시작한다. */
  const [activeIndex, setActiveIndex] = useState(0)
  const [dropUp, setDropUp] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const listId = useId()

  const selectedIndex = options.findIndex((option) => option.value === value)
  const selected = selectedIndex >= 0 ? options[selectedIndex] : undefined
  // 첫 조회 중에는 폼 전체가 비활성이다. 그 사이 열려 있던 것은 파생값으로 가린다.
  const expanded = open && !disabled

  useEffect(() => {
    if (!expanded) {
      return
    }

    function handlePointerDown(event: PointerEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }

    document.addEventListener('pointerdown', handlePointerDown)
    return () => document.removeEventListener('pointerdown', handlePointerDown)
  }, [expanded])

  function openAt(index: number) {
    const trigger = triggerRef.current
    if (trigger !== null) {
      const wanted = Math.min(
        options.length * OPTION_HEIGHT_PX + PANEL_PADDING_PX,
        PANEL_MAX_HEIGHT_PX,
      )
      const { above, below } = spaceAround(trigger)
      // 아래가 모자라고 위가 더 넓을 때만 뒤집는다. 둘 다 모자라면 아래로 두고 줄인다.
      setDropUp(below < wanted && above > below)
    }
    setActiveIndex(Math.max(index, 0))
    setOpen(true)
  }

  function commit(index: number) {
    setOpen(false)
    triggerRef.current?.focus()
    const option = options[index]
    // 같은 값을 다시 고르면 조회를 새로 낼 이유가 없다.
    if (option !== undefined && option.value !== value) {
      onChange(option.value)
    }
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLButtonElement>) {
    if (!expanded) {
      if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
        event.preventDefault()
        openAt(selectedIndex)
      }
      return
    }

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        setActiveIndex((index) => Math.min(index + 1, options.length - 1))
        break
      case 'ArrowUp':
        event.preventDefault()
        setActiveIndex((index) => Math.max(index - 1, 0))
        break
      case 'Home':
        event.preventDefault()
        setActiveIndex(0)
        break
      case 'End':
        event.preventDefault()
        setActiveIndex(options.length - 1)
        break
      case 'Enter':
      case ' ':
        event.preventDefault()
        commit(activeIndex)
        break
      case 'Escape':
        event.preventDefault()
        setOpen(false)
        break
      case 'Tab':
        // 막지 않는다. 닫고 다음 요소로 보낸다.
        setOpen(false)
        break
      default:
        break
    }
  }

  const shownLabel = selected?.label ?? placeholder ?? ''

  return (
    <div className="defect-select" ref={containerRef}>
      <button
        type="button"
        className={
          `defect-select-trigger is-${variant}`
          + (expanded ? ' is-open' : '')
          + (selected === undefined ? ' is-placeholder' : '')
        }
        ref={triggerRef}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={expanded}
        aria-controls={listId}
        aria-label={`${label}, 지금 ${shownLabel}`}
        aria-activedescendant={
          expanded ? `${listId}-${activeIndex}` : undefined
        }
        onClick={() => (expanded ? setOpen(false) : openAt(selectedIndex))}
        onKeyDown={handleKeyDown}
      >
        {icon}
        <span className="defect-select-value">{shownLabel}</span>
        <ChevronDown size={15} aria-hidden="true" />
      </button>

      {expanded && (
        <ul
          className={
            `defect-select-panel align-${align}`
            + (dropUp ? ' is-above' : '')
          }
          id={listId}
          role="listbox"
          aria-label={label}
        >
          {options.map((option, index) => (
            <li
              key={option.value}
              id={`${listId}-${index}`}
              role="option"
              aria-selected={option.value === value}
              className={
                'defect-select-option'
                + (index === activeIndex ? ' is-active' : '')
                + (option.value === value ? ' is-selected' : '')
              }
              // 마우스로 훑을 때도 짚은 자리가 따라온다.
              onPointerEnter={() => setActiveIndex(index)}
              onClick={() => commit(index)}
            >
              <Check
                size={14}
                aria-hidden="true"
                className="defect-select-check"
              />
              {option.label}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
