import {
  createElement,
  type ReactElement,
} from 'react'

type SafeReportDocumentProps = {
  text: string
  documentTitle?: string
  hiddenSectionTitles?: readonly string[]
}

type HeadingElement = 'h4' | 'h5' | 'h6'

const HEADING_PATTERN = /^(#{1,3})[ \t]+(.+?)\s*$/
const LIST_ITEM_PATTERN = /^-[ \t]+(.+?)\s*$/
const KEY_DEFECT_FIELD_PATTERN =
  /^(?:결함\s*ID|사건\s*코드|결함\s*유형|위험도|처리\s*상태)\s*:/
const ACTION_PRIORITY_PATTERN =
  /^\[조치\s*우선순위\s*:\s*([^\]]+)\]\s*/
const ACTION_FIELD_PATTERN = /^(?:담당|조치|관련\s*결함(?:\s*ID)?)\s*:/

/**
 * 서버가 만드는 보고서 형식 중 제목과 순서 없는 목록만 React 요소로 바꾼다.
 *
 * HTML, 링크, 이미지, 코드와 인라인 Markdown은 해석하지 않는다. 모든 실제 내용은
 * 계속 React text child로 전달되므로 Provider나 사용자가 넣은 태그는 실행되지 않는다.
 */
export function SafeReportDocument({
  text,
  documentTitle,
  hiddenSectionTitles = [],
}: SafeReportDocumentProps): ReactElement {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  const hiddenSections = new Set(
    hiddenSectionTitles.map((title) => title.trim()),
  )
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
  let currentSection = ''

  while (lineIndex < lines.length) {
    const line = lines[lineIndex] ?? ''
    if (line.trim().length === 0) {
      lineIndex += 1
      continue
    }

    const heading = line.match(HEADING_PATTERN)
    if (heading !== null) {
      const markdownLevel = heading[1]?.length ?? 1
      const headingTitle = heading[2]?.trim() ?? ''
      if (hiddenSections.has(headingTitle)) {
        lineIndex += 1
        while (lineIndex < lines.length) {
          const nextHeading = (lines[lineIndex] ?? '').match(HEADING_PATTERN)
          if (nextHeading !== null
              && (nextHeading[1]?.length ?? 1) <= markdownLevel) {
            break
          }
          lineIndex += 1
        }
        continue
      }

      const isDuplicateTitle = hidesDuplicateTitle
        && lineIndex === firstContentIndex
      if (!isDuplicateTitle) {
        const displayLevel = hidesDuplicateTitle
          ? Math.max(1, markdownLevel - 1)
          : markdownLevel
        children.push(createElement(
          headingElement(displayLevel),
          { key: `heading-${blockIndex}` },
          headingTitle,
        ))
        blockIndex += 1
      }
      if (markdownLevel <= 2) {
        currentSection = headingTitle
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
          reportListItem(
            current[1]?.trim() ?? '',
            currentSection,
            lineIndex,
          ),
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

function reportListItem(
  text: string,
  sectionTitle: string,
  itemIndex: number,
): string | ReactElement {
  if (sectionTitle === '주요 결함') {
    return keyDefectListItem(text, itemIndex)
  }
  if (sectionTitle === '조치 권고') {
    return actionRecommendationListItem(text, itemIndex)
  }
  return text
}

function keyDefectListItem(
  text: string,
  itemIndex: number,
): string | ReactElement {
  const segments = text
    .split(/\s+(?:·|—|\||--)\s+/)
    .map((segment) => segment.trim())
    .filter(Boolean)
  if (segments.length < 2
      || !segments.some((segment) => KEY_DEFECT_FIELD_PATTERN.test(segment))) {
    return text
  }

  return structuredItem(
    segments.map((segment) => ({
      text: segment,
      kind: KEY_DEFECT_FIELD_PATTERN.test(segment) ? 'field' : 'body',
    })),
    itemIndex,
  )
}

function actionRecommendationListItem(
  text: string,
  itemIndex: number,
): string | ReactElement {
  const priority = text.match(ACTION_PRIORITY_PATTERN)
  const looksStructured = priority !== null
    || /\s+(?:—|\||--)\s+/.test(text)
    || ACTION_FIELD_PATTERN.test(text)
  if (!looksStructured) {
    return text
  }

  const lines: StructuredLine[] = []
  let remainder = text
  if (priority !== null) {
    lines.push({
      text: `조치 우선순위: ${priority[1]?.trim() ?? ''}`,
      kind: 'field',
    })
    remainder = remainder.slice(priority[0].length)
  }

  const sections = remainder
    .split(/\s+(?:—|--)\s+/)
    .map((section) => section.trim())
    .filter(Boolean)
  const recommendation = sections.shift()
  if (recommendation !== undefined) {
    const recommendationLines = recommendation
      .split(/\s*\|\s*/)
      .map((line) => line.trim())
      .filter(Boolean)
    recommendationLines.forEach((line, index) => {
      const hasFieldLabel = ACTION_FIELD_PATTERN.test(line)
      lines.push({
        text: !hasFieldLabel && index === 0
          ? `조치: ${line}`
          : normalizeRelatedDefectLabel(line),
        kind: 'field',
      })
    })
  }

  sections.forEach((section) => {
    const isField = ACTION_FIELD_PATTERN.test(section)
    lines.push({
      text: isField ? normalizeRelatedDefectLabel(section) : section,
      kind: isField ? 'field' : 'body',
    })
  })

  return lines.length > 1 ? structuredItem(lines, itemIndex) : text
}

type StructuredLine = {
  text: string
  kind: 'field' | 'body'
}

function normalizeRelatedDefectLabel(text: string): string {
  return text.replace(/^관련\s*결함(?:\s*ID)?\s*:/, '관련 결함:')
}

function structuredItem(
  lines: readonly StructuredLine[],
  itemIndex: number,
): ReactElement {
  return createElement(
    'span',
    { className: 'safe-report-structured-item' },
    ...lines.map((line, lineIndex) => createElement(
      'span',
      {
        className: line.kind === 'field'
          ? 'safe-report-structured-field'
          : 'safe-report-structured-body',
        key: `structured-${itemIndex}-${lineIndex}`,
      },
      line.text,
    )),
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
