/**
 * AI가 검출한 영역을 사진 위에 그린다.
 *
 * <p>`bboxNorm`은 [xMin, yMin, xMax, yMax] 정규 좌표(0~1)다. viewBox를 `0 0 1 1`로
 * 두면 그 값을 그대로 rect 좌표로 쓸 수 있어 이미지 크기·종횡비 계산이 필요 없다.
 * SVG는 figure를 꽉 채우고 figure 높이는 이미지가 정하므로 항상 정확히 겹친다.
 *
 * <p>모양은 `App.css`의 클래스로만 준다. Nginx CSP가 `style-src 'self'`라
 * style 속성을 쓰면 차단된다.
 */
export function BoundingBoxOverlay({ bboxNorm }: { bboxNorm: number[] | null }) {
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
  return (
    <svg
      className="defect-bbox"
      viewBox="0 0 1 1"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <rect
        x={xMin}
        y={yMin}
        width={width}
        height={height}
        // 상자 선 두께가 이미지 비율에 따라 찌그러지지 않게 한다.
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  )
}
