import {
  ChevronLeft,
  ChevronRight,
  Search,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import {
  Link,
  useLocation,
} from 'react-router'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import { listReports } from './reportApi'
import {
  actorLabel,
  formatReportDateTime,
  formatReportPeriod,
  toReportPeriodEndInstant,
  toReportPeriodStartInstant,
} from './reportFormat'
import { ReportStatusBadge } from './ReportStatusBadge'
import { useReportRealtime } from './useReportRealtime'
import type {
  ReportGenerationMethod,
  ReportListResponse,
  ReportStatus,
  ReportType,
} from './reportTypes'

const PAGE_SIZE = 20

type ReportListDraftFilters = {
  search: string
  status: ReportStatus | ''
  reportType: ReportType | ''
  generationMethod: ReportGenerationMethod | ''
  periodFrom: string
  periodTo: string
}

const EMPTY_FILTERS: ReportListDraftFilters = {
  search: '',
  status: '',
  reportType: '',
  generationMethod: '',
  periodFrom: '',
  periodTo: '',
}

const GENERATION_LABELS: Record<ReportGenerationMethod, string> = {
  MANUAL: '수동',
  TEMPLATE: '템플릿',
  LLM: 'LLM',
  SYSTEM: '시스템',
}

export function ReportListPage() {
  const location = useLocation()
  const requestSequenceRef = useRef(0)
  const [draftFilters, setDraftFilters] =
    useState<ReportListDraftFilters>(EMPTY_FILTERS)
  const [appliedFilters, setAppliedFilters] =
    useState<ReportListDraftFilters>(EMPTY_FILTERS)
  const [page, setPage] = useState(0)
  const [reports, setReports] = useState<ReportListResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [notice] = useState(() => {
    const state = location.state as { notice?: unknown } | null
    return typeof state?.notice === 'string' ? state.notice : null
  })
  const { user } = useAuthentication()

  const load = useCallback(async (
    targetPage: number,
    filters: ReportListDraftFilters,
  ) => {
    const requestSequence = requestSequenceRef.current + 1
    requestSequenceRef.current = requestSequence
    setLoading(true)
    setError(null)
    try {
      const result = await listReports({
        page: targetPage,
        size: PAGE_SIZE,
        search: filters.search,
        statuses: filters.status === '' ? undefined : [filters.status],
        periodFrom: toReportPeriodStartInstant(filters.periodFrom),
        periodTo: toReportPeriodEndInstant(filters.periodTo),
        reportType: filters.reportType === '' ? undefined : filters.reportType,
        generationMethod: filters.generationMethod === ''
          ? undefined
          : filters.generationMethod,
      })
      if (requestSequence === requestSequenceRef.current) {
        setReports(result)
      }
    } catch (loadError) {
      if (requestSequence === requestSequenceRef.current) {
        setError(loadError)
      }
    } finally {
      if (requestSequence === requestSequenceRef.current) {
        setLoading(false)
      }
    }
  }, [])

  const refreshFromRealtime = useCallback(() => {
    void load(page, appliedFilters)
  }, [appliedFilters, load, page])

  useReportRealtime(refreshFromRealtime)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void load(page, appliedFilters)
    }, 0)
    return () => window.clearTimeout(timer)
  }, [appliedFilters, load, page])

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (
      draftFilters.periodFrom !== ''
      && draftFilters.periodTo !== ''
      && draftFilters.periodFrom > draftFilters.periodTo
    ) {
      setError(new Error('조회 시작일은 종료일보다 늦을 수 없습니다.'))
      return
    }
    setError(null)
    setPage(0)
    setAppliedFilters({ ...draftFilters })
  }

  function resetFilters() {
    setDraftFilters({ ...EMPTY_FILTERS })
    setAppliedFilters({ ...EMPTY_FILTERS })
    setPage(0)
  }

  return (
    <section className="page-stack" aria-labelledby="reports-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">REPORTS</p>
          <h2 id="reports-page-title">운영 보고서</h2>
          <p>승인 가능한 불변 버전으로 운영 현황과 결함 조치를 관리합니다.</p>
        </div>
      </div>

      <form className="content-card report-filter-grid" onSubmit={handleSearch}>
        <label className="admin-form-field report-search-field">
          제목·보고서 코드
          <input
            type="search"
            value={draftFilters.search}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              search: event.target.value,
            }))}
            maxLength={100}
            placeholder="제목 또는 보고서 코드"
          />
        </label>
        <label className="admin-form-field">
          상태
          <select
            value={draftFilters.status}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              status: event.target.value as ReportStatus | '',
            }))}
          >
            <option value="">전체 상태</option>
            <option value="DRAFT">초안</option>
            <option value="IN_REVIEW">검토 중</option>
            <option value="APPROVED">승인</option>
            <option value="REJECTED">반려</option>
          </select>
        </label>
        <label className="admin-form-field">
          보고서 유형
          <select
            value={draftFilters.reportType}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              reportType: event.target.value as ReportType | '',
            }))}
          >
            <option value="">전체 유형</option>
            <option value="PERIODIC">정기</option>
            <option value="ISSUE">이슈</option>
          </select>
        </label>
        <label className="admin-form-field">
          생성 방식
          <select
            value={draftFilters.generationMethod}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              generationMethod:
                event.target.value as ReportGenerationMethod | '',
            }))}
          >
            <option value="">전체 방식</option>
            {Object.entries(GENERATION_LABELS).map(([method, label]) => (
              <option key={method} value={method}>{label}</option>
            ))}
          </select>
        </label>
        <fieldset
          className="report-period-filter"
          aria-describedby="report-period-help"
        >
          <legend>기간 검색</legend>
          <input
            type="date"
            aria-label="조회 기간 시작"
            value={draftFilters.periodFrom}
            max={draftFilters.periodTo || undefined}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              periodFrom: event.target.value,
            }))}
          />
          <span>–</span>
          <input
            type="date"
            aria-label="조회 기간 종료"
            value={draftFilters.periodTo}
            min={draftFilters.periodFrom || undefined}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              periodTo: event.target.value,
            }))}
          />
        </fieldset>
        <p id="report-period-help" className="report-contract-note">
          선택한 기간과 하루라도 겹치는 보고서를 조회합니다.
        </p>
        <div className="report-filter-actions">
          <button className="secondary-button" type="button" onClick={resetFilters}>
            초기화
          </button>
          <button className="primary-button button-with-icon" type="submit">
            <Search size={17} aria-hidden="true" />
            검색
          </button>
        </div>
      </form>

      {error ? (
        <div className="page-alert" role="alert">
          {getApiErrorMessage(error)}
        </div>
      ) : null}
      {notice ? <div className="page-notice" role="status">{notice}</div> : null}

      <div className="content-card report-list-card">
        <div className="table-heading">
          <div>
            <h3>보고서 목록</h3>
            <p>소프트 삭제된 보고서는 서버 목록 계약에서 제외됩니다.</p>
          </div>
          <strong>{reports?.totalElements ?? 0}건</strong>
        </div>

        {loading && reports === null ? (
          <div className="report-loading" role="status">
            <span className="loading-spinner" aria-hidden="true" />
            보고서를 불러오는 중입니다.
          </div>
        ) : null}

        {!loading && reports?.content.length === 0 ? (
          <div className="audit-empty">
            <p>검색 조건에 맞는 보고서가 없습니다.</p>
          </div>
        ) : null}

        {reports !== null && reports.content.length > 0 ? (
          <div className="report-table-wrap">
            <table className="report-table">
              <thead>
                <tr>
                  <th>보고서</th>
                  <th>기간</th>
                  <th>상태</th>
                  <th>작성자</th>
                  <th>수정 일시</th>
                  <th><span className="sr-only">상세</span></th>
                </tr>
              </thead>
              <tbody>
                {reports.content.map((report) => (
                  <tr key={report.reportId}>
                    <td>
                      <Link
                        className="report-title-link"
                        to={`/reports/${encodeURIComponent(report.reportId)}`}
                      >
                        <strong>{report.title}</strong>
                        <small>
                          {report.reportCode} · {GENERATION_LABELS[
                            report.generationMethod
                          ]}
                        </small>
                      </Link>
                    </td>
                    <td>{formatReportPeriod(report.periodFrom, report.periodTo)}</td>
                    <td><ReportStatusBadge status={report.reportStatus} /></td>
                    <td>
                      {actorLabel(
                        report.createdBy,
                        user?.userId ?? null,
                        user?.displayName ?? null,
                      )}
                    </td>
                    <td>
                      <time dateTime={report.updatedAt}>
                        {formatReportDateTime(report.updatedAt)}
                      </time>
                    </td>
                    <td>
                      <Link
                        className="report-row-open"
                        aria-label={`${report.title} 상세 보기`}
                        to={`/reports/${encodeURIComponent(report.reportId)}`}
                      >
                        <ChevronRight size={18} aria-hidden="true" />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}

        {reports !== null && reports.totalPages > 1 ? (
          <nav className="pagination" aria-label="보고서 페이지">
            <button
              className="secondary-button"
              type="button"
              disabled={page === 0 || loading}
              onClick={() => setPage((current) => current - 1)}
            >
              <ChevronLeft size={16} aria-hidden="true" />
              이전
            </button>
            <span>
              {page + 1} / {reports.totalPages}
            </span>
            <button
              className="secondary-button"
              type="button"
              disabled={page + 1 >= reports.totalPages || loading}
              onClick={() => setPage((current) => current + 1)}
            >
              다음
              <ChevronRight size={16} aria-hidden="true" />
            </button>
          </nav>
        ) : null}
      </div>
    </section>
  )
}
