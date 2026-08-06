/**
 * 날짜 조건의 화면 값 ↔ 필터 값 변환이다. 컴포넌트 파일에서 떼어냈다 — 한 파일이
 * 컴포넌트와 순수 함수를 같이 내보내면 Fast Refresh가 그 파일의 상태를 보존하지 못한다
 * (`react-refresh/only-export-components`).
 *
 * <p>`filters.from`은 서버로 나갈 때 시각까지 쓰는 datetime-local 문자열인데, 화면에서는
 * 날짜만 받는다. 그래서 앞 10자만 잘라 보여주고, 고른 날짜는 그 날 0시로 되돌린다.
 * 상한(`to`)은 받지 않는다. 결함은 미래에 생기지 않으니 늘 "지금"이다.
 */
export function toDateInputValue(from: string): string {
  return from.slice(0, 10)
}

export function fromDateInputValue(date: string): string {
  return date === '' ? '' : `${date}T00:00`
}
