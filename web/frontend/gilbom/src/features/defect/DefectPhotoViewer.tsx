import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Minus, Plus, X } from 'lucide-react'
import { lockBodyScroll } from '../../shared/dom/bodyScrollLock'
import { BoundingBoxOverlay } from './DefectBoundingBox'
import {
  anchoredScroll,
  canZoomIn,
  canZoomOut,
  MIN_PHOTO_ZOOM,
  nextPhotoZoom,
  photoZoomClass,
  photoZoomLabel,
} from './defectPhotoZoom'
import type { PhotoZoom } from './defectPhotoZoom'

interface DefectPhotoViewerProps {
  imageUrl: string
  /** [xMin, yMin, xMax, yMax] 정규 좌표. AI가 지목한 영역을 확대해서도 보여준다. */
  bboxNorm: number[] | null
  incidentCode: string
  onClose: () => void
}

/**
 * 증거 사진을 크게 보는 창이다. 카드 안 사진은 340px 폭이라 파손 부위를 눈으로 가릴 수
 * 없어, 눌러서 화면 크기로 열고 단계별로 확대한다.
 *
 * <p>확대는 CSS class로 한다(`.defect-photo-zoom-*`). CSP가 inline style을 막아 계산된
 * `transform`을 쓸 수 없다 — 자세한 배경은 {@link photoZoomClass} 쪽 주석 참고.
 * 이동은 확대된 사진을 담은 상자의 스크롤로 한다. 브라우저가 주는 스크롤이라
 * 키보드·트랙패드에서도 그대로 동작한다.
 */
export function DefectPhotoViewer({
  imageUrl,
  bboxNorm,
  incidentCode,
  onClose,
}: DefectPhotoViewerProps) {
  const [zoom, setZoom] = useState<PhotoZoom>(MIN_PHOTO_ZOOM)
  const [dragging, setDragging] = useState(false)
  /**
   * 사진의 원본 비율. 사진을 상자에 `contain`으로 담으므로 bbox 오버레이도 같은 비율로
   * 여백을 둬야 겹친다. 서버 응답의 imageWidth·imageHeight는 null일 수 있어 실제로
   * 그려진 이미지에서 읽는다.
   */
  const [aspect, setAspect] = useState<{ width: number; height: number } | null>(
    null,
  )
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const closeRef = useRef<HTMLButtonElement | null>(null)
  /** 끌기 시작점. 화면 좌표와 그때의 스크롤 위치를 함께 잡아둔다. */
  const dragOriginRef = useRef({ x: 0, y: 0, left: 0, top: 0 })
  /**
   * 배율이 바뀐 뒤 옮겨놓을 스크롤 위치. 상자 크기가 실제로 바뀐 다음에 적용해야 하므로
   * 눌린 시점의 값을 담아두고 `useLayoutEffect`에서 쓴다.
   */
  const pendingAnchorRef = useRef<
    { x: number; y: number; left: number; top: number; ratio: number } | null
  >(null)

  /**
   * 배율을 바꾸면서 기준점을 화면에 고정한다. 기준점이 없으면 스크롤이 0에 머물러
   * 늘 왼쪽 위로만 확대된다.
   */
  const zoomTo = (next: PhotoZoom, anchor: { x: number; y: number }) => {
    const scroll = scrollRef.current
    if (next === zoom || scroll === null) {
      return
    }
    pendingAnchorRef.current = {
      x: anchor.x,
      y: anchor.y,
      left: scroll.scrollLeft,
      top: scroll.scrollTop,
      ratio: next / zoom,
    }
    setZoom(next)
  }

  /** 버튼·키보드로 확대할 때의 기준점. 지금 보이는 영역의 가운데다. */
  const centerAnchor = () => {
    const scroll = scrollRef.current
    return scroll === null
      ? { x: 0, y: 0 }
      : { x: scroll.clientWidth / 2, y: scroll.clientHeight / 2 }
  }

  const zoomBy = (direction: 1 | -1, anchor = centerAnchor()) => {
    zoomTo(nextPhotoZoom(zoom, direction), anchor)
  }

  /*
   * 배율이 바뀐 직후, 그림이 그려지기 전에 스크롤을 옮긴다. effect(paint 후)로 하면
   * 한 프레임 동안 왼쪽 위로 튀는 것이 보인다.
   */
  useLayoutEffect(() => {
    const scroll = scrollRef.current
    const pending = pendingAnchorRef.current
    pendingAnchorRef.current = null
    if (scroll === null || pending === null) {
      return
    }
    scroll.scrollLeft = anchoredScroll(pending.left, pending.x, pending.ratio)
    scroll.scrollTop = anchoredScroll(pending.top, pending.y, pending.ratio)
  }, [zoom])

  useEffect(() => {
    const releaseBodyScroll = lockBodyScroll()
    // 열자마자 Esc·Tab이 먹도록 창 안으로 포커스를 옮긴다.
    closeRef.current?.focus()

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose()
        return
      }
      // 키보드만으로도 확대할 수 있게 한다. 브라우저 확대와 헷갈리지 않도록
      // Ctrl/Cmd가 눌린 조합은 건드리지 않는다.
      if (event.ctrlKey || event.metaKey) {
        return
      }
      if (event.key === '+' || event.key === '=') {
        zoomBy(1)
      }
      if (event.key === '-' || event.key === '_') {
        zoomBy(-1)
      }
      if (event.key === '0') {
        zoomTo(MIN_PHOTO_ZOOM, centerAnchor())
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      releaseBodyScroll()
      window.removeEventListener('keydown', handleKeyDown)
    }
    // zoom이 바뀌면 핸들러를 다시 걸어야 한다. 지금 배율을 기준으로 한 단계를 계산한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onClose, zoom])

  useEffect(() => {
    const scroll = scrollRef.current
    if (scroll === null) {
      return
    }
    /*
     * React의 onWheel은 passive로 붙어 preventDefault가 통하지 않는다. 직접 걸어
     * 페이지가 함께 스크롤되는 것을 막는다.
     */
    // 함수 선언(hoisting) 대신 const로 둔다. 선언은 위의 null 검사보다 앞서 올라가
    // 좁혀진 타입이 풀린다.
    const handleWheel = (event: WheelEvent) => {
      event.preventDefault()
      // 커서가 가리키는 지점을 고정한다. 지도 확대와 같은 감각이다.
      const rect = scroll.getBoundingClientRect()
      zoomBy(event.deltaY < 0 ? 1 : -1, {
        x: event.clientX - rect.left,
        y: event.clientY - rect.top,
      })
    }

    scroll.addEventListener('wheel', handleWheel, { passive: false })
    return () => scroll.removeEventListener('wheel', handleWheel)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [zoom])

  const startDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    const scroll = scrollRef.current
    if (scroll === null || zoom === MIN_PHOTO_ZOOM) {
      return
    }
    /*
     * 사진 위에서 끌면 브라우저가 이미지 드래그(HTML5 drag&drop)를 시작해 pointermove가
     * 끊긴다. img의 draggable=false와 함께 여기서도 기본 동작을 막는다.
     */
    event.preventDefault()
    dragOriginRef.current = {
      x: event.clientX,
      y: event.clientY,
      left: scroll.scrollLeft,
      top: scroll.scrollTop,
    }
    setDragging(true)
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  const moveDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    const scroll = scrollRef.current
    if (!dragging || scroll === null) {
      return
    }
    const origin = dragOriginRef.current
    // 사진을 손으로 미는 느낌이 되도록 끄는 방향과 반대로 스크롤한다.
    scroll.scrollLeft = origin.left - (event.clientX - origin.x)
    scroll.scrollTop = origin.top - (event.clientY - origin.y)
  }

  const endDrag = () => setDragging(false)

  return (
    <div
      className="defect-photo-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label={`${incidentCode} 증거 사진`}
      // 사진 바깥을 누르면 닫는다. 사진 자체는 아래에서 전파를 막는다.
      onClick={onClose}
    >
      <div
        className="defect-photo-panel"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="defect-photo-header">
          <div className="defect-photo-title">
            <p className="defect-photo-code">{incidentCode}</p>
            {/* 안내를 따로 한 줄로 두면 그만큼 사진이 작아진다. 머리줄에 붙인다. */}
            <p className="defect-photo-hint">
              휠·<kbd>+</kbd><kbd>-</kbd> 확대, 끌어서 이동, <kbd>0</kbd> 전체,
              {' '}<kbd>Esc</kbd> 닫기
            </p>
          </div>
          <div className="defect-photo-controls">
            <button
              type="button"
              className="defect-photo-control"
              aria-label="축소"
              disabled={!canZoomOut(zoom)}
              onClick={() => zoomBy(-1)}
            >
              <Minus size={16} aria-hidden="true" />
            </button>
            <span className="defect-photo-zoom-label" aria-live="polite">
              {photoZoomLabel(zoom)}
            </span>
            <button
              type="button"
              className="defect-photo-control"
              aria-label="확대"
              disabled={!canZoomIn(zoom)}
              onClick={() => zoomBy(1)}
            >
              <Plus size={16} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="secondary-button defect-photo-reset"
              disabled={zoom === MIN_PHOTO_ZOOM}
              onClick={() => zoomTo(MIN_PHOTO_ZOOM, centerAnchor())}
            >
              전체 보기
            </button>
            <button
              type="button"
              className="defect-photo-control"
              aria-label="닫기"
              ref={closeRef}
              onClick={onClose}
            >
              <X size={18} aria-hidden="true" />
            </button>
          </div>
        </header>

        <div
          className={`defect-photo-scroll${dragging ? ' is-dragging' : ''}`}
          ref={scrollRef}
          onPointerDown={startDrag}
          onPointerMove={moveDrag}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
        >
          <div className={`defect-photo-frame ${photoZoomClass(zoom)}`}>
            <img
              src={imageUrl}
              alt={`${incidentCode} 증거 사진`}
              draggable={false}
              onLoad={(event) => {
                const { naturalWidth, naturalHeight } = event.currentTarget
                if (naturalWidth > 0 && naturalHeight > 0) {
                  setAspect({ width: naturalWidth, height: naturalHeight })
                }
              }}
            />
            <BoundingBoxOverlay bboxNorm={bboxNorm} aspect={aspect} />
          </div>
        </div>

      </div>
    </div>
  )
}
