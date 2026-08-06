/**
 * 화면에 보이는 모든 시각의 기준 타임존.
 *
 * <p>저장·전송은 UTC 순간(`TIMESTAMPTZ` → `Instant` → `...Z`)이고, 브라우저 로컬
 * 타임존으로 보여주고 있었다. 그래서 **보는 사람 기계 설정에 따라 같은 결함의 시각이
 * 다르게 찍혔다.** 현장·관제 화면은 한국 시간 하나로 읽혀야 하므로 표시와 입력을 모두
 * 서울로 못박는다.
 *
 * <p>서버·DB는 그대로 UTC다. 순간을 저장하는 층이라 타임존을 바꿀 이유가 없다.
 */
export const SEOUL_TIME_ZONE = 'Asia/Seoul'

/** 한국은 일광절약시간이 없어 고정 오프셋이 언제나 정확하다. */
export const SEOUL_UTC_OFFSET = '+09:00'

/**
 * 서울 기준 `Intl.DateTimeFormat`.
 *
 * <p>`new Intl.DateTimeFormat('ko-KR', …)`을 그대로 쓰면 타임존이 브라우저 설정이 된다.
 * 화면에서 시각을 만들 때는 항상 이 함수를 쓴다.
 */
export function seoulDateTimeFormat(
  options: Intl.DateTimeFormatOptions = {},
): Intl.DateTimeFormat {
  return new Intl.DateTimeFormat('ko-KR', {
    ...options,
    timeZone: SEOUL_TIME_ZONE,
  })
}

const PART_FORMAT = seoulDateTimeFormat({
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

export type SeoulParts = {
  year: number
  month: number
  day: number
  hour: number
  minute: number
  second: number
}

/**
 * 서울 기준 벽시계 값. `date.getHours()` 같은 로컬 getter를 대신한다.
 *
 * <p>직접 조립한 짧은 형식(목록 표처럼 폭이 빠듯한 곳)에서 쓴다.
 */
export function seoulParts(value: Date): SeoulParts {
  const found: Partial<Record<Intl.DateTimeFormatPartTypes, string>> = {}
  for (const part of PART_FORMAT.formatToParts(value)) {
    found[part.type] = part.value
  }
  return {
    year: Number(found.year),
    month: Number(found.month),
    day: Number(found.day),
    // 자정을 '24'로 주는 구현이 있어 24는 0으로 맞춘다.
    hour: Number(found.hour) % 24,
    minute: Number(found.minute),
    second: Number(found.second),
  }
}

/**
 * 서울 벽시계 문자열 → UTC 순간.
 *
 * <p>`<input type="date">`는 `2026-08-03`, `<input type="datetime-local">`은
 * `2026-08-03T09:30`을 준다. 둘 다 타임존이 없는 값이라 `new Date(값)`으로 파싱하면
 * 브라우저 로컬로 해석돼 조회 경계가 사람마다 달라진다. 오프셋을 붙여 못박는다.
 *
 * @returns ISO UTC 문자열. 형식이 아니면 `null`.
 */
export function seoulInputToInstant(value: string): string | null {
  const match = /^(\d{4}-\d{2}-\d{2})(?:T(\d{2}:\d{2})(?::(\d{2}))?)?$/.exec(
    value,
  )
  if (match === null) {
    return null
  }
  const [, date, time = '00:00', second = '00'] = match
  const parsed = new Date(`${date}T${time}:${second}${SEOUL_UTC_OFFSET}`)
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
}

/**
 * UTC 순간 → `<input type="datetime-local">`이 요구하는 서울 벽시계 문자열
 * (`2026-08-03T09:30`).
 */
export function instantToSeoulInput(value: Date | string): string {
  const parts = seoulParts(typeof value === 'string' ? new Date(value) : value)
  const pad = (number: number) => String(number).padStart(2, '0')
  return `${parts.year}-${pad(parts.month)}-${pad(parts.day)}`
    + `T${pad(parts.hour)}:${pad(parts.minute)}`
}

/** UTC 순간 → `<input type="date">`가 요구하는 서울 날짜(`2026-08-03`). */
export function instantToSeoulDateInput(value: Date | string): string {
  return instantToSeoulInput(value).slice(0, 10)
}
