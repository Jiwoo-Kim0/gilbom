import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { DefectFilter } from './DefectFilter'
import { DefectList } from './DefectList'
import { DefectMap } from './DefectMap'
import { DefectMergeModal } from './DefectMergeModal'
import { getDefects } from './defectApi'
import { useDefectRealtime } from './useDefectRealtime'
import { useAuthentication } from '../auth/authenticationContext'
import { DEFECT_MERGE, hasPermission } from '../auth/permissions'
import {
  DEFECT_TYPE_LABELS,
  EMPTY_DEFECT_FILTERS,
  RISK_LEVEL_LABELS,
} from './defectTypes'
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

export function DefectManagePage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const filters = useMemo(() => readFilters(searchParams), [searchParams])
  const page = Number(searchParams.get('page') ?? '0')
  const sort = searchParams.get('sort') ?? DEFAULT_SORT

  const [selectedDefectId, setSelectedDefectId] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [checkedIds, setCheckedIds] = useState<string[]>([])
  const [merging, setMerging] = useState(false)
  const [mergeNotice, setMergeNotice] = useState<string | null>(null)

  const { user } = useAuthentication()
  const canMerge = hasPermission(user?.permissions ?? [], DEFECT_MERGE)

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

  const refresh = useCallback(() => {
    setReloadToken((token) => token + 1)
  }, [])

  const toggleCheck = useCallback((defectId: string) => {
    setCheckedIds((previous) =>
      previous.includes(defectId)
        ? previous.filter((value) => value !== defectId)
        : [...previous, defectId],
    )
  }, [])

  // 페이지·필터가 바뀌면 화면에 없는 결함이 선택으로 남는다. 보이는 것만 유지한다.
  const visibleIds = result?.content.map((defect) => defect.defectId) ?? []
  const checkedVisible = checkedIds.filter((defectId) =>
    visibleIds.includes(defectId),
  )
  const checkedDefects = (result?.content ?? []).filter((defect) =>
    checkedVisible.includes(defect.defectId),
  )

  // 실시간 알림은 "다시 읽어라"만 알려준다. 데이터는 목록·지도가 각자 다시 읽는다.
  const { status: streamStatus, lastEvent } = useDefectRealtime(refresh)

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

  return (
    <section className="page-stack" aria-labelledby="defect-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">DEFECTS</p>
          <h2 id="defect-page-title">실시간 결함 상태 모니터링</h2>
          <p>시스템에서 감지된 최신 결함 목록 및 위치 현황입니다.</p>
        </div>
        {/*
          연결이 끊긴 채 조용히 있으면 "결함이 없다"로 오해한다. 상태를 항상 보여준다.
        */}
        <p
          className={`defect-stream-status defect-stream-${streamStatus}`}
          role="status"
        >
          {streamStatus === 'connected' && '실시간 연결됨'}
          {streamStatus === 'connecting' && '실시간 연결 중…'}
          {streamStatus === 'reconnecting' && '연결이 끊겨 재연결 중…'}
        </p>
      </div>

      {lastEvent !== null && lastEvent.eventType === 'defect.created' && (
        <p className="defect-toast" role="status">
          새 결함 {lastEvent.incidentCode} ·{' '}
          {DEFECT_TYPE_LABELS[lastEvent.defectType] ?? lastEvent.defectType} ·{' '}
          {RISK_LEVEL_LABELS[lastEvent.riskLevel] ?? lastEvent.riskLevel}
        </p>
      )}
      <div className="defect-layout">
        <DefectFilter
          filters={filters}
          onChange={applyFilters}
          disabled={loading && result === null}
        />
        {/* 지도는 목록 페이지와 별개로 뷰포트 bbox 기준의 GeoJSON을 직접 조회한다.
            목록 20건에 묶이지 않고 화면에 보이는 범위 전체를 그린다. */}
        <DefectMap
          filters={filters}
          selectedDefectId={selectedDefectId}
          onSelect={setSelectedDefectId}
          onChanged={refresh}
        />
      </div>
      {canMerge && checkedVisible.length > 0 && (
        <div className="defect-merge-bar" role="status">
          <span>{checkedVisible.length}건 선택됨</span>
          <div className="defect-merge-bar-actions">
            <button
              type="button"
              className="secondary-button"
              onClick={() => setCheckedIds([])}
            >
              선택 해제
            </button>
            <button
              type="button"
              className="primary-button"
              // 한 건만 고르면 합칠 상대가 없다.
              disabled={checkedVisible.length < 2}
              onClick={() => setMerging(true)}
            >
              병합
            </button>
          </div>
        </div>
      )}

      {mergeNotice !== null && (
        <p className="defect-toast" role="status">
          {mergeNotice}
        </p>
      )}

      <DefectList
        page={result}
        loading={loading}
        error={error}
        sort={sort}
        selectedDefectId={selectedDefectId}
        selectable={canMerge}
        checkedIds={checkedVisible}
        onSortChange={changeSort}
        onPageChange={changePage}
        onSelect={setSelectedDefectId}
        onToggleCheck={toggleCheck}
        onRetry={() => setReloadToken((token) => token + 1)}
      />

      {merging && (
        <DefectMergeModal
          defects={checkedDefects}
          onClose={() => setMerging(false)}
          onMerged={(mergeResult) => {
            setCheckedIds([])
            // 흡수된 결함이 열려 있으면 목록에서 사라지므로 상세도 닫는다.
            if (
              selectedDefectId !== null
              && mergeResult.mergedDefectIds.includes(selectedDefectId)
            ) {
              setSelectedDefectId(mergeResult.targetDefectId)
            }
            setMergeNotice(
              `${mergeResult.mergedDefectIds.length}건을 병합했습니다. `
              + `대표 결함의 관측은 ${mergeResult.observationCount}회입니다.`,
            )
            refresh()
          }}
        />
      )}
    </section>
  )
}
