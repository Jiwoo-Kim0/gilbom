import {
  createElement,
  type ReactElement,
} from 'react'

type SafeTextElement = 'div' | 'p' | 'span'

export type SafeReportTextProps = {
  text: string
  as?: SafeTextElement
  className?: string
  id?: string
}

/**
 * 보고서 본문과 LLM 결과를 React text node로만 렌더링한다. HTML, Markdown,
 * 링크를 해석하지 않으므로 태그와 javascript: 문자열도 화면의 일반 텍스트다.
 */
export function SafeReportText({
  text,
  as = 'div',
  className,
  id,
}: SafeReportTextProps): ReactElement {
  const combinedClassName = className === undefined
    ? 'safe-report-text'
    : `safe-report-text ${className}`

  return createElement(as, {
    className: combinedClassName,
    id,
  }, text)
}
