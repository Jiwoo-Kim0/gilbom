import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { DefectFilter } from './DefectFilter'
import { DefectList } from './DefectList'
import { DefectMap } from './DefectMap'
import type { DefectMarker, DefectStatus } from './DefectMap'
import { getDefects } from './defectApi'
import { EMPTY_DEFECT_FILTERS } from './defectTypes'
import type {
  DefectFilters,
  DefectListItem,
  PageResponse,
  RiskLevel,
  WorkflowStatus,
} from './defectTypes'

const PAGE_SIZE = 20
const DEFAULT_SORT = 'lastSeenAt,desc'

/**
 * 필터를 URL query에 실어 둔다. 새로고침해도 조건이 유지되고, 시연 중 특정 조건의
 * 화면을 링크로 공유할 수 있다.
 */
function readFilters(params: URLSearchParams): DefectFilters {
  return {
    ...EMPTY_DEFECT_FILTERS,
    q: params.get('q') ?? '',
    from: params.get('from') ?? '',
    to: params.get('to') ?? '',
    workflowStatuses: params.getAll('status') as WorkflowStatus[],
    defectTypes: params.getAll(
      'type',
    ) as DefectFilters['defectTypes'],
    severities: params.getAll('risk') as RiskLevel[],
    minConfidence: params.has('minConfidence')
      ? Number(params.get('minConfidence'))
      : null,
  }
}

function writeFilters(
  filters: DefectFilters,
  page: number,
  sort: string,
): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.q.length > 0) {
    params.set('q', filters.q)
  }
  if (filters.from.length > 0) {
    params.set('from', filters.from)
  }
  if (filters.to.length > 0) {
    params.set('to', filters.to)
  }
  filters.workflowStatuses.forEach((value) => params.append('status', value))
  filters.defectTypes.forEach((value) => params.append('type', value))
  filters.severities.forEach((value) => params.append('risk', value))
  if (filters.minConfidence !== null) {
    params.set('minConfidence', filters.minConfidence.toFixed(2))
  }
  if (page > 0) {
    params.set('page', String(page))
  }
  if (sort !== DEFAULT_SORT) {
    params.set('sort', sort)
  }
  return params
}

/** 지도 범례는 세 가지 색만 쓰므로 6개 상태를 여기에 접어 넣는다. */
function toMarkerStatus(status: WorkflowStatus): DefectStatus {
  switch (status) {
    case 'IN_PROGRESS':
      return 'progress'
    case 'RESOLVED':
    case 'FALSE_POSITIVE':
    case 'REJECTED':
      return 'done'
    default:
      return 'received'
  }
}

function toMarkers(items: DefectListItem[]): DefectMarker[] {
  return items
    .filter((defect) => defect.location !== null)
    .map((defect) => ({
      id: defect.defectId,
      lat: defect.location!.latitude,
      lng: defect.location!.longitude,
      status: toMarkerStatus(defect.workflowStatus),
    }))
}

export function DefectManagePage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const filters = useMemo(() => readFilters(searchParams), [searchParams])
  const page = Number(searchParams.get('page') ?? '0')
  const sort = searchParams.get('sort') ?? DEFAULT_SORT

  const [selectedDefectId, setSelectedDefectId] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  /**
   * 조회 조건 하나로 요약한 키다. 로딩 여부를 state로 따로 두지 않고 "마지막으로
   * 응답을 받은 키가 지금 조건과 같은가"로 판단한다. effect 본문에서 동기로
   * setState를 호출하지 않게 되고, 갱신 중에도 이전 목록이 남아 화면이 깜빡이지 않는다.
   */
  const requestKey = useMemo(
    () => JSON.stringify({ filters, page, sort, reloadToken }),
    [filters, page, sort, reloadToken],
  )

  const [snapshot, setSnapshot] = useState<{
    key: string
    result: PageResponse<DefectListItem> | null
    error: string | null
  } | null>(null)

  const loading = snapshot?.key !== requestKey
  const result = snapshot?.result ?? null
  const error = snapshot?.key === requestKey ? snapshot.error : null

  useEffect(() => {
    let cancelled = false

    getDefects(page, PAGE_SIZE, sort, filters)
      .then((response) => {
        if (!cancelled) {
          setSnapshot({ key: requestKey, result: response, error: null })
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setSnapshot({
            key: requestKey,
            result: null,
            error: getApiErrorMessage(cause),
          })
        }
      })

    return () => {
      cancelled = true
    }
  }, [filters, page, sort, requestKey])

  const applyFilters = useCallback(
    (next: DefectFilters) => {
      // 조건이 바뀌면 현재 페이지가 범위를 벗어날 수 있어 첫 페이지로 되돌린다.
      setSearchParams(writeFilters(next, 0, sort), { replace: true })
    },
    [setSearchParams, sort],
  )

  const changeSort = useCallback(
    (next: string) => {
      setSearchParams(writeFilters(filters, 0, next), { replace: true })
    },
    [filters, setSearchParams],
  )

  const changePage = useCallback(
    (next: number) => {
      setSearchParams(writeFilters(filters, next, sort))
    },
    [filters, setSearchParams, sort],
  )

  const markers = useMemo(
    () => toMarkers(result?.content ?? []),
    [result],
  )

  return (
    <section className="page-stack" aria-labelledby="defect-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">DEFECTS</p>
          <h2 id="defect-page-title">실시간 결함 상태 모니터링</h2>
          <p>시스템에서 감지된 최신 결함 목록 및 위치 현황입니다.</p>
        </div>
      </div>
      <div className="defect-layout">
        <DefectFilter
          filters={filters}
          onChange={applyFilters}
          disabled={loading && result === null}
        />
        <DefectMap markers={markers} />
      </div>
      <DefectList
        page={result}
        loading={loading}
        error={error}
        sort={sort}
        selectedDefectId={selectedDefectId}
        onSortChange={changeSort}
        onPageChange={changePage}
        onSelect={setSelectedDefectId}
        onRetry={() => setReloadToken((token) => token + 1)}
      />
    </section>
  )
}
