/**
 * 지도 위 상세 카드를 끌어 옮기는 계산.
 *
 * <p>**자유 좌표가 아니라 정수 백분율**인 이유는 CSP다. `style-src 'self'`가 inline
 * style을 막아 `left: 337px`처럼 계산된 값을 넣을 수 없다(그 회귀는
 * `scripts/verify-frontend-csp-source.sh`가 소스 단계에서 잡는다). 그래서 위치를 CSS
 * class로 표현한다 — 사진 확대가 배율을 클래스로 두는 것과 같은 이유다.
 *
 * <p>클래스 수를 줄이려고 **10%p 자리와 1%p 자리를 따로** 둔다. 축마다 11 + 10개면
 * 0~100%를 1%p 간격으로 덮는다(둘을 곱해 101개를 만들지 않는다). `App.css`의
 * `--defect-card-*` 변수를 각각 정해 `calc()`로 합친다.
 *
 * <p>1%p는 지도 폭 1000px에서 10px, 높이 600px에서 6px이다. 끌 때 눈에 걸리지 않는다.
 */

/** 컨테이너 모서리에서 이만큼은 남긴다. 카드가 모서리에 붙으면 답답해 보인다. */
export const CARD_DRAG_MARGIN_PX = 16

/**
 * 카드가 컨테이너보다 클 때 최소한 이만큼은 안에 남긴다(≈헤더 한 줄).
 *
 * <p>기본 카드는 지도 높이를 거의 다 쓴다. "다 들어가는 자리"만 허용하면 **위아래로는
 * 1px도 못 움직인다.** 아래로 끌면 App.css의 `max-height: calc(100% - top)`이 카드를
 * 남은 공간만큼 줄이므로 잘려나가지도 않는다 — 다만 손잡이까지 사라지면 되돌릴 길이
 * 없으니 그 한 줄은 남긴다.
 */
export const CARD_DRAG_MIN_VISIBLE_PX = 96

/** 컨테이너 폭·높이에 대한 정수 백분율. 창 크기가 바뀌어도 비율이 유지된다. */
export type DefectCardPosition = { x: number; y: number }

/** 끌기 시작 시점에 재둔 값. 끌는 동안 다시 재지 않는다 — 레이아웃이 흔들린다. */
export type DefectCardDragGeometry = {
  /** 컨테이너 왼쪽·위에서 카드까지의 거리(px). */
  originLeft: number
  originTop: number
  containerWidth: number
  containerHeight: number
  cardWidth: number
  cardHeight: number
}

/**
 * 한 축의 위치를 백분율로 바꾸고 컨테이너 안으로 자른다.
 *
 * <p>자르기를 **백분율에서** 한다. px에서 자르고 나서 반올림하면 반올림이 경계를
 * 0.5%p 넘길 수 있다.
 */
function clampToPercent(
  positionPx: number,
  containerSize: number,
  cardSize: number,
): number {
  if (containerSize <= 0) {
    return 0
  }
  const minPercent = Math.ceil((CARD_DRAG_MARGIN_PX / containerSize) * 100)
  /*
   * 먼저 "카드가 전부 보이는 자리"까지만 허용한다. 그렇게 해서 움직일 여유가 안 나오면
   * (기본 카드는 지도 높이를 꽉 채운다) "손잡이는 남는 자리"까지 넓힌다. 이 두 번째
   * 단계가 없으면 위아래로 1px도 못 움직인다.
   */
  const fitPercent = Math.floor(
    ((containerSize - cardSize - CARD_DRAG_MARGIN_PX) / containerSize) * 100,
  )
  const maxPercent = fitPercent > minPercent
    ? fitPercent
    : Math.floor(
        ((containerSize - CARD_DRAG_MIN_VISIBLE_PX) / containerSize) * 100,
      )
  // 아주 좁은 컨테이너에서는 둘이 뒤집힌다. 그때는 여백만 남기고 붙여둔다.
  if (maxPercent <= minPercent) {
    return Math.min(Math.max(minPercent, 0), 100)
  }
  const percent = Math.round((positionPx / containerSize) * 100)
  return Math.min(Math.max(percent, minPercent), maxPercent)
}

export function cardPositionFromDrag(
  geometry: DefectCardDragGeometry,
  deltaX: number,
  deltaY: number,
): DefectCardPosition {
  return {
    x: clampToPercent(
      geometry.originLeft + deltaX,
      geometry.containerWidth,
      geometry.cardWidth,
    ),
    y: clampToPercent(
      geometry.originTop + deltaY,
      geometry.containerHeight,
      geometry.cardHeight,
    ),
  }
}

/**
 * App.css의 위치 클래스와 짝이다. `null`이면 빈 문자열 — 카드가 기본 자리
 * (오른쪽 위)에 그대로 있다.
 */
export function cardPositionClasses(
  position: DefectCardPosition | null,
): string {
  if (position === null) {
    return ''
  }
  return [
    'is-dragged',
    `dc-x-${Math.floor(position.x / 10)}`,
    `dc-xf-${position.x % 10}`,
    `dc-y-${Math.floor(position.y / 10)}`,
    `dc-yf-${position.y % 10}`,
  ].join(' ')
}

/** 방향키 한 번에 옮기는 양(%p). 1%p는 너무 잘아 눌러도 움직인 줄 모른다. */
export const CARD_KEYBOARD_STEP = 2

/**
 * 방향키로 옮긴 뒤의 위치. 모르는 키면 `null`이라 호출부가 기본 동작(스크롤 등)을
 * 그대로 흘려보낼 수 있다.
 *
 * <p>`current`는 지금 그려진 자리다. 아직 끌어본 적이 없으면 호출부가 카드를 재서 넘긴다
 * — 기본 자리(오른쪽 위)는 백분율로 들고 있지 않기 때문이다.
 */
export function cardPositionFromKey(
  current: DefectCardPosition,
  key: string,
  geometry: DefectCardDragGeometry,
): DefectCardPosition | null {
  const step = CARD_KEYBOARD_STEP
  const moved: DefectCardPosition | null =
    key === 'ArrowLeft' ? { ...current, x: current.x - step }
      : key === 'ArrowRight' ? { ...current, x: current.x + step }
        : key === 'ArrowUp' ? { ...current, y: current.y - step }
          : key === 'ArrowDown' ? { ...current, y: current.y + step }
            : null
  return moved === null ? null : clampCardPosition(moved, geometry)
}

/** 백분율 위치를 컨테이너 안으로 자른다. 창이 좁아졌을 때도 같은 규칙을 쓴다. */
export function clampCardPosition(
  position: DefectCardPosition,
  geometry: DefectCardDragGeometry,
): DefectCardPosition {
  return {
    x: clampToPercent(
      (position.x / 100) * geometry.containerWidth,
      geometry.containerWidth,
      geometry.cardWidth,
    ),
    y: clampToPercent(
      (position.y / 100) * geometry.containerHeight,
      geometry.containerHeight,
      geometry.cardHeight,
    ),
  }
}
