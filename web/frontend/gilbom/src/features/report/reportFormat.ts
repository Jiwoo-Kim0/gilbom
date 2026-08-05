export function formatReportDateTime(value: string | null): string {
  if (value === null) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date)
}

export function formatReportPeriod(from: string, to: string): string {
  const formatter = new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
  return `${formatter.format(new Date(from))} – ${formatter.format(new Date(to))}`
}

function toReportPeriodBoundary(
  value: string,
  endOfDay: boolean,
): string | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (match === null) {
    return undefined
  }

  const year = Number(match[1])
  const monthIndex = Number(match[2]) - 1
  const day = Number(match[3])
  const date = new Date(
    year,
    monthIndex,
    day,
    endOfDay ? 23 : 0,
    endOfDay ? 59 : 0,
    endOfDay ? 59 : 0,
    endOfDay ? 999 : 0,
  )
  if (
    date.getFullYear() !== year
    || date.getMonth() !== monthIndex
    || date.getDate() !== day
  ) {
    return undefined
  }
  return date.toISOString()
}

export function toReportPeriodStartInstant(value: string): string | undefined {
  return toReportPeriodBoundary(value, false)
}

export function toReportPeriodEndInstant(value: string): string | undefined {
  return toReportPeriodBoundary(value, true)
}

const INCIDENT_CODE_PATTERN = /\bINC-[A-Z0-9]+(?:-[A-Z0-9]+)+\b/
const LEGACY_DEFECT_IDENTIFIER_PATTERN = new RegExp(
  [
    '결함 ID:[ \\t]*',
    '([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})',
    '[ \\t]*·[ \\t]*사건 코드:[ \\t]*',
    '(INC-[A-Z0-9]+(?:-[A-Z0-9]+)+)',
  ].join(''),
  'gi',
)

/**
 * 구형 LLM 보고서가 내부 UUID를 "결함 ID"로 노출한 표기를 사용자용 사건 코드로
 * 바꾼다. UUID와 사건 코드의 쌍이 본문에서 확인된 경우에만 치환한다.
 */
export function formatReportContentIdentifiers(content: string): string {
  const incidentCodeByDefectId = new Map<string, string>()
  const normalized = content.replace(
    LEGACY_DEFECT_IDENTIFIER_PATTERN,
    (_match, defectId: string, incidentCode: string) => {
      incidentCodeByDefectId.set(defectId.toLowerCase(), incidentCode)
      return `결함 ID: ${incidentCode}`
    },
  )

  let formatted = normalized
  for (const [defectId, incidentCode] of incidentCodeByDefectId) {
    formatted = formatted.replace(
      new RegExp(defectId, 'gi'),
      incidentCode,
    )
  }
  return formatted
}

/**
 * 단건 결함 보고서는 모델이 만든 긴 제목을 화면 제목으로 사용하지 않는다.
 *
 * 기존 보고서도 바로 짧게 보이도록 저장 제목과 검증된 보고서 본문에서 결함 코드를
 * 찾고, 찾지 못하면 서버 보고서 코드를 안전한 fallback으로 사용한다.
 */
export function formatReportDisplayTitle(
  storedTitle: string,
  content: string,
  reportCode: string,
  versionNo: number,
  isSingleDefectReport: boolean,
): string {
  if (!isSingleDefectReport) {
    return `${storedTitle} v${versionNo}`
  }

  const incidentCode = (
    storedTitle.match(INCIDENT_CODE_PATTERN)
    ?? content.match(INCIDENT_CODE_PATTERN)
  )?.[0] ?? reportCode

  return `${incidentCode} 보고서 v${versionNo}`
}

export function actorLabel(
  actorId: string | null,
  currentUserId: string | null,
  currentDisplayName: string | null,
): string {
  if (actorId === null) {
    return '시스템'
  }
  if (actorId === currentUserId && currentDisplayName !== null) {
    return `${currentDisplayName} (나)`
  }
  return actorId
}

export function toDateTimeLocal(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}
