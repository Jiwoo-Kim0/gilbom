import { useEffect, useRef, useState } from 'react'
import { X } from 'lucide-react'
import { DefectConfidenceField, DefectDateField } from './DefectFilterFields'
import { DefectFilterOptions } from './DefectFilterOptions'
import { defectTypeTone } from './defectMapState'
import {
  DEFECT_TYPES,
  DEFECT_TYPE_LABELS,
  RISK_LEVELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUSES,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type { DefectFilters } from './defectTypes'

interface DefectFilterModalProps {
  filters: DefectFilters
  /** "적용하기". 이때 처음으로 URL이 바뀌고 조회가 나간다. */
  onApply: (next: DefectFilters) => void
  onClose: () => void
}

/**
 * 조건을 한 화면에 펼쳐놓는 상세 필터다. 알약은 조건 하나씩 빠르게 손보는 길이고,
 * 여기는 여러 조건을 한꺼번에 짜맞추는 길이다.
 *
 * <p>**알약과 달리 즉시 반영하지 않는다.** 여기서 고른 것은 초안으로만 들고 있다가
 * "적용하기"에서 한 번에 나간다. 조건 다섯 개를 손보는 동안 매번 조회가 나가면
 * 뒤 화면이 계속 다시 그려져 무엇을 고르는 중인지 알 수 없다. "닫기"는 초안을 버린다.
 */
export function DefectFilterModal({
  filters,
  onApply,
  onClose,
}: DefectFilterModalProps) {
  const [draft, setDraft] = useState(filters)
  const dialogRef = useRef<HTMLDivElement>(null)
  /** 닫을 때 포커스를 돌려줄 곳. 열기 직전에 눌린 "상세 필터" 버튼이다. */
  const openerRef = useRef<Element | null>(null)

  const update = (partial: Partial<DefectFilters>) => {
    setDraft((previous) => ({ ...previous, ...partial }))
  }

  useEffect(() => {
    openerRef.current = document.activeElement
    // 열자마자 포커스를 카드로 옮긴다. 그러지 않으면 Tab이 뒤 화면의 알약들을
    // 훑고 다녀 키보드 사용자는 모달이 열린 줄도 모른다.
    dialogRef.current?.focus()

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      if (openerRef.current instanceof HTMLElement) {
        openerRef.current.focus()
      }
    }
  }, [onClose])

  return (
    <div
      className="defect-filter-modal-backdrop"
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose()
        }
      }}
    >
      <div
        className="defect-filter-modal"
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label="상세 필터"
        tabIndex={-1}
      >
        <header className="defect-filter-modal-header">
          <h3>필터</h3>
          <button
            type="button"
            className="defect-card-close"
            aria-label="필터 창 닫기"
            onClick={onClose}
          >
            <X size={18} aria-hidden="true" />
          </button>
        </header>

        <form
          className="defect-filter-modal-body"
          aria-label="상세 결함 필터"
          onSubmit={(event) => {
            event.preventDefault()
            onApply(draft)
          }}
        >
          <DefectDateField
            from={draft.from}
            onChange={(from) => update({ from })}
          />

          <section className="defect-filter-group">
            <h4 className="defect-filter-group-heading">처리 상태</h4>
            <DefectFilterOptions
              label="처리 상태"
              variant="grid"
              options={WORKFLOW_STATUSES}
              optionLabels={WORKFLOW_STATUS_LABELS}
              selected={draft.workflowStatuses}
              onChange={(workflowStatuses) => update({ workflowStatuses })}
            />
          </section>

          {/*
            지도 마커는 결함 유형으로 색이 갈린다(`defectTypeTone`). 그 색을 이 목록에도
            찍어 "지도의 빨간 핀 = 파손"을 필터에서 읽을 수 있게 한다.
          */}
          <section className="defect-filter-group">
            <h4 className="defect-filter-group-heading">결함 유형</h4>
            <DefectFilterOptions
              label="결함 유형"
              variant="grid"
              options={DEFECT_TYPES}
              optionLabels={DEFECT_TYPE_LABELS}
              selected={draft.defectTypes}
              onChange={(defectTypes) => update({ defectTypes })}
              renderMark={(defectType) => (
                <span
                  className={`defect-type-dot defect-type-dot-${defectTypeTone(defectType)}`}
                  aria-hidden="true"
                />
              )}
            />
          </section>

          <section className="defect-filter-group">
            <h4 className="defect-filter-group-heading">심각도</h4>
            <DefectFilterOptions
              label="심각도"
              variant="grid"
              options={RISK_LEVELS}
              optionLabels={RISK_LEVEL_LABELS}
              selected={draft.severities}
              onChange={(severities) => update({ severities })}
            />
          </section>

          <DefectConfidenceField
            minConfidence={draft.minConfidence}
            onChange={(minConfidence) => update({ minConfidence })}
          />

          <div className="defect-filter-modal-actions">
            <button
              type="button"
              className="secondary-button"
              onClick={onClose}
            >
              닫기
            </button>
            <button type="submit" className="primary-button">
              적용하기
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
