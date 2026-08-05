import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  Download,
  FilePenLine,
  Send,
  Trash2,
  XCircle,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import {
  Link,
  useLocation,
  useNavigate,
  useParams,
} from 'react-router'
import {
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import { DEFECT_STATUS_VIEW } from '../auth/permissions'
import {
  approveReport,
  deleteReport,
  getReport,
  getReportVersion,
  listReportVersions,
  rejectReport,
  submitReport,
} from './reportApi'
import {
  actorLabel,
  formatReportDateTime,
  formatReportDisplayTitle,
  formatReportPeriod,
} from './reportFormat'
import { getReportActionPolicy } from './reportPolicy'
import { ReportEvidenceGallery } from './ReportEvidenceGallery'
import { ReportStatusBadge } from './ReportStatusBadge'
import { SafeReportDocument } from './SafeReportDocument'
import { SafeReportText } from './SafeReportText'
import {
  useReportRealtime,
  type ReportRealtimeEvent,
} from './useReportRealtime'
import type {
  ReportDetail,
  ReportVersion,
  ReportVersionSummary,
} from './reportTypes'

type DecisionMode = 'approve' | 'reject'

function isStateConflict(error: unknown): boolean {
  return (
    error instanceof ApiError
    && error.status === 409
    && error.code === 'REPORT_STATE_CONFLICT'
  )
}

export function ReportDetailPage() {
  const { reportId = '' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const requestSequenceRef = useRef(0)
  const [detail, setDetail] = useState<ReportDetail | null>(null)
  const [versions, setVersions] = useState<ReportVersionSummary[]>([])
  const [selectedVersionNo, setSelectedVersionNo] = useState<number | null>(null)
  const [selectedVersion, setSelectedVersion] = useState<ReportVersion | null>(null)
  const [loading, setLoading] = useState(true)
  const [versionLoading, setVersionLoading] = useState(false)
  const [actionBusy, setActionBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(() => {
    const state = location.state as { notice?: unknown } | null
    return typeof state?.notice === 'string' ? state.notice : null
  })
  const [conflictNotice, setConflictNotice] = useState<string | null>(null)
  const [decisionMode, setDecisionMode] = useState<DecisionMode | null>(null)
  const [decisionReason, setDecisionReason] = useState('')
  const { user } = useAuthentication()

  const policy = useMemo(
    () => getReportActionPolicy(
      detail,
      user?.userId ?? null,
      user?.permissions ?? [],
    ),
    [detail, user],
  )
  const canViewDefectEvidence =
    user?.permissions.includes(DEFECT_STATUS_VIEW) ?? false

  const load = useCallback(async (preserveSelectedVersion = false) => {
    const requestSequence = requestSequenceRef.current + 1
    requestSequenceRef.current = requestSequence
    setLoading(true)
    setError(null)
    try {
      const [nextDetail, versionPage] = await Promise.all([
        getReport(reportId),
        listReportVersions(reportId, 0, 100),
      ])
      if (requestSequence !== requestSequenceRef.current) {
        return null
      }
      setDetail(nextDetail)
      setVersions(versionPage.content)
      setSelectedVersionNo((current) => (
        preserveSelectedVersion
        && current !== null
        && versionPage.content.some((version) => version.versionNo === current)
          ? current
          : nextDetail.currentVersionNo
      ))
      setSelectedVersion((current) => (
        preserveSelectedVersion
        && current !== null
        && current.versionNo !== nextDetail.currentVersionNo
          ? current
          : nextDetail.currentVersion
      ))
      return nextDetail
    } catch (loadError) {
      if (requestSequence === requestSequenceRef.current) {
        setError(loadError)
      }
      return null
    } finally {
      if (requestSequence === requestSequenceRef.current) {
        setLoading(false)
      }
    }
  }, [reportId])

  const refreshFromRealtime = useCallback((
    event: ReportRealtimeEvent | null,
  ) => {
    if (event?.deleted === true) {
      navigate('/reports', {
        replace: true,
        state: {
          notice: '보고서가 삭제되어 목록으로 이동했습니다.',
        },
      })
      return
    }
    void load(true)
  }, [load, navigate])

  useReportRealtime(refreshFromRealtime, reportId)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void load()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [load])

  async function selectVersion(versionNo: number) {
    if (detail === null || versionNo === selectedVersionNo) {
      return
    }
    setSelectedVersionNo(versionNo)
    setVersionLoading(true)
    setError(null)
    try {
      const version = versionNo === detail.currentVersionNo
        ? detail.currentVersion
        : await getReportVersion(reportId, versionNo)
      setSelectedVersion(version)
    } catch (versionError) {
      setError(versionError)
    } finally {
      setVersionLoading(false)
    }
  }

  async function handleConflict(errorValue: unknown) {
    if (!isStateConflict(errorValue)) {
      setError(errorValue)
      return
    }
    const latest = await load()
    setConflictNotice(
      latest === null
        ? '보고서가 다른 사용자에 의해 변경되었습니다. 최신 정보 재조회에도 실패했습니다.'
        : `보고서가 다른 사용자에 의해 변경되어 최신 v${latest.currentVersionNo}을 다시 불러왔습니다.`,
    )
  }

  async function handleSubmit() {
    if (detail === null || !policy.canSubmit || actionBusy) {
      return
    }
    setActionBusy(true)
    setError(null)
    setNotice(null)
    try {
      await submitReport(reportId, detail.currentVersionNo)
      setNotice('보고서를 검토 요청했습니다.')
      await load()
    } catch (actionError) {
      await handleConflict(actionError)
    } finally {
      setActionBusy(false)
    }
  }

  async function handleDecision(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (detail === null || decisionMode === null || actionBusy) {
      return
    }
    if (decisionMode === 'approve' && !policy.canApprove) {
      return
    }
    if (decisionMode === 'reject' && !policy.canReject) {
      return
    }

    const normalizedReason = decisionReason.trim()
    if (decisionMode === 'reject' && normalizedReason.length === 0) {
      setError(new Error('반려 사유를 입력해주세요.'))
      return
    }

    setActionBusy(true)
    setError(null)
    setNotice(null)
    try {
      if (decisionMode === 'approve') {
        await approveReport(
          reportId,
          detail.currentVersionNo,
          normalizedReason.length === 0 ? undefined : normalizedReason,
        )
        setNotice('보고서를 승인했습니다.')
      } else {
        await rejectReport(reportId, detail.currentVersionNo, normalizedReason)
        setNotice('보고서를 반려했습니다.')
      }
      setDecisionMode(null)
      setDecisionReason('')
      await load()
    } catch (actionError) {
      await handleConflict(actionError)
    } finally {
      setActionBusy(false)
    }
  }

  async function handleDelete() {
    if (
      detail === null
      || !policy.canDelete
      || actionBusy
      || !window.confirm('이 보고서를 삭제 표시하시겠습니까? 버전은 보존됩니다.')
    ) {
      return
    }
    setActionBusy(true)
    setError(null)
    try {
      await deleteReport(reportId, detail.currentVersionNo)
      navigate('/reports', {
        replace: true,
        state: { notice: '보고서를 삭제했습니다.' },
      })
    } catch (actionError) {
      await handleConflict(actionError)
      setActionBusy(false)
    }
  }

  if (loading && detail === null) {
    return (
      <section className="content-card report-loading" role="status">
        <span className="loading-spinner" aria-hidden="true" />
        보고서 상세를 불러오는 중입니다.
      </section>
    )
  }

  if (detail === null) {
    return (
      <section className="page-stack">
        <Link className="secondary-button report-back-link" to="/reports">
          <ArrowLeft size={17} aria-hidden="true" />
          목록
        </Link>
        <div className="page-alert" role="alert">
          {error ? getApiErrorMessage(error) : '보고서를 찾을 수 없습니다.'}
        </div>
      </section>
    )
  }

  const version = selectedVersion ?? detail.currentVersion
  const isCurrentVersion = version.versionNo === detail.currentVersionNo
  const displayTitle = formatReportDisplayTitle(
    version.title,
    version.content,
    detail.reportCode,
    version.versionNo,
    typeof detail.filterCriteria.defectId === 'string',
  )

  return (
    <section className="page-stack" aria-labelledby="report-detail-title">
      <div className="report-detail-heading">
        <div>
          <Link className="report-back-link" to="/reports">
            <ArrowLeft size={16} aria-hidden="true" />
            보고서 목록
          </Link>
          <div className="report-title-line">
            <ReportStatusBadge status={detail.reportStatus} />
            <span>{detail.reportCode}</span>
          </div>
          <h2 id="report-detail-title">{displayTitle}</h2>
        </div>
      </div>

      {conflictNotice ? (
        <div className="report-conflict-banner" role="alert">
          <AlertTriangle size={20} aria-hidden="true" />
          <div>
            <strong>버전 충돌을 감지했습니다.</strong>
            <p>{conflictNotice}</p>
          </div>
        </div>
      ) : null}
      {notice ? <div className="page-notice" role="status">{notice}</div> : null}
      {error ? (
        <div className="page-alert" role="alert">{getApiErrorMessage(error)}</div>
      ) : null}

      <div className="report-detail-grid">
        <main className="report-detail-main">
          <article className="content-card report-version-card">
            <div className="report-section-heading">
              <div>
                <p className="eyebrow">
                  {isCurrentVersion ? 'CURRENT VERSION' : 'HISTORICAL VERSION'}
                </p>
                <h3>버전 v{version.versionNo}</h3>
              </div>
              {!isCurrentVersion ? (
                <span className="report-read-only-label">과거 버전 · 읽기 전용</span>
              ) : null}
            </div>
            <dl className="report-metadata-grid">
              <div>
                <dt>대상 기간</dt>
                <dd>{formatReportPeriod(version.periodFrom, version.periodTo)}</dd>
              </div>
              <div>
                <dt>버전 작성자</dt>
                <dd>
                  {actorLabel(
                    version.createdBy,
                    user?.userId ?? null,
                    user?.displayName ?? null,
                  )}
                </dd>
              </div>
              <div>
                <dt>작성 일시</dt>
                <dd>{formatReportDateTime(version.createdAt)}</dd>
              </div>
              <div>
                <dt>생성 모델</dt>
                <dd>{version.llmModel ?? '해당 없음'}</dd>
              </div>
            </dl>
            {version.changeNote ? (
              <div className="report-change-note">
                <strong>버전 변경 사유</strong>
                <SafeReportText text={version.changeNote} />
              </div>
            ) : null}
            <div className="report-document">
              {versionLoading ? (
                <div className="report-loading" role="status">
                  버전을 불러오는 중입니다.
                </div>
              ) : (
                <>
                  <ReportEvidenceGallery
                    key={[
                      version.versionNo,
                      ...version.evidenceSnapshot.defectIds,
                    ].join(':')}
                    defectIds={version.evidenceSnapshot.defectIds}
                    canView={canViewDefectEvidence}
                  />
                  <SafeReportDocument
                    text={version.content}
                    documentTitle={version.title}
                  />
                </>
              )}
            </div>
          </article>

          <article className="content-card report-statistics-card">
            <div className="report-section-heading">
              <div>
                <p className="eyebrow">STATISTICS SNAPSHOT</p>
                <h3>버전 통계</h3>
              </div>
              <span>저장 시점 불변 snapshot</span>
            </div>
            <div className="report-metric-grid">
              <div><span>임무</span><strong>{version.statistics.aggregate.missionCount}</strong></div>
              <div><span>완료율</span><strong>{version.statistics.aggregate.completionRatePercent}%</strong></div>
              <div><span>Incident</span><strong>{version.statistics.aggregate.incidentEventCount}</strong></div>
              <div><span>결함</span><strong>{version.statistics.aggregate.defectCount}</strong></div>
              <div><span>관측</span><strong>{version.statistics.aggregate.observationCount}</strong></div>
            </div>
          </article>

          {detail.reportStatus === 'REJECTED' ? (
            <article className="content-card report-rejection-card">
              <div className="report-section-heading">
                <div>
                  <p className="eyebrow">REJECTION</p>
                  <h3>반려 사유</h3>
                </div>
              </div>
              <div className="report-contract-gap">
                반려 사유는 현재 상세·버전 응답에 포함되지 않습니다. 서버 조회 계약이
                추가될 때까지 저장된 사유를 임의로 표시하지 않습니다.
              </div>
            </article>
          ) : null}

          {detail.reportStatus === 'APPROVED' ? (
            <article className="content-card report-pdf-card">
              <div>
                <p className="eyebrow">APPROVED PDF</p>
                <h3>승인본 PDF</h3>
                <p>승인 버전 v{detail.approvedVersionNo}의 PDF입니다.</p>
              </div>
              <button className="secondary-button button-with-icon" type="button" disabled>
                <Download size={17} aria-hidden="true" />
                PDF API 연결 대기
              </button>
              <small>현재 OpenAPI에는 승인 PDF 조회 endpoint가 없습니다.</small>
            </article>
          ) : null}
        </main>

        <aside className="report-detail-aside">
          <section className="content-card report-state-card">
            <p className="eyebrow">WORKFLOW</p>
            <div className="report-current-state">
              <ReportStatusBadge status={detail.reportStatus} />
              <strong>현재 v{detail.currentVersionNo}</strong>
            </div>
            <dl className="report-actor-list">
              <div>
                <dt>최초 작성자</dt>
                <dd>{actorLabel(detail.createdBy, user?.userId ?? null, user?.displayName ?? null)}</dd>
              </div>
              <div>
                <dt>최근 수정자</dt>
                <dd>{actorLabel(detail.currentVersion.createdBy, user?.userId ?? null, user?.displayName ?? null)}</dd>
              </div>
              <div>
                <dt>승인자</dt>
                <dd>
                  {detail.approvedBy === null
                    ? '—'
                    : actorLabel(
                      detail.approvedBy,
                      user?.userId ?? null,
                      user?.displayName ?? null,
                    )}
                </dd>
              </div>
              <div>
                <dt>최근 수정</dt>
                <dd>{formatReportDateTime(detail.updatedAt)}</dd>
              </div>
            </dl>

            <div className="report-workflow-actions">
              {policy.canSubmit ? (
                <button
                  className="primary-button button-with-icon"
                  type="button"
                  disabled={actionBusy}
                  onClick={() => void handleSubmit()}
                >
                  <Send size={17} aria-hidden="true" />
                  검토 요청
                </button>
              ) : null}
              {policy.canEdit ? (
                <Link
                  className="secondary-button button-with-icon"
                  to={`/reports/${encodeURIComponent(reportId)}/edit`}
                >
                  <FilePenLine size={17} aria-hidden="true" />
                  {detail.reportStatus === 'REJECTED' ? '수정·재작성' : '수정'}
                </Link>
              ) : null}
              {policy.canReject ? (
                <button
                  className="report-danger-button button-with-icon"
                  type="button"
                  disabled={actionBusy}
                  onClick={() => {
                    setDecisionMode('reject')
                    setDecisionReason('')
                  }}
                >
                  <XCircle size={17} aria-hidden="true" />
                  반려
                </button>
              ) : null}
              {policy.canApprove ? (
                <button
                  className="primary-button button-with-icon"
                  type="button"
                  disabled={actionBusy}
                  onClick={() => {
                    setDecisionMode('approve')
                    setDecisionReason('')
                  }}
                >
                  <CheckCircle2 size={17} aria-hidden="true" />
                  승인
                </button>
              ) : null}
              {policy.canDelete ? (
                <button
                  className="report-danger-button button-with-icon"
                  type="button"
                  disabled={actionBusy}
                  onClick={() => void handleDelete()}
                >
                  <Trash2 size={17} aria-hidden="true" />
                  삭제
                </button>
              ) : null}
              {policy.readOnly ? (
                <p className="report-read-only-note">
                  현재 상태 또는 권한에서는 내용을 변경할 수 없습니다.
                </p>
              ) : null}
            </div>
          </section>

          <section className="content-card report-version-list-card">
            <div className="report-section-heading">
              <h3>버전 기록</h3>
              <span>{versions.length}개</span>
            </div>
            <ol className="report-version-list">
              {versions.map((item) => (
                <li key={item.versionNo}>
                  <button
                    type="button"
                    className={item.versionNo === selectedVersionNo ? 'is-selected' : ''}
                    onClick={() => void selectVersion(item.versionNo)}
                  >
                    <strong>v{item.versionNo}</strong>
                    <span>{item.title}</span>
                    <small>{formatReportDateTime(item.createdAt)}</small>
                  </button>
                </li>
              ))}
            </ol>
          </section>
        </aside>
      </div>

      {decisionMode !== null ? (
        <div className="report-dialog-backdrop">
          <section
            className="report-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="report-decision-title"
          >
            <h3 id="report-decision-title">
              보고서 {decisionMode === 'approve' ? '승인' : '반려'}
            </h3>
            <p>
              현재 버전 v{detail.currentVersionNo}에 대한 결정을 기록합니다.
            </p>
            <form onSubmit={handleDecision}>
              <label className="admin-form-field">
                {decisionMode === 'approve' ? '승인 메모 (선택)' : '반려 사유'}
                <textarea
                  value={decisionReason}
                  onChange={(event) => setDecisionReason(event.target.value)}
                  maxLength={500}
                  rows={5}
                  required={decisionMode === 'reject'}
                  autoFocus
                />
              </label>
              <div className="report-dialog-actions">
                <button
                  className="secondary-button"
                  type="button"
                  disabled={actionBusy}
                  onClick={() => setDecisionMode(null)}
                >
                  취소
                </button>
                <button
                  className={decisionMode === 'approve'
                    ? 'primary-button'
                    : 'report-danger-button'}
                  type="submit"
                  disabled={actionBusy}
                >
                  {actionBusy ? '처리 중…' : (
                    decisionMode === 'approve' ? '승인 확정' : '반려 확정'
                  )}
                </button>
              </div>
            </form>
          </section>
        </div>
      ) : null}
    </section>
  )
}
