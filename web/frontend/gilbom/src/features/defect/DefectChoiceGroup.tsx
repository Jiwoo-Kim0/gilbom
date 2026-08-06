import { useId } from 'react'
import type { ReactNode } from 'react'

interface DefectChoiceGroupProps<T extends string> {
  /** 스크린리더가 이 묶음을 뭐라고 읽을지. 위에 붙는 제목과 같은 말을 쓴다. */
  label: string
  options: readonly T[]
  optionLabels: Record<T, string>
  value: T
  onChange: (next: T) => void
  disabled?: boolean
  /** 색 점처럼 라벨 앞에 붙일 표식이 있으면 여기서 그린다. */
  renderMark?: (value: T) => ReactNode
  /** 고른 칸에 값별 색을 입힐 때 쓴다. 심각도가 배지와 같은 색을 갖는다. */
  optionClassName?: (value: T) => string
}

/**
 * 하나만 고르는 값(결함 유형·심각도)을 알약 줄로 그린다.
 *
 * <p>`<select>`를 걷어낸 자리다. 네이티브 select는 브라우저 기본 모양이 그대로 나와
 * 이 화면의 다른 것들과 결이 맞지 않았고, 무엇을 고를 수 있는지 열어봐야 알 수 있었다.
 * 선택지가 넷뿐이라 다 펼쳐 두는 것이 낫다.
 *
 * <p>요소는 `<input type="radio">` 그대로다. 버튼으로 바꾸면 "하나만 고른다"와 방향키
 * 이동을 직접 만들어야 한다. 라디오는 그것을 공짜로 준다 — 숨긴 것은 동그라미 표식뿐이고
 * 포커스는 살아 있다. 필터 모달의 다중 선택 알약과 같은 CSS를 쓴다.
 */
export function DefectChoiceGroup<T extends string>({
  label,
  options,
  optionLabels,
  value,
  onChange,
  disabled = false,
  renderMark,
  optionClassName,
}: DefectChoiceGroupProps<T>) {
  // 같은 화면에 묶음이 둘 있어도 라디오가 서로 섞이지 않게 이름을 나눈다.
  const name = useId()

  return (
    <div className="defect-choice-group" role="radiogroup" aria-label={label}>
      {options.map((option) => (
        <label
          key={option}
          className={`defect-choice${
            optionClassName ? ` ${optionClassName(option)}` : ''
          }`}
        >
          <input
            type="radio"
            name={name}
            value={option}
            checked={option === value}
            disabled={disabled}
            onChange={() => onChange(option)}
          />
          {renderMark?.(option)}
          {optionLabels[option]}
        </label>
      ))}
    </div>
  )
}
