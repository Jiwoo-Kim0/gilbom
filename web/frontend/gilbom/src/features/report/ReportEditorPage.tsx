import {
  AlertTriangle,
  ArrowLeft,
  BarChart3,
  Bot,
  FileText,
  RefreshCw,
  Save,
  Sparkles,
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
  useSearchParams,
} from 'react-router'
import {
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import {
  createReport,
  getReport,
  ReportCreateAttempt,
  updateReport,
} from './reportApi'
import {
  toDateTimeLocal,
} from './reportFormat'
import { getReportActionPolicy } from './reportPolicy'
import { loadReportStatistics } from './reportStatisticsApi'
import type {
  ReportCreateRequest,
  ReportDetail,
  ReportStatisticsSnapshot,
  ReportType,
} from './reportTypes'
import { useAuthentication } from '../auth/authenticationContext'

type ReportEditorMode = 'create' | 'edit'
type CreateGenerationMethod = 'MANUAL' | 'TEMPLATE' | 'LLM'

const TEMPLATE_CONTENT = `운영 요약

주요 지표

주요 결함

조치 권고

후속 확인 사항`

function isReportConflict(error: unknown): boolean {
  return (
    error instanceof ApiError
    && error.status === 409
    && error.code === 'REPORT_STATE_CONFLICT'
  )
}

function isLlmFailure(error: unknown): boolean {
  return (
    error instanceof ApiError
    && (
      error.code === 'LLM_UNAVAILABLE'
      || error.code === 'LLM_RESPONSE_INVALID'
    )
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
 * 결함 카드에서 넘어온 기간을 폼 초기값으로 쓴다. 값이 없거나 해석할 수 없으면
 * 빈 문자열이라 기존처럼 사용자가 직접 고른다.
 */
function prefilledPeriod(mode: ReportEditorMode, value: string | null): string {
  if (mode !== 'create' || value === null) {
    return ''
  }
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '' : toDateTimeLocal(value)
}

export function ReportEditorPage({ mode }: { mode: ReportEditorMode }) {
  const { reportId = '' } = useParams()

  // 결함 카드의 "리포트 생성"이 넘겨주는 값이다. 있으면 그 결함 한 건짜리 보고서가 된다.
  const [searchParams] = useSearchParams()
  const defectId = mode === 'create' ? searchParams.get('defectId') : null
  const defectIncidentCode = searchParams.get('incidentCode')

  // 결함 없이 들어오면 LLM을 고를 수 없으므로 수동을 기본으로 둔다.
  const initialGenerationMethod: CreateGenerationMethod =
    defectId === null ? 'MANUAL' : 'LLM'

  const navigate = useNavigate()
  const createAttemptRef = useRef(new ReportCreateAttempt())
  const submittingRef = useRef(false)
  const [detail, setDetail] = useState<ReportDetail | null>(null)
  const [conflictDetail, setConflictDetail] = useState<ReportDetail | null>(null)
  const [loading, setLoading] = useState(mode === 'edit')
  const [submitting, setSubmitting] = useState(false)
  const [statisticsLoading, setStatisticsLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [generationMethod, setGenerationMethod] =
    useState<CreateGenerationMethod>(initialGenerationMethod)
  const [reportType, setReportType] = useState<ReportType>('PERIODIC')
  const [title, setTitle] = useState('')
  const [summary, setSummary] = useState('')
  const [periodFrom, setPeriodFrom] = useState(() =>
    prefilledPeriod(mode, searchParams.get('from')),
  )
  const [periodTo, setPeriodTo] = useState(() =>
    prefilledPeriod(mode, searchParams.get('to')),
  )
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
    if (mode !== 'edit') {
      return
    }
    let active = true
    void getReport(reportId)
      .then((loaded) => {
        if (!active) {
          return
        }
        setDetail(loaded)
        setReportType(loaded.reportType)
        setGenerationMethod(
          loaded.generationMethod === 'TEMPLATE'
            ? 'TEMPLATE'
            : (loaded.generationMethod === 'LLM' ? 'LLM' : 'MANUAL'),
        )
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
  }, [mode, reportId])

  function changePeriod(
    setter: (value: string) => void,
    value: string,
  ) {
    setter(value)
    setStatistics(null)
  }

  function applyTemplate(method: CreateGenerationMethod) {
    setGenerationMethod(method)
    if (mode === 'create' && method === 'TEMPLATE' && content.trim().length === 0) {
      setContent(TEMPLATE_CONTENT)
    }
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
    if (mode === 'edit' && (detail === null || !policy.canEdit)) {
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
        (mode === 'edit' || generationMethod !== 'LLM')
        && (
          title.trim().length === 0
          || content.trim().length === 0
          || statistics === null
        )
      ) {
        throw new Error('제목·본문과 기간 통계 snapshot을 준비해주세요.')
      }

      submittingRef.current = true
      setSubmitting(true)
      setError(null)

      let saved: ReportDetail
      if (mode === 'create') {
        const input: ReportCreateRequest = generationMethod === 'LLM'
          ? {
            generationMethod: 'LLM',
            reportType,
            periodFrom: from,
            periodTo: to,
            // 결함 카드에서 넘어온 경우 그 결함 한 건만 근거로 삼는다.
            // 통계는 기간 전체를 유지한다(단건으로 좁히면 의미가 없다).
            ...(defectId === null ? {} : { filterCriteria: { defectId } }),
          }
          : {
            generationMethod,
            reportType,
            title: title.trim(),
            summary: summary.trim().length === 0 ? null : summary.trim(),
            periodFrom: from,
            periodTo: to,
            content,
            statistics: statistics as ReportStatisticsSnapshot,
          }
        saved = await createReport(input, createAttemptRef.current)
      } else {
        saved = await updateReport(
          reportId,
          (detail as ReportDetail).currentVersionNo,
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
      }

      navigate(`/reports/${encodeURIComponent(saved.reportId)}`, {
        replace: true,
        state: {
          notice: mode === 'create'
            ? '보고서를 생성했습니다.'
            : `v${saved.currentVersionNo}으로 저장했습니다.`,
        },
      })
    } catch (submitError) {
      if (mode === 'edit' && isReportConflict(submitError)) {
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

  if (mode === 'edit' && detail === null) {
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

  if (mode === 'edit' && !policy.canEdit) {
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
            to={mode === 'create'
              ? '/reports'
              : `/reports/${encodeURIComponent(reportId)}`}
          >
            <ArrowLeft size={16} aria-hidden="true" />
            {mode === 'create' ? '보고서 목록' : '보고서 상세'}
          </Link>
          <p className="eyebrow">
            {mode === 'create' ? 'NEW REPORT' : `EDIT VERSION ${detail?.currentVersionNo}`}
          </p>
          <h2 id="report-editor-title">
            {mode === 'create' ? '새 보고서 작성' : '보고서 새 버전 작성'}
          </h2>
          <p>
            {mode === 'create'
              ? '수동·템플릿·LLM 방식으로 DRAFT 보고서를 생성합니다.'
              : '현재 입력은 보존한 채 새 불변 버전으로 저장됩니다.'}
          </p>
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
          {mode === 'create' && generationMethod === 'LLM' && isLlmFailure(error) ? (
            <div>
              <p>입력을 유지한 채 다시 생성하거나 수동 작성으로 전환할 수 있습니다.</p>
              <button
                className="secondary-button"
                type="button"
                onClick={() => applyTemplate('MANUAL')}
              >
                수동 작성으로 전환
              </button>
            </div>
          ) : null}
        </div>
      ) : null}

      <form className="report-editor-layout" onSubmit={handleSubmit}>
        <main className="report-editor-main">
          {mode === 'create' ? (
            <section className="content-card report-method-card">
              <div className="report-section-heading">
                <div>
                  <p className="eyebrow">GENERATION METHOD</p>
                  <h3>생성 방식</h3>
                </div>
              </div>
              <div className="report-method-options">
                <label className={generationMethod === 'MANUAL' ? 'is-selected' : ''}>
                  <input
                    type="radio"
                    name="generationMethod"
                    value="MANUAL"
                    checked={generationMethod === 'MANUAL'}
                    onChange={() => applyTemplate('MANUAL')}
                    disabled={submitting}
                  />
                  <FileText size={20} aria-hidden="true" />
                  <strong>수동</strong>
                  <span>본문과 통계를 직접 구성</span>
                </label>
                <label className={generationMethod === 'TEMPLATE' ? 'is-selected' : ''}>
                  <input
                    type="radio"
                    name="generationMethod"
                    value="TEMPLATE"
                    checked={generationMethod === 'TEMPLATE'}
                    onChange={() => applyTemplate('TEMPLATE')}
                    disabled={submitting}
                  />
                  <BarChart3 size={20} aria-hidden="true" />
                  <strong>템플릿</strong>
                  <span>안전한 일반 텍스트 틀 사용</span>
                </label>
                {/*
                  LLM 보고서는 결함 한 건을 근거로만 만든다. 대상 결함 없이 들어온
                  경우(보고서 목록의 "새 보고서")에는 고를 수 없게 한다.
                */}
                <label
                  className={`${generationMethod === 'LLM' ? 'is-selected' : ''}${
                    defectId === null ? ' is-disabled' : ''
                  }`}
                >
                  <input
                    type="radio"
                    name="generationMethod"
                    value="LLM"
                    checked={generationMethod === 'LLM'}
                    onChange={() => applyTemplate('LLM')}
                    disabled={submitting || defectId === null}
                  />
                  <Sparkles size={20} aria-hidden="true" />
                  <strong>LLM</strong>
                  <span>
                    {defectId === null
                      ? '결함 상세에서 "리포트 생성"으로 시작하세요'
                      : '서버 검증 후 DRAFT 생성'}
                  </span>
                </label>
              </div>
            </section>
          ) : null}

          <section className="content-card report-editor-fields">
            <div className="report-section-heading">
              <div>
                <p className="eyebrow">REPORT SCOPE</p>
                <h3>유형과 대상 기간</h3>
                {defectId !== null && (
                  <p>
                    결함 {defectIncidentCode ?? defectId} 한 건을 근거로
                    생성합니다. 통계는 아래 기간 전체를 기준으로 집계됩니다.
                  </p>
                )}
              </div>
            </div>
            <div className="report-form-grid">
              <label className="admin-form-field">
                보고서 유형
                <select
                  value={reportType}
                  onChange={(event) => setReportType(event.target.value as ReportType)}
                  disabled={mode === 'edit' || submitting}
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

          {generationMethod !== 'LLM' || mode === 'edit' ? (
            <section className="content-card report-editor-fields">
              <div className="report-section-heading">
                <div>
                  <p className="eyebrow">CONTENT</p>
                  <h3>제목과 본문</h3>
                </div>
                <span>HTML·Markdown 미지원</span>
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
                  <small>저장된 문자열은 상세 화면에서 text node로만 렌더링됩니다.</small>
                </label>
                {mode === 'edit' ? (
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
                ) : null}
              </div>
            </section>
          ) : (
            <section className="content-card report-llm-explanation">
              <Bot size={28} aria-hidden="true" />
              <div>
                <h3>LLM 생성은 서버에서 수행됩니다.</h3>
                <p>
                  기간 통계와 허용된 결함만 전달하고 JSON Schema 검증을 통과한
                  결과만 DRAFT로 저장합니다.
                </p>
              </div>
            </section>
          )}
        </main>

        <aside className="report-editor-aside">
          {generationMethod !== 'LLM' || mode === 'edit' ? (
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
          ) : null}

          <section className="content-card report-save-card">
            <h3>{mode === 'create' ? 'DRAFT 생성' : '새 버전 저장'}</h3>
            <p>
              {mode === 'create'
                ? '생성 중 버튼을 잠그고 같은 입력 재시도에는 동일 멱등 키를 사용합니다.'
                : `현재 v${detail?.currentVersionNo}을 기준으로 저장합니다.`}
            </p>
            <button
              className="primary-button button-with-icon report-save-button"
              type="submit"
              disabled={submitting || statisticsLoading}
            >
              {generationMethod === 'LLM' && mode === 'create'
                ? <Sparkles size={17} aria-hidden="true" />
                : <Save size={17} aria-hidden="true" />}
              {submitting
                ? '처리 중…'
                : (
                  generationMethod === 'LLM' && mode === 'create'
                    ? 'LLM 보고서 생성'
                    : (mode === 'create' ? '보고서 생성' : '새 버전 저장')
                )}
            </button>
          </section>
        </aside>
      </form>
    </section>
  )
}
