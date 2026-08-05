import {
  AlertTriangle,
  ArrowLeft,
  RefreshCw,
  Save,
} from 'lucide-react'
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import {
  Link,
  useNavigate,
  useParams,
} from 'react-router'
import {
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import {
  getReport,
  updateReport,
} from './reportApi'
import {
  toDateTimeLocal,
} from './reportFormat'
import { getReportActionPolicy } from './reportPolicy'
import { loadReportStatistics } from './reportStatisticsApi'
import type {
  ReportDetail,
  ReportStatisticsSnapshot,
  ReportType,
} from './reportTypes'
import { useAuthentication } from '../auth/authenticationContext'

function isReportConflict(error: unknown): boolean {
  return (
    error instanceof ApiError
    && error.status === 409
    && error.code === 'REPORT_STATE_CONFLICT'
  )
}

function toInstant(value: string): string {
  const instant = new Date(value)
  if (Number.isNaN(instant.getTime())) {
    throw new Error('유효한 보고서 기간을 입력해주세요.')
  }
  return instant.toISOString()
}

/**
 * 보고서 편집 전용 화면이다.
 *
 * <p>생성 경로는 여기에 없다. 보고서는 결함 상세의 "리포트 생성"에서 LLM으로만
 * 만들고(2026-08-01 결정), 이 화면은 **발행 뒤 사람이 손보는 경우**를 위해 남는다.
 * 그래서 생성 방식 선택·템플릿·통계 최초 구성 같은 것이 전부 빠져 있다.
 */
export function ReportEditorPage() {
  const { reportId = '' } = useParams()
  const navigate = useNavigate()
  const submittingRef = useRef(false)
  const [detail, setDetail] = useState<ReportDetail | null>(null)
  const [conflictDetail, setConflictDetail] = useState<ReportDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [statisticsLoading, setStatisticsLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [reportType, setReportType] = useState<ReportType>('PERIODIC')
  const [title, setTitle] = useState('')
  const [summary, setSummary] = useState('')
  const [periodFrom, setPeriodFrom] = useState('')
  const [periodTo, setPeriodTo] = useState('')
  const [content, setContent] = useState('')
  const [changeNote, setChangeNote] = useState('')
  const [statistics, setStatistics] = useState<ReportStatisticsSnapshot | null>(
    null,
  )
  const { user } = useAuthentication()

  const policy = useMemo(
    () => getReportActionPolicy(
      detail,
      user?.userId ?? null,
      user?.permissions ?? [],
    ),
    [detail, user],
  )

  useEffect(() => {
    let active = true
    void getReport(reportId)
      .then((loaded) => {
        if (!active) {
          return
        }
        setDetail(loaded)
        setReportType(loaded.reportType)
        setTitle(loaded.currentVersion.title)
        setSummary(loaded.currentVersion.summary ?? '')
        setPeriodFrom(toDateTimeLocal(loaded.currentVersion.periodFrom))
        setPeriodTo(toDateTimeLocal(loaded.currentVersion.periodTo))
        setContent(loaded.currentVersion.content)
        setStatistics(loaded.currentVersion.statistics)
      })
      .catch((loadError: unknown) => {
        if (active) {
          setError(loadError)
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false)
        }
      })
    return () => {
      active = false
    }
  }, [reportId])

  function changePeriod(
    setter: (value: string) => void,
    value: string,
  ) {
    setter(value)
    setStatistics(null)
  }

  async function handleStatisticsLoad() {
    if (statisticsLoading) {
      return
    }
    try {
      const from = toInstant(periodFrom)
      const to = toInstant(periodTo)
      if (new Date(from) >= new Date(to)) {
        throw new Error('통계 기간의 종료 시각은 시작 시각보다 늦어야 합니다.')
      }
      setStatisticsLoading(true)
      setError(null)
      const snapshot = await loadReportStatistics(from, to)
      setStatistics(snapshot)
    } catch (statisticsError) {
      setError(statisticsError)
    } finally {
      setStatisticsLoading(false)
    }
  }

  function resolveConflict(preserveInput: boolean) {
    if (conflictDetail === null) {
      return
    }
    setDetail(conflictDetail)
    if (!preserveInput) {
      setTitle(conflictDetail.currentVersion.title)
      setSummary(conflictDetail.currentVersion.summary ?? '')
      setPeriodFrom(toDateTimeLocal(conflictDetail.currentVersion.periodFrom))
      setPeriodTo(toDateTimeLocal(conflictDetail.currentVersion.periodTo))
      setContent(conflictDetail.currentVersion.content)
      setStatistics(conflictDetail.currentVersion.statistics)
      setChangeNote('')
    }
    setConflictDetail(null)
    setError(null)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submittingRef.current) {
      return
    }
    if (detail === null || !policy.canEdit) {
      setError(new Error('현재 상태 또는 권한에서는 보고서를 수정할 수 없습니다.'))
      return
    }

    try {
      const from = toInstant(periodFrom)
      const to = toInstant(periodTo)
      if (new Date(from) > new Date(to)) {
        throw new Error('보고서 종료 시각은 시작 시각보다 빠를 수 없습니다.')
      }
      if (
        title.trim().length === 0
        || content.trim().length === 0
        || statistics === null
      ) {
        throw new Error('제목·본문과 기간 통계 snapshot을 준비해주세요.')
      }

      submittingRef.current = true
      setSubmitting(true)
      setError(null)

      const saved = await updateReport(
        reportId,
        detail.currentVersionNo,
        {
          title: title.trim(),
          summary: summary.trim().length === 0 ? null : summary.trim(),
          periodFrom: from,
          periodTo: to,
          content,
          statistics: statistics as ReportStatisticsSnapshot,
          changeNote: changeNote.trim().length === 0
            ? null
            : changeNote.trim(),
        },
      )

      navigate(`/reports/${encodeURIComponent(saved.reportId)}`, {
        replace: true,
        state: {
          notice: `v${saved.currentVersionNo}으로 저장했습니다.`,
        },
      })
    } catch (submitError) {
      if (isReportConflict(submitError)) {
        try {
          const latest = await getReport(reportId)
          setConflictDetail(latest)
          setError(null)
        } catch (reloadError) {
          setError(reloadError)
        }
      } else {
        setError(submitError)
      }
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <section className="content-card report-loading" role="status">
        <span className="loading-spinner" aria-hidden="true" />
        편집할 보고서를 불러오는 중입니다.
      </section>
    )
  }

  if (detail === null) {
    return (
      <section className="page-stack">
        <Link className="report-back-link" to="/reports">
          <ArrowLeft size={16} aria-hidden="true" />
          보고서 목록
        </Link>
        <div className="page-alert" role="alert">
          {error ? getApiErrorMessage(error) : '보고서를 찾을 수 없습니다.'}
        </div>
      </section>
    )
  }

  if (!policy.canEdit) {
    return (
      <section className="page-stack">
        <Link
          className="report-back-link"
          to={`/reports/${encodeURIComponent(reportId)}`}
        >
          <ArrowLeft size={16} aria-hidden="true" />
          보고서 상세
        </Link>
        <div className="content-card report-read-only-panel">
          <AlertTriangle size={24} aria-hidden="true" />
          <h2>읽기 전용 보고서입니다.</h2>
          <p>DRAFT와 REJECTED 상태에서 REPORT_UPDATE 권한이 있을 때만 편집할 수 있습니다.</p>
        </div>
      </section>
    )
  }

  return (
    <section className="page-stack" aria-labelledby="report-editor-title">
      <div className="report-detail-heading">
        <div>
          <Link
            className="report-back-link"
            to={`/reports/${encodeURIComponent(reportId)}`}
          >
            <ArrowLeft size={16} aria-hidden="true" />
            보고서 상세
          </Link>
          <p className="eyebrow">
            EDIT VERSION {detail.currentVersionNo}
          </p>
          <h2 id="report-editor-title">보고서 새 버전 작성</h2>
          <p>현재 입력은 보존한 채 새 불변 버전으로 저장됩니다.</p>
        </div>
      </div>

      {conflictDetail ? (
        <div className="report-conflict-banner" role="alert">
          <AlertTriangle size={22} aria-hidden="true" />
          <div>
            <strong>v{conflictDetail.currentVersionNo}이 새로 저장되었습니다.</strong>
            <p>
              현재 입력은 폐기하지 않았습니다. 최신 버전을 기준으로 다시 적용하거나
              서버의 최신 내용으로 폼을 바꿀 수 있습니다.
            </p>
            <div className="report-conflict-actions">
              <button
                className="primary-button"
                type="button"
                onClick={() => resolveConflict(true)}
              >
                내 입력을 최신 버전에 다시 적용
              </button>
              <button
                className="secondary-button"
                type="button"
                onClick={() => resolveConflict(false)}
              >
                최신 서버 내용으로 바꾸기
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {error ? (
        <div className="page-alert report-editor-error" role="alert">
          <strong>{getApiErrorMessage(error)}</strong>
        </div>
      ) : null}

      <form className="report-editor-layout" onSubmit={handleSubmit}>
        <main className="report-editor-main">

          <section className="content-card report-editor-fields">
            <div className="report-section-heading">
              <div>
                <p className="eyebrow">REPORT SCOPE</p>
                <h3>유형과 대상 기간</h3>
              </div>
            </div>
            <div className="report-form-grid">
              <label className="admin-form-field">
                보고서 유형
                <select
                  value={reportType}
                  onChange={(event) => setReportType(event.target.value as ReportType)}
                  disabled
                >
                  <option value="PERIODIC">정기 보고서</option>
                  <option value="ISSUE">이슈 보고서</option>
                </select>
              </label>
              <label className="admin-form-field">
                기간 시작
                <input
                  type="datetime-local"
                  value={periodFrom}
                  onChange={(event) => changePeriod(setPeriodFrom, event.target.value)}
                  disabled={submitting}
                  required
                />
              </label>
              <label className="admin-form-field">
                기간 종료
                <input
                  type="datetime-local"
                  value={periodTo}
                  onChange={(event) => changePeriod(setPeriodTo, event.target.value)}
                  disabled={submitting}
                  required
                />
              </label>
            </div>
          </section>

          <section className="content-card report-editor-fields">
              <div className="report-section-heading">
                <div>
                  <p className="eyebrow">CONTENT</p>
                  <h3>제목과 본문</h3>
                </div>
                <span>보고서 제목·목록 서식 지원</span>
              </div>
              <div className="report-form-grid">
                <label className="admin-form-field report-form-wide">
                  제목
                  <input
                    value={title}
                    onChange={(event) => setTitle(event.target.value)}
                    maxLength={200}
                    disabled={submitting}
                    required
                  />
                </label>
                <label className="admin-form-field report-form-wide">
                  요약
                  <textarea
                    value={summary}
                    onChange={(event) => setSummary(event.target.value)}
                    maxLength={255}
                    rows={3}
                    disabled={submitting}
                  />
                </label>
                <label className="admin-form-field report-form-wide">
                  본문
                  <textarea
                    className="report-content-editor"
                    value={content}
                    onChange={(event) => setContent(event.target.value)}
                    maxLength={100000}
                    rows={18}
                    disabled={submitting}
                    required
                  />
                  <small>
                    줄 시작의 #, ##, ### 제목과 - 목록만 지원합니다.
                    HTML·링크·이미지는 일반 텍스트로 표시됩니다.
                  </small>
                </label>
                <label className="admin-form-field report-form-wide">
                  변경 사유
                  <input
                    value={changeNote}
                    onChange={(event) => setChangeNote(event.target.value)}
                    maxLength={500}
                    disabled={submitting}
                    placeholder="안전한 변경 사유를 입력하세요."
                  />
                </label>
              </div>
            </section>
        </main>

        <aside className="report-editor-aside">
            <section className="content-card report-statistics-selector">
              <p className="eyebrow">STATISTICS</p>
              <h3>통계 snapshot</h3>
              <p>선택한 전체 기간의 Mission·Incident·결함 통계를 불러옵니다.</p>
              <button
                className="secondary-button button-with-icon"
                type="button"
                disabled={statisticsLoading || submitting}
                onClick={() => void handleStatisticsLoad()}
              >
                <RefreshCw size={17} aria-hidden="true" />
                {statisticsLoading ? '통계 불러오는 중…' : '기간 통계 선택'}
              </button>
              {statistics ? (
                <dl className="report-statistics-preview">
                  <div><dt>임무</dt><dd>{statistics.aggregate.missionCount}</dd></div>
                  <div><dt>완료율</dt><dd>{statistics.aggregate.completionRatePercent}%</dd></div>
                  <div><dt>Incident</dt><dd>{statistics.aggregate.incidentEventCount}</dd></div>
                  <div><dt>결함</dt><dd>{statistics.aggregate.defectCount}</dd></div>
                  <div><dt>관측</dt><dd>{statistics.aggregate.observationCount}</dd></div>
                </dl>
              ) : (
                <div className="report-statistics-empty">
                  기간을 입력하고 통계를 선택해주세요.
                </div>
              )}
              <small>
                현재 통계 API는 보고서의 Robot·결함 필터 조합을 지원하지 않아
                기간 전체 snapshot만 제공합니다.
              </small>
            </section>

          <section className="content-card report-save-card">
            <h3>새 버전 저장</h3>
            <p>현재 v{detail.currentVersionNo}을 기준으로 저장합니다.</p>
            <button
              className="primary-button button-with-icon report-save-button"
              type="submit"
              disabled={submitting || statisticsLoading}
            >
              <Save size={17} aria-hidden="true" />
              {submitting ? '처리 중…' : '새 버전 저장'}
            </button>
          </section>
        </aside>
      </form>
    </section>
  )
}
