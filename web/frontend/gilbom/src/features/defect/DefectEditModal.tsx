import { useState } from 'react'
import { X } from 'lucide-react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { updateDefect } from './defectApi'
import {
  DEFECT_TYPES,
  DEFECT_TYPE_LABELS,
  RISK_LEVELS,
  RISK_LEVEL_LABELS,
  type DefectDetail,
  type DefectListItem,
  type DefectType,
  type DefectUpdateRequest,
  type RiskLevel,
} from './defectTypes'

/**
 * 서버는 PATCH 본문에서 null인 항목을 "변경 없음"으로 읽고, 바뀐 것이 하나도 없으면
 * COMMON_INVALID_REQUEST를 돌려준다. 그래서 화면도 바뀐 축만 담아 보내고, 아무것도
 * 바뀌지 않았으면 아예 보내지 않는다.
 */
function buildRequest(
  defect: DefectListItem,
  form: EditForm,
): DefectUpdateRequest | null {
  const request: DefectUpdateRequest = { reason: form.reason.trim() }
  let changed = false

  if (form.defectType !== defect.defectType) {
    request.defectType = form.defectType
    changed = true
  }
  if (form.riskLevel !== defect.riskLevel) {
    request.riskLevel = form.riskLevel
    changed = true
  }
  // 빈 입력은 "지운다"가 아니라 "건드리지 않는다"로 다룬다. 서버에 null 지우기
  // 계약이 없어서, 화면에서 지운 것처럼 보이게 하면 거짓말이 된다.
  const zoneId = form.zoneId.trim()
  if (zoneId.length > 0 && zoneId !== (defect.zoneId ?? '')) {
    request.zoneId = zoneId
    changed = true
  }
  const locationLabel = form.locationLabel.trim()
  if (
    locationLabel.length > 0
    && locationLabel !== (defect.locationLabel ?? '')
  ) {
    request.locationLabel = locationLabel
    changed = true
  }
  if (form.reviewRequired !== defect.reviewRequired) {
    request.reviewRequired = form.reviewRequired
    changed = true
  }

  return changed ? request : null
}

type EditForm = {
  defectType: DefectType
  riskLevel: RiskLevel
  zoneId: string
  locationLabel: string
  reviewRequired: boolean
  reason: string
}

export function DefectEditModal({
  defect,
  onClose,
  onUpdated,
}: {
  defect: DefectListItem
  onClose: () => void
  onUpdated: (detail: DefectDetail) => void
}) {
  const [form, setForm] = useState<EditForm>({
    defectType: defect.defectType,
    riskLevel: defect.riskLevel,
    zoneId: defect.zoneId ?? '',
    locationLabel: defect.locationLabel ?? '',
    reviewRequired: defect.reviewRequired,
    reason: '',
  })
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const request = buildRequest(defect, form)
  const canSubmit
    = request !== null && form.reason.trim().length > 0 && !submitting

  function update<K extends keyof EditForm>(key: K, value: EditForm[K]) {
    setForm((previous) => ({ ...previous, [key]: value }))
  }

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

  return (
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
        className="defect-modal"
        role="dialog"
        aria-modal="true"
        aria-label={`${defect.incidentCode} 결함 수정`}
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
            <X size={16} aria-hidden="true" />
          </button>
        </header>

        <label className="defect-card-field">
          <span>유형</span>
          <select
            value={form.defectType}
            disabled={submitting}
            onChange={(event) =>
              update('defectType', event.target.value as DefectType)
            }
          >
            {DEFECT_TYPES.map((value) => (
              <option key={value} value={value}>
                {DEFECT_TYPE_LABELS[value]}
              </option>
            ))}
          </select>
        </label>

        <label className="defect-card-field">
          <span>심각도</span>
          <select
            value={form.riskLevel}
            disabled={submitting}
            onChange={(event) =>
              update('riskLevel', event.target.value as RiskLevel)
            }
          >
            {RISK_LEVELS.map((value) => (
              <option key={value} value={value}>
                {RISK_LEVEL_LABELS[value]}
              </option>
            ))}
          </select>
        </label>

        <label className="defect-card-field">
          <span>구역</span>
          <input
            type="text"
            value={form.zoneId}
            disabled={submitting}
            placeholder="A-01"
            onChange={(event) => update('zoneId', event.target.value)}
          />
        </label>

        <label className="defect-card-field">
          <span>위치 설명</span>
          <input
            type="text"
            value={form.locationLabel}
            maxLength={200}
            disabled={submitting}
            placeholder="예: 진평동 사거리 북단"
            onChange={(event) => update('locationLabel', event.target.value)}
          />
        </label>

        <label className="defect-card-checkbox">
          <input
            type="checkbox"
            checked={form.reviewRequired}
            disabled={submitting}
            onChange={(event) => update('reviewRequired', event.target.checked)}
          />
          <span>재검토 필요</span>
        </label>

        <label className="defect-card-field">
          <span>수정 사유</span>
          <input
            type="text"
            value={form.reason}
            maxLength={1000}
            disabled={submitting}
            placeholder="변경 이력에 남습니다"
            onChange={(event) => update('reason', event.target.value)}
          />
        </label>

        {/* 좌표는 지도에서 옮겨야 의미가 있어 이 모달에서 다루지 않는다. */}
        <p className="defect-card-coord">
          구역·위치 설명은 비워두면 기존 값을 유지합니다.
        </p>

        {error !== null && (
          <p className="defect-card-error" role="alert">
            {error}
          </p>
        )}

        <div className="defect-modal-actions">
          <button
            type="button"
            className="secondary-button"
            disabled={submitting}
            onClick={onClose}
          >
            취소
          </button>
          <button
            type="button"
            className="primary-button"
            disabled={!canSubmit}
            onClick={submit}
          >
            {submitting ? '저장 중…' : '저장'}
          </button>
        </div>

        {request === null && (
          <p className="defect-card-terminal">변경된 항목이 없습니다.</p>
        )}
      </div>
    </div>
  )
}
