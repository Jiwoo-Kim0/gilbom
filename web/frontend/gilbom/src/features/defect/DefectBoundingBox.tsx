interface DefectBoundingBoxProps {
  /** [xMin, yMin, xMax, yMax] 정규 좌표(0~1). */
  bboxNorm: number[] | null
  /**
   * 사진의 원본 비율. 사진을 `object-fit: contain`으로 담아 여백(letterbox)이 생기는
   * 곳에서만 넘긴다. 같은 비율을 SVG에도 주면 오버레이가 사진과 똑같이 여백을 두어
   * 상자가 정확히 겹친다.
   */
  aspect?: { width: number; height: number } | null
}

/**
 * AI가 검출한 영역을 사진 위에 그린다.
 *
 * <p>기본은 `viewBox="0 0 1 1"` + `preserveAspectRatio="none"`이다. 사진이 상자를 꽉
 * 채우는 곳(상세 카드처럼 폭으로 크기가 정해지는 경우)에서는 그것으로 정확히 겹치고,
 * 이미지 크기를 몰라도 된다.
 *
 * <p>`aspect`를 주면 그 비율의 좌표계로 그리고 `meet`으로 맞춘다. 확대 창처럼 사진이
 * 상자 안에서 여백을 두고 담기는 경우에 쓴다.
 *
 * <p>모양은 `App.css`의 클래스로만 준다. Nginx CSP가 `style-src 'self'`라
 * style 속성을 쓰면 차단된다.
 */
export function BoundingBoxOverlay({
  bboxNorm,
  aspect = null,
}: DefectBoundingBoxProps) {
  if (bboxNorm === null || bboxNorm.length !== 4) {
    return null
  }
  const [xMin, yMin, xMax, yMax] = bboxNorm
  const width = xMax - xMin
  const height = yMax - yMin
  // 서버가 순서를 검증하지만, 뒤집힌 값이 오면 그리지 않는다.
  if (!(width > 0) || !(height > 0)) {
    return null
  }

  const scaleX = aspect === null ? 1 : aspect.width
  const scaleY = aspect === null ? 1 : aspect.height
  return (
    <svg
      className="defect-bbox"
      viewBox={`0 0 ${scaleX} ${scaleY}`}
      preserveAspectRatio={aspect === null ? 'none' : 'xMidYMid meet'}
      aria-hidden="true"
    >
      <rect
        x={xMin * scaleX}
        y={yMin * scaleY}
        width={width * scaleX}
        height={height * scaleY}
        // 상자 선 두께가 이미지 비율에 따라 찌그러지지 않게 한다.
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  )
}
