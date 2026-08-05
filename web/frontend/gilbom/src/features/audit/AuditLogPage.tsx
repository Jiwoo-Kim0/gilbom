import {
  FileClock,
  Filter,
  RotateCcw,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { getAuditLogs } from './auditApi'
import type {
  AuditLogFilters,
  AuditLogItem,
  AuditLogPageResponse,
} from './auditTypes'

const emptyFilters: AuditLogFilters = {
  actionType: '',
  targetType: '',
  result: '',
  createdFrom: '',
  createdTo: '',
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'medium',
  }).format(new Date(value))
}

function formatSnapshot(value: Record<string, unknown> | null): string {
  return value === null ? '없음' : JSON.stringify(value, null, 2)
}

function AuditDetails({ item }: { item: AuditLogItem }) {
  return (
    <details className="audit-details">
      <summary>변경 값 보기</summary>
      <div className="audit-snapshot-grid">
        <div>
          <strong>변경 전</strong>
          <pre>{formatSnapshot(item.beforeValue)}</pre>
        </div>
        <div>
          <strong>변경 후</strong>
          <pre>{formatSnapshot(item.afterValue)}</pre>
        </div>
      </div>
      <dl className="audit-meta-list">
        <div>
          <dt>Request ID</dt>
          <dd>{item.requestId ?? '없음'}</dd>
        </div>
        <div>
          <dt>IP</dt>
          <dd>{item.ipAddress ?? '기록 없음'}</dd>
        </div>
        <div>
          <dt>User-Agent</dt>
          <dd>{item.userAgent ?? '기록 없음'}</dd>
        </div>
      </dl>
    </details>
  )
}

export function AuditLogPage() {
  const requestedQueryRef = useRef<string | null>(null)
  const [draftFilters, setDraftFilters] = useState<AuditLogFilters>(emptyFilters)
  const [appliedFilters, setAppliedFilters] = useState<AuditLogFilters>(emptyFilters)
  const [logs, setLogs] = useState<AuditLogPageResponse | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async (
    targetPage: number,
    filters: AuditLogFilters,
  ) => {
    try {
      setLogs(await getAuditLogs(targetPage, 20, filters))
    } catch (loadError) {
      setError(loadError)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    const queryKey = JSON.stringify([page, appliedFilters])
    if (requestedQueryRef.current === queryKey) {
      return
    }
    requestedQueryRef.current = queryKey
    void load(page, appliedFilters)
  }, [appliedFilters, load, page])

  function handleFilter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (
      draftFilters.createdFrom.length > 0
      && draftFilters.createdTo.length > 0
      && new Date(draftFilters.createdFrom) > new Date(draftFilters.createdTo)
    ) {
      setError(new Error('조회 시작 시각은 종료 시각보다 늦을 수 없습니다.'))
      return
    }
    setLoading(true)
    setError(null)
    requestedQueryRef.current = null
    setPage(0)
    setAppliedFilters({ ...draftFilters })
  }

  function resetFilters() {
    setDraftFilters(emptyFilters)
    setLoading(true)
    setError(null)
    requestedQueryRef.current = null
    setPage(0)
    setAppliedFilters({ ...emptyFilters })
  }

  function changePage(targetPage: number) {
    setLoading(true)
    setError(null)
    setPage(targetPage)
  }

  return (
    <section className="page-stack" aria-labelledby="audit-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">SECURITY AUDIT</p>
          <h2 id="audit-page-title">감사 로그</h2>
          <p>주요 보안 이벤트와 관리자 변경 이력을 append-only 원본에서 조회합니다.</p>
        </div>
      </div>

      <form className="content-card audit-filter-grid" onSubmit={handleFilter}>
        <label className="admin-form-field" htmlFor="audit-action">
          작업 유형
          <input
            id="audit-action"
            value={draftFilters.actionType}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              actionType: event.target.value.toUpperCase(),
            }))}
            pattern="^[A-Z][A-Z0-9_]{2,79}$"
            maxLength={80}
            placeholder="USER_ROLE_CHANGED"
          />
        </label>
        <label className="admin-form-field" htmlFor="audit-target">
          대상 유형
          <input
            id="audit-target"
            value={draftFilters.targetType}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              targetType: event.target.value.toUpperCase(),
            }))}
            pattern="^[A-Z][A-Z0-9_]{1,49}$"
            maxLength={50}
            placeholder="USER"
          />
        </label>
        <label className="admin-form-field" htmlFor="audit-result">
          처리 결과
          <select
            id="audit-result"
            value={draftFilters.result}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              result: event.target.value as AuditLogFilters['result'],
            }))}
          >
            <option value="">전체</option>
            <option value="SUCCESS">성공</option>
            <option value="FAILURE">실패</option>
            <option value="DENIED">거부</option>
          </select>
        </label>
        <label className="admin-form-field" htmlFor="audit-from">
          시작 시각
          <input
            id="audit-from"
            type="datetime-local"
            value={draftFilters.createdFrom}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              createdFrom: event.target.value,
            }))}
          />
        </label>
        <label className="admin-form-field" htmlFor="audit-to">
          종료 시각
          <input
            id="audit-to"
            type="datetime-local"
            value={draftFilters.createdTo}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              createdTo: event.target.value,
            }))}
          />
        </label>
        <div className="audit-filter-actions">
          <button className="secondary-button" type="button" onClick={resetFilters}>
            <RotateCcw size={16} />
            초기화
          </button>
          <button className="primary-button" type="submit">
            <Filter size={16} />
            조회
          </button>
        </div>
      </form>

      {error ? (
        <div className="page-alert" role="alert">
          {getApiErrorMessage(error)}
        </div>
      ) : null}

      <div className="content-card audit-card">
        <div className="table-heading">
          <div>
            <h3>이벤트 목록</h3>
            <p>이 조회 작업 자체도 <code>AUDIT_LOG_VIEWED</code> 이벤트로 기록됩니다.</p>
          </div>
          <span className="status-badge">{logs?.totalElements ?? 0}건</span>
        </div>

        {loading && logs === null ? (
          <div className="admin-loading" role="status">
            <span className="loading-spinner" aria-hidden="true" />
            감사 로그를 불러오는 중입니다.
          </div>
        ) : logs?.content.length === 0 ? (
          <div className="empty-state audit-empty">
            <span className="empty-state-icon" aria-hidden="true">
              <FileClock size={22} />
            </span>
            <h3>조건에 맞는 감사 로그가 없습니다.</h3>
            <p>필터 범위를 변경하거나 초기화한 뒤 다시 조회해주세요.</p>
          </div>
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table audit-table">
              <thead>
                <tr>
                  <th scope="col">발생 시각</th>
                  <th scope="col">작업</th>
                  <th scope="col">대상</th>
                  <th scope="col">작업자</th>
                  <th scope="col">결과</th>
                  <th scope="col">사유·상세</th>
                </tr>
              </thead>
              <tbody>
                {logs?.content.map((item) => (
                  <tr key={item.auditId}>
                    <td>
                      <time dateTime={item.createdAt}>
                        {formatDateTime(item.createdAt)}
                      </time>
                      <small>#{item.auditId}</small>
                    </td>
                    <td>
                      <strong>{item.actionType}</strong>
                      <small>{item.actorType}</small>
                    </td>
                    <td>
                      <strong>{item.targetType}</strong>
                      <small>{item.targetId ?? '식별자 없음'}</small>
                    </td>
                    <td>
                      <span className="audit-identifier">
                        {item.actorUserId ?? 'SYSTEM'}
                      </span>
                    </td>
                    <td>
                      <span className={`audit-result audit-result-${item.result.toLowerCase()}`}>
                        {item.result}
                      </span>
                    </td>
                    <td>
                      <p>{item.reason ?? '사유 없음'}</p>
                      <AuditDetails item={item} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="pagination" aria-label="감사 로그 페이지">
          <button
            className="secondary-button"
            type="button"
            onClick={() => changePage(Math.max(0, page - 1))}
            disabled={page === 0 || loading}
          >
            이전
          </button>
          <span>{page + 1} / {Math.max(logs?.totalPages ?? 1, 1)}</span>
          <button
            className="secondary-button"
            type="button"
            onClick={() => changePage(page + 1)}
            disabled={
              loading
              || logs === null
              || page + 1 >= logs.totalPages
            }
          >
            다음
          </button>
        </div>
      </div>
    </section>
  )
}
