import {
  createElement,
  type ReactElement,
} from 'react'

type SafeReportDocumentProps = {
  text: string
  documentTitle?: string
}

type HeadingElement = 'h4' | 'h5' | 'h6'

const HEADING_PATTERN = /^(#{1,3})[ \t]+(.+?)\s*$/
const LIST_ITEM_PATTERN = /^-[ \t]+(.+?)\s*$/

/**
 * 서버가 만드는 보고서 형식 중 제목과 순서 없는 목록만 React 요소로 바꾼다.
 *
 * HTML, 링크, 이미지, 코드와 인라인 Markdown은 해석하지 않는다. 모든 실제 내용은
 * 계속 React text child로 전달되므로 Provider나 사용자가 넣은 태그는 실행되지 않는다.
 */
export function SafeReportDocument({
  text,
  documentTitle,
}: SafeReportDocumentProps): ReactElement {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  const firstContentIndex = lines.findIndex((line) => line.trim().length > 0)
  const firstHeading = firstContentIndex < 0
    ? null
    : lines[firstContentIndex]?.match(HEADING_PATTERN) ?? null
  const hidesDuplicateTitle = firstHeading?.[1] === '#'
    && documentTitle !== undefined
    && firstHeading[2]?.trim() === documentTitle.trim()

  const children: ReactElement[] = []
  let blockIndex = 0
  let lineIndex = 0

  while (lineIndex < lines.length) {
    const line = lines[lineIndex] ?? ''
    if (line.trim().length === 0) {
      lineIndex += 1
      continue
    }

    const heading = line.match(HEADING_PATTERN)
    if (heading !== null) {
      const isDuplicateTitle = hidesDuplicateTitle
        && lineIndex === firstContentIndex
      if (!isDuplicateTitle) {
        const markdownLevel = heading[1]?.length ?? 1
        const displayLevel = hidesDuplicateTitle
          ? Math.max(1, markdownLevel - 1)
          : markdownLevel
        children.push(createElement(
          headingElement(displayLevel),
          { key: `heading-${blockIndex}` },
          heading[2]?.trim() ?? '',
        ))
        blockIndex += 1
      }
      lineIndex += 1
      continue
    }

    const listItem = line.match(LIST_ITEM_PATTERN)
    if (listItem !== null) {
      const items: ReactElement[] = []
      while (lineIndex < lines.length) {
        const current = (lines[lineIndex] ?? '').match(LIST_ITEM_PATTERN)
        if (current === null) {
          break
        }
        items.push(createElement(
          'li',
          { key: `item-${lineIndex}` },
          current[1]?.trim() ?? '',
        ))
        lineIndex += 1
      }
      children.push(createElement(
        'ul',
        { key: `list-${blockIndex}` },
        ...items,
      ))
      blockIndex += 1
      continue
    }

    const paragraphLines: string[] = []
    while (lineIndex < lines.length) {
      const current = lines[lineIndex] ?? ''
      if (current.trim().length === 0
          || HEADING_PATTERN.test(current)
          || LIST_ITEM_PATTERN.test(current)) {
        break
      }
      paragraphLines.push(current)
      lineIndex += 1
    }
    children.push(createElement(
      'p',
      { key: `paragraph-${blockIndex}` },
      paragraphLines.join('\n').trim(),
    ))
    blockIndex += 1
  }

  return createElement(
    'div',
    { className: 'safe-report-document' },
    ...children,
  )
}

function headingElement(markdownLevel: number): HeadingElement {
  if (markdownLevel <= 1) {
    return 'h4'
  }
  if (markdownLevel === 2) {
    return 'h5'
  }
  return 'h6'
}
