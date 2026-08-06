/**
 * 닫힌 조건 알약에 찍을 요약 문자열과, 날짜 입력의 값 변환이다.
 *
 * <p>컴포넌트 파일에서 떼어냈다. 한 파일이 컴포넌트와 순수 함수를 같이 내보내면
 * Fast Refresh가 그 파일의 상태를 보존하지 못한다(`react-refresh/only-export-components`).
 */

/**
 * `filters.from`은 서버로 나갈 때 시각까지 쓰는 datetime-local 문자열인데, 화면에서는
 * 날짜만 받는다. 그래서 앞 10자만 잘라 보여주고, 고른 날짜는 그 날 0시로 되돌린다.
 * 상한(`to`)은 받지 않는다. 결함은 미래에 생기지 않으니 늘 "지금"이다.
 */
export function toDateInputValue(from: string): string {
  return from.slice(0, 10)
}

export function fromDateInputValue(date: string): string {
  return date === '' ? '' : `${date}T00:00`
}

/**
 * 다중 선택 요약. 빈 배열은 "조건 없음", 즉 전체 조회다. 선택이 셋 이상이면 라벨을
 * 다 이어붙이면 알약이 줄을 넘치므로 개수로 줄인다.
 */
export function summarizeSelection<T extends string>(
  selected: T[],
  optionLabels: Record<T, string>,
): string {
  if (selected.length === 0) {
    return '전체'
  }
  const labels = selected.map((value) => optionLabels[value])
  return selected.length <= 2
    ? labels.join(', ')
    : `${labels[0]} 외 ${selected.length - 1}개`
}

export function summarizeConfidence(minConfidence: number | null): string {
  return minConfidence === null
    ? '전체'
    : `${Math.round(minConfidence * 100)}% 이상`
}

export function summarizeDate(from: string): string {
  // "기간 전체 기간"으로 읽히지 않게 다른 조건과 같은 말("전체")을 쓴다.
  return from.length === 0 ? '전체' : `${toDateInputValue(from)}~`
}
