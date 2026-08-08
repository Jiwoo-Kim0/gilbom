import {
  Eye,
  X,
} from 'lucide-react'
import { useEffect } from 'react'
import { lockBodyScroll } from '../../shared/dom/bodyScrollLock'
import { PatrolAreaSelectionPreview } from './PatrolAreaSelectionPreview'
import type { PatrolArea } from './missionTypes'

type PatrolAreaViewDialogProps = {
  area: PatrolArea
  onClose: () => void
}

export function PatrolAreaViewDialog({
  area,
  onClose,
}: PatrolAreaViewDialogProps) {
  useEffect(() => {
    const releaseBodyScroll = lockBodyScroll()
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      releaseBodyScroll()
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [onClose])

  return (
    <div
      className="mission-area-modal-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose()
        }
      }}
    >
      <section
        className="mission-area-modal mission-area-view-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="patrol-area-view-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="mission-area-modal-heading">
          <div>
            <p className="eyebrow">PATROL AREA VIEW</p>
            <h3 id="patrol-area-view-title">순찰 구역 조회</h3>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label="조회 창 닫기"
            onClick={onClose}
          >
            <X size={18} aria-hidden="true" />
          </button>
        </div>

        <div className="mission-area-view-content">
          <p className="mission-area-view-guide">
            <Eye size={16} aria-hidden="true" />
            지도 정점에 마우스를 올리면 해당 위도와 경도를 확인할 수 있습니다.
          </p>
          <PatrolAreaSelectionPreview
            area={area}
            selectedRobot={null}
            robotPosition={null}
            showBoundaryPoints
            titleSuffix="조회 구역"
          />
        </div>

        <div className="mission-area-view-actions">
          <button
            className="secondary-button"
            type="button"
            onClick={onClose}
          >
            닫기
          </button>
        </div>
      </section>
    </div>
  )
}
