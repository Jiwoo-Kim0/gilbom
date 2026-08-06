import { useState } from 'react'
import { X } from 'lucide-react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { mergeDefects } from './defectApi'
import {
  DEFECT_TYPE_LABELS,
  RISK_LEVEL_LABELS,
  type DefectListItem,
  type DefectMergeResult,
} from './defectTypes'

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * 자동 병합이 놓친 중복을 사람이 합친다.
 *
 * <p>서버의 자동 병합은 탐지 시점에 반경 2m·같은 유형·같은 블록 타입·같은 순찰구역이
 * 전부 맞을 때만 동작한다. GPS가 튀거나 유형이 다르게 분류되면 별도 결함으로 남는데,
 * 그걸 여기서 합친다.
 */
export function DefectMergeModal({
  defects,
  onClose,
  onMerged,
}: {
  defects: DefectListItem[]
  onClose: () => void
  onMerged: (result: DefectMergeResult) => void
}) {
  // 관측이 가장 많은 결함을 기본 대표로 둔다. 근거가 가장 많은 쪽이라 대개 맞다.
  const [targetDefectId, setTargetDefectId] = useState(
    () =>
      [...defects].sort(
        (left, right) => right.observationCount - left.observationCount,
      )[0].defectId,
  )
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const sourceDefectIds = defects
    .map((defect) => defect.defectId)
    .filter((defectId) => defectId !== targetDefectId)
  const canSubmit
    = sourceDefectIds.length > 0 && reason.trim().length > 0 && !submitting

  async function submit() {
    if (!canSubmit) {
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      onMerged(
        await mergeDefects({
          targetDefectId,
          sourceDefectIds,
          reason: reason.trim(),
        }),
      )
      onClose()
    } catch (mergeError) {
      setError(getApiErrorMessage(mergeError))
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
        aria-label="결함 병합"
      >
        <header className="defect-modal-header">
          <div>
            <p className="eyebrow">선택한 {defects.length}건</p>
            <h3>결함 병합</h3>
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

        <p className="defect-card-coord">
          대표로 남길 결함을 고르세요. 나머지는 대표 결함으로 흡수되어 목록에서
          사라지고, 관측 기록은 대표 결함에 합산됩니다.
        </p>

        <ul className="defect-merge-candidates">
          {defects.map((defect) => (
            <li key={defect.defectId}>
              <label className="defect-merge-candidate">
                <input
                  type="radio"
                  name="merge-target"
                  value={defect.defectId}
                  checked={defect.defectId === targetDefectId}
                  disabled={submitting}
                  onChange={() => setTargetDefectId(defect.defectId)}
                />
                <span>
                  <strong>{defect.incidentCode}</strong>
                  <span className="defect-card-coord">
                    {DEFECT_TYPE_LABELS[defect.defectType] ?? defect.defectType}
                    {' · '}
                    {RISK_LEVEL_LABELS[defect.riskLevel] ?? defect.riskLevel}
                    {' · 관측 '}
                    {defect.observationCount}
                    회 · 최근 {formatDateTime(defect.lastSeenAt)}
                  </span>
                </span>
              </label>
            </li>
          ))}
        </ul>

        <label className="defect-card-field">
          <span>병합 사유</span>
          <input
            type="text"
            value={reason}
            maxLength={1000}
            disabled={submitting}
            placeholder="예: GPS 오차로 같은 블록이 두 건으로 잡힘"
            onChange={(event) => setReason(event.target.value)}
          />
        </label>

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
            {submitting
              ? '병합 중…'
              : `${sourceDefectIds.length}건 병합`}
          </button>
        </div>
      </div>
    </div>
  )
}
