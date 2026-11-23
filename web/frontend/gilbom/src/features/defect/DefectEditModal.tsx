import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { DefectChoiceGroup } from './DefectChoiceGroup'
import { updateDefect } from './defectApi'
import {
  buildDefectUpdateRequest,
  defectEditBlockingHint,
  EDIT_REASON_MAX_LENGTH,
} from './defectEditForm'
import type { DefectEditForm } from './defectEditForm'
import { defectTypeTone } from './defectMapState'
import {
  DEFECT_TYPES,
  DEFECT_TYPE_LABELS,
  RISK_LEVELS,
  RISK_LEVEL_LABELS,
  type DefectDetail,
  type DefectListItem,
} from './defectTypes'

export function DefectEditModal({
  defect,
  onClose,
  onUpdated,
}: {
  defect: DefectListItem
  onClose: () => void
  onUpdated: (detail: DefectDetail) => void
}) {
  const [form, setForm] = useState<DefectEditForm>({
    defectType: defect.defectType,
    riskLevel: defect.riskLevel,
    zoneId: defect.zoneId ?? '',
    locationLabel: defect.locationLabel ?? '',
    reviewRequired: defect.reviewRequired,
    reason: '',
  })
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const dialogRef = useRef<HTMLDivElement>(null)
  const fieldId = useId()

  const request = buildDefectUpdateRequest(defect, form)
  const hint = defectEditBlockingHint(request, form.reason)
  const canSubmit = hint === null && !submitting

  function update<K extends keyof DefectEditForm>(
    key: K,
    value: DefectEditForm[K],
  ) {
    setForm((previous) => ({ ...previous, [key]: value }))
  }

  useEffect(() => {
    // 열자마자 포커스를 창으로 옮긴다. 그러지 않으면 Tab이 뒤 화면을 훑고 다닌다.
    dialogRef.current?.focus()

    function handleKeyDown(event: KeyboardEvent) {
      // 저장하는 중에는 닫지 않는다. 응답을 못 받은 채로 창이 사라진다.
      if (event.key === 'Escape' && !submitting) {
        onClose()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose, submitting])

  async function submit() {
    if (request === null || !canSubmit) {
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      onUpdated(await updateDefect(defect.defectId, request))
      onClose()
    } catch (updateError) {
      setError(getApiErrorMessage(updateError))
    } finally {
      setSubmitting(false)
    }
  }

  /*
   * 지도 컨테이너가 `isolation: isolate`로 stacking context를 만들기 때문에, 그 안에
   * 남겨두면 화면 전체를 덮어야 하는 이 창이 고정된 상단 바 밑에 깔린다.
   * 모달은 문서 최상단에 그린다.
   */
  return createPortal(
    <div
      className="defect-modal-backdrop"
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget && !submitting) {
          onClose()
        }
      }}
    >
      <div
        className="defect-modal defect-edit-modal"
        role="dialog"
        aria-modal="true"
        aria-label={`${defect.incidentCode} 결함 수정`}
        ref={dialogRef}
        tabIndex={-1}
      >
        <header className="defect-modal-header">
          <div>
            <p className="eyebrow">{defect.incidentCode}</p>
            <h3>결함 수정</h3>
          </div>
          <button
            type="button"
            className="defect-card-close"
            aria-label="닫기"
            disabled={submitting}
            onClick={onClose}
          >
            <X size={18} aria-hidden="true" />
          </button>
        </header>

        <form
          className="defect-edit-body"
          onSubmit={(event) => {
            event.preventDefault()
            void submit()
          }}
        >
          <section className="defect-edit-section">
            <h4>결함 유형</h4>
            {/* 점 색은 지도 마커·필터와 같은 변수를 쓴다. 셋이 어긋나지 않는다. */}
            <DefectChoiceGroup
              label="결함 유형"
              options={DEFECT_TYPES}
              optionLabels={DEFECT_TYPE_LABELS}
              value={form.defectType}
              disabled={submitting}
              onChange={(defectType) => update('defectType', defectType)}
              renderMark={(defectType) => (
                <span
                  className={`defect-type-dot defect-type-dot-${defectTypeTone(defectType)}`}
                  aria-hidden="true"
                />
              )}
            />
          </section>

          <section className="defect-edit-section">
            <h4>심각도</h4>
            {/* 고른 알약이 목록 표의 배지와 같은 색이 된다. */}
            <DefectChoiceGroup
              label="심각도"
              options={RISK_LEVELS}
              optionLabels={RISK_LEVEL_LABELS}
              value={form.riskLevel}
              disabled={submitting}
              onChange={(riskLevel) => update('riskLevel', riskLevel)}
              optionClassName={(riskLevel) =>
                `defect-choice-risk-${riskLevel.toLowerCase()}`
              }
            />
          </section>

          <section className="defect-edit-section">
            <h4>위치</h4>
            <div className="defect-edit-pair">
              <label className="defect-edit-field">
                <span>구역</span>
                <input
                  type="text"
                  className="defect-text-input"
                  value={form.zoneId}
                  disabled={submitting}
                  placeholder="A-01"
                  onChange={(event) => update('zoneId', event.target.value)}
                />
              </label>
              <label className="defect-edit-field">
                <span>위치 설명</span>
                <input
                  type="text"
                  className="defect-text-input"
                  value={form.locationLabel}
                  maxLength={200}
                  disabled={submitting}
                  placeholder="예: 진평동 사거리 북단"
                  onChange={(event) =>
                    update('locationLabel', event.target.value)
                  }
                />
              </label>
            </div>
            {/* 좌표는 지도에서 옮겨야 의미가 있어 이 창에서 다루지 않는다. */}
            <p className="defect-edit-hint">
              비워두면 기존 값을 그대로 둡니다. 좌표는 바꿀 수 없습니다.
            </p>
          </section>

          {/*
            체크박스를 스위치 모양으로 바꿨다. 켜짐·꺼짐이 한눈에 들어와야 하는 값이고,
            요소는 그대로 체크박스라 키보드·스크린리더 동작이 변하지 않는다.
          */}
          <label className="defect-switch">
            <input
              type="checkbox"
              checked={form.reviewRequired}
              disabled={submitting}
              onChange={(event) => update('reviewRequired', event.target.checked)}
            />
            <span className="defect-switch-track" aria-hidden="true" />
            <span className="defect-switch-text">
              <strong>재검토 필요</strong>
              <small>사람이 다시 확인해야 하는 결함으로 표시합니다.</small>
            </span>
          </label>

          <section className="defect-edit-section">
            <h4 className="defect-edit-required">
              <label htmlFor={`${fieldId}-reason`}>수정 사유</label>
              <span className="defect-edit-counter">
                {form.reason.length}/{EDIT_REASON_MAX_LENGTH}
              </span>
            </h4>
            <input
              id={`${fieldId}-reason`}
              type="text"
              className="defect-text-input"
              value={form.reason}
              maxLength={EDIT_REASON_MAX_LENGTH}
              disabled={submitting}
              placeholder="예: 현장 확인 결과 파손이 아니라 미설치였음"
              onChange={(event) => update('reason', event.target.value)}
            />
            <p className="defect-edit-hint">변경 이력에 그대로 남습니다.</p>
          </section>

          {error !== null && (
            <p className="defect-card-error" role="alert">
              {error}
            </p>
          )}

          <div className="defect-edit-actions">
            {/* 저장이 막힌 이유를 버튼 옆에 둔다. 흐린 버튼만으로는 알 수 없다. */}
            <p className="defect-edit-status" role="status">
              {hint ?? '저장하면 변경 이력에 기록됩니다.'}
            </p>
            <div className="defect-edit-buttons">
              <button
                type="button"
                className="secondary-button"
                disabled={submitting}
                onClick={onClose}
              >
                취소
              </button>
              <button
                type="submit"
                className="primary-button"
                disabled={!canSubmit}
              >
                {submitting ? '저장 중…' : '저장'}
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  )
}
