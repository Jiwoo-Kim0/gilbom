/**
 * 증거 사진 확대 배율.
 *
 * <p>지도처럼 자유 배율이 아니라 단계로 두는 이유는 CSP다. `style-src 'self'`가 inline
 * style을 막아 `transform: scale(1.37)`처럼 계산된 값을 넣을 수 없다(그 회귀는
 * `scripts/verify-frontend-csp-source.sh`가 소스 단계에서 잡는다). 그래서 배율을 CSS
 * class로 표현하고, 클래스로 만들 수 있는 단계만 쓴다.
 *
 * <p>처음에는 ×1 → ×1.5 → ×2로 뛰었는데 한 번 누를 때 너무 많이 확대됐다. 사진에서
 * 파손 부위를 찾는 동작은 조금씩 키우며 맞추는 일이라 간격을 20%p로 좁혔다.
 * 단계마다 `App.css`에 짝이 되는 클래스가 있어야 한다.
 */
export const PHOTO_ZOOM_STEPS = [
  100, 120, 140, 160, 180, 200,
  // 크게 확대한 뒤에는 한 단계의 체감이 작아진다. 위쪽만 간격을 넓혀 ×4까지 가는
  // 클릭 수를 줄인다(전 구간을 20%p로 두면 열여섯 번을 눌러야 한다).
  240, 280, 320, 360, 400,
] as const

export type PhotoZoom = (typeof PHOTO_ZOOM_STEPS)[number]

export const MIN_PHOTO_ZOOM: PhotoZoom = PHOTO_ZOOM_STEPS[0]
export const MAX_PHOTO_ZOOM: PhotoZoom =
  PHOTO_ZOOM_STEPS[PHOTO_ZOOM_STEPS.length - 1]

/**
 * 한 단계 확대(`1`)하거나 축소(`-1`)한다. 양 끝에서는 그대로 둔다 — 버튼을 눌렀는데
 * 아무 일도 안 일어나는 것이 배율이 한 바퀴 도는 것보다 낫다.
 */
export function nextPhotoZoom(current: PhotoZoom, direction: 1 | -1): PhotoZoom {
  const index = PHOTO_ZOOM_STEPS.indexOf(current)
  // 모르는 값이 들어오면 원본 크기로 되돌린다.
  if (index < 0) {
    return MIN_PHOTO_ZOOM
  }
  const next = index + direction
  if (next < 0 || next >= PHOTO_ZOOM_STEPS.length) {
    return current
  }
  return PHOTO_ZOOM_STEPS[next]
}

/** App.css의 `.defect-photo-zoom-*`와 짝이다. */
export function photoZoomClass(zoom: PhotoZoom): string {
  return `defect-photo-zoom-${zoom}`
}

/**
 * 표시용 배율. 기준(100)이 "사진 전체가 보이는 크기"라 원본 픽셀의 100%가 아니다.
 * 그래서 퍼센트가 아니라 기준 대비 배수로 적는다.
 */
export function photoZoomLabel(zoom: PhotoZoom): string {
  return `×${zoom / 100}`
}

/**
 * 확대·축소 뒤의 스크롤 위치.
 *
 * <p>기준점(`anchor`)이 화면에서 같은 자리에 남게 한다. 그냥 배율만 키우면 스크롤이 0에
 * 머물러 늘 왼쪽 위로만 확대된다.
 *
 * @param scroll 바뀌기 전 스크롤 위치
 * @param anchor 보이는 영역 안에서의 기준점(가운데 확대는 폭/2, 휠은 커서 위치)
 * @param ratio 새 배율 ÷ 이전 배율
 */
export function anchoredScroll(
  scroll: number,
  anchor: number,
  ratio: number,
): number {
  // 음수로 내려가면 브라우저가 어차피 0으로 자른다. 계산에서 미리 자른다.
  return Math.max(0, (scroll + anchor) * ratio - anchor)
}

export function canZoomIn(zoom: PhotoZoom): boolean {
  return zoom !== MAX_PHOTO_ZOOM
}

export function canZoomOut(zoom: PhotoZoom): boolean {
  return zoom !== MIN_PHOTO_ZOOM
}
