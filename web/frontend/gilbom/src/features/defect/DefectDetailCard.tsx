import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { X } from 'lucide-react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import {
  DEFECT_STATUS_UPDATE,
  REPORT_CREATE,
  hasPermission,
} from '../auth/permissions'
import { changeDefectStatus, getDefect, getDefectImageUrl } from './defectApi'
import {
  ALLOWED_TRANSITIONS,
  DEFECT_TYPE_LABELS,
  RESOLUTIONS,
  RESOLUTION_LABELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type {
  DefectDetail,
  DefectImageUrl,
  Resolution,
  WorkflowStatus,
} from './defectTypes'

function formatDateTime(value: string): string {
  return new Date(value).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** 이미지가 없는 결함이 흔하므로 404는 오류가 아니라 "없음"으로 다룬다. */
type ImageState =
  | { kind: 'loading' }
  | { kind: 'none' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; image: DefectImageUrl }

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
function BoundingBoxOverlay({ bboxNorm }: { bboxNorm: number[] | null }) {
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

interface DefectDetailCardProps {
  defectId: string
  onClose: () => void
  /** 상태를 바꾸면 목록·지도를 다시 읽어야 한다. */
  onChanged: () => void
}

export function DefectDetailCard({
  defectId,
  onClose,
  onChanged,
}: DefectDetailCardProps) {
  const navigate = useNavigate()
  const { user } = useAuthentication()
  const permissions = user?.permissions ?? []
  const canUpdate = hasPermission(permissions, DEFECT_STATUS_UPDATE)
  const canCreateReport = hasPermission(permissions, REPORT_CREATE)

  const [detail, setDetail] = useState<DefectDetail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [image, setImage] = useState<ImageState>({ kind: 'loading' })

  const [nextStatus, setNextStatus] = useState<WorkflowStatus | ''>('')
  const [resolution, setResolution] = useState<Resolution | ''>('')
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const load = useCallback(() => {
    let cancelled = false

    getDefect(defectId)
      .then((response) => {
        if (!cancelled) {
          setDetail(response)
          setLoadError(null)
          setNextStatus('')
          setResolution('')
          setReason('')
          setSubmitError(null)
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setDetail(null)
          setLoadError(getApiErrorMessage(cause))
        }
      })

    getDefectImageUrl(defectId)
      .then((response) => {
        if (!cancelled) setImage({ kind: 'ready', image: response })
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        // 이미지가 아직 연결되지 않은 결함은 정상적인 상태다.
        const status = (cause as { status?: number }).status
        setImage(
          status === 404
            ? { kind: 'none' }
            : { kind: 'error', message: getApiErrorMessage(cause) },
        )
      })

    return () => {
      cancelled = true
    }
  }, [defectId])

  useEffect(() => load(), [load])

  const defect = detail?.defect ?? null
  const allowed: WorkflowStatus[] = defect
    ? ALLOWED_TRANSITIONS[defect.workflowStatus]
    : []
  // 서버 DefectWorkflow와 같은 규칙이다. 완료 처리에는 처리 결과가 필요하다.
  const resolutionRequired = nextStatus === 'RESOLVED'
  const canSubmit =
    canUpdate
    && nextStatus !== ''
    && reason.trim().length > 0
    && (!resolutionRequired || resolution !== '')
    && !submitting

  const submit = () => {
    // canSubmit이 nextStatus !== '' 를 포함하므로 여기서 다시 검사하지 않는다.
    if (!canSubmit) return
    setSubmitting(true)
    setSubmitError(null)

    changeDefectStatus(defectId, {
      workflowStatus: nextStatus,
      resolution: resolution === '' ? null : resolution,
      reason: reason.trim(),
    })
      .then((updated) => {
        setDetail(updated)
        setNextStatus('')
        setResolution('')
        setReason('')
        onChanged()
      })
      .catch((cause: unknown) => {
        setSubmitError(getApiErrorMessage(cause))
      })
      .finally(() => setSubmitting(false))
  }

  return (
    <aside className="defect-card" aria-label="결함 상세">
      <header className="defect-card-header">
        <div>
          <p className="eyebrow">{defect?.incidentCode ?? '결함'}</p>
          <h3>
            {defect
              ? (DEFECT_TYPE_LABELS[defect.defectType] ?? defect.defectType)
              : '불러오는 중…'}
          </h3>
        </div>
        <button
          type="button"
          className="defect-card-close"
          aria-label="닫기"
          onClick={onClose}
        >
          <X size={16} aria-hidden="true" />
        </button>
      </header>

      {loadError !== null && (
        <p className="defect-card-error" role="alert">
          {loadError}
        </p>
      )}

      {defect !== null && (
        <>
          <div className="defect-card-image">
            {image.kind === 'loading' && <p>이미지를 불러오는 중…</p>}
            {image.kind === 'none' && <p>연결된 이미지가 없습니다.</p>}
            {image.kind === 'error' && <p role="alert">{image.message}</p>}
            {image.kind === 'ready' && (
              <figure className="defect-card-figure">
                <img src={image.image.imageUrl} alt="결함 증거 이미지" />
                <BoundingBoxOverlay bboxNorm={image.image.bboxNorm} />
              </figure>
            )}
          </div>
          {image.kind === 'ready' && image.image.bboxNorm !== null && (
            <p className="defect-card-bbox-caption">
              노란 상자는 AI가 결함으로 판단한 영역입니다.
            </p>
          )}

          <dl className="defect-card-facts">
            <div>
              <dt>위치</dt>
              <dd>
                {defect.locationLabel ?? defect.zoneId ?? '—'}
                {defect.location && (
                  <span className="defect-card-coord">
                    {defect.location.latitude.toFixed(5)},{' '}
                    {defect.location.longitude.toFixed(5)}
                    {defect.locationAccuracyM !== null
                      && ` (±${defect.locationAccuracyM}m)`}
                  </span>
                )}
              </dd>
            </div>
            <div>
              <dt>최초 발견</dt>
              <dd>{formatDateTime(defect.firstSeenAt)}</dd>
            </div>
            <div>
              <dt>최근 관측</dt>
              <dd>
                {formatDateTime(defect.lastSeenAt)}
                <span className="defect-card-coord">
                  누적 {defect.observationCount}회
                </span>
              </dd>
            </div>
            <div>
              <dt>심각도</dt>
              <dd>
                {RISK_LEVEL_LABELS[defect.riskLevel] ?? defect.riskLevel}
                {defect.latestConfidence !== null && (
                  <span className="defect-card-coord">
                    AI 신뢰도 {Math.round(defect.latestConfidence * 100)}%
                  </span>
                )}
              </dd>
            </div>
            <div>
              <dt>처리 상태</dt>
              <dd>
                <em
                  className={`defect-status defect-status-${defect.workflowStatus.toLowerCase()}`}
                >
                  {WORKFLOW_STATUS_LABELS[defect.workflowStatus]}
                </em>
              </dd>
            </div>
          </dl>

          <div className="defect-card-actions">
            {allowed.length === 0 ? (
              <p className="defect-card-terminal">
                종결된 결함이라 상태를 더 바꿀 수 없습니다.
              </p>
            ) : (
              <>
                <label className="defect-card-field">
                  <span>처리 상태 변경</span>
                  <select
                    value={nextStatus}
                    disabled={!canUpdate || submitting}
                    onChange={(event) => {
                      setNextStatus(event.target.value as WorkflowStatus | '')
                      setResolution('')
                    }}
                  >
                    <option value="">선택하세요</option>
                    {allowed.map((status) => (
                      <option key={status} value={status}>
                        {WORKFLOW_STATUS_LABELS[status]}
                      </option>
                    ))}
                  </select>
                </label>

                {resolutionRequired && (
                  <label className="defect-card-field">
                    <span>처리 결과</span>
                    <select
                      value={resolution}
                      disabled={submitting}
                      onChange={(event) =>
                        setResolution(event.target.value as Resolution | '')
                      }
                    >
                      <option value="">선택하세요</option>
                      {RESOLUTIONS.map((value) => (
                        <option key={value} value={value}>
                          {RESOLUTION_LABELS[value]}
                        </option>
                      ))}
                    </select>
                  </label>
                )}

                {nextStatus !== '' && (
                  <label className="defect-card-field">
                    <span>변경 사유</span>
                    <input
                      type="text"
                      value={reason}
                      maxLength={1000}
                      disabled={submitting}
                      placeholder="변경 이력에 남습니다"
                      onChange={(event) => setReason(event.target.value)}
                    />
                  </label>
                )}

                {!canUpdate && (
                  <p className="defect-card-terminal">
                    상태를 변경할 권한이 없습니다.
                  </p>
                )}

                {submitError !== null && (
                  <p className="defect-card-error" role="alert">
                    {submitError}
                  </p>
                )}

                <button
                  type="button"
                  className="primary-button"
                  disabled={!canSubmit}
                  onClick={submit}
                >
                  {submitting ? '변경 중…' : '상태 변경'}
                </button>
              </>
            )}

            {canCreateReport && (
              <button
                type="button"
                className="secondary-button"
                onClick={() => {
                  // 보고서 편집 화면은 아직 이 값들을 읽지 않는다. 프리필이
                  // 붙으면 그대로 쓰이도록 컨텍스트를 함께 넘겨 둔다.
                  const params = new URLSearchParams({
                    defectId: defect.defectId,
                    from: defect.firstSeenAt,
                    to: defect.lastSeenAt,
                  })
                  if (defect.patrolAreaId) {
                    params.set('patrolAreaId', defect.patrolAreaId)
                  }
                  navigate(`/reports/new?${params}`)
                }}
              >
                리포트 생성
              </button>
            )}
          </div>
        </>
      )}
    </aside>
  )
}
