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
