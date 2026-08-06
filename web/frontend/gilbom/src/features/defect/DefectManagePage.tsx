import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { DefectFilterBar } from './DefectFilterBar'
import { DefectList } from './DefectList'
import { DefectMap } from './DefectMap'
import { DefectMergeModal } from './DefectMergeModal'
import { DefectSearchBar } from './DefectSearchBar'
import { getDefects } from './defectApi'
import { useDefectRealtime } from './useDefectRealtime'
import { useAuthentication } from '../auth/authenticationContext'
import { DEFECT_MERGE, hasPermission } from '../auth/permissions'
import {
  DEFAULT_WORKFLOW_STATUSES,
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
    // 종료 시각(to)은 화면에서 받지 않으므로 URL에서도 읽지 않는다. 필터 패널에
    // 보이지 않는 상한이 링크에 남아 "왜 안 나오지"가 되는 것을 막는다.
    // 상태 조건이 없으면 첫 진입이다. 종결된 결함은 조치할 것이 없어 지도와
    // 목록을 가리기만 하므로 기본으로는 빼둔다. 사용자가 체크를 전부 풀면
    // writeFilters가 allStatus 표식을 남기고, 그때는 진짜 전체를 보여준다.
    workflowStatuses: params.has('status')
      ? (params.getAll('status') as WorkflowStatus[])
      : (params.has('allStatus') ? [] : DEFAULT_WORKFLOW_STATUSES),
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
  if (filters.workflowStatuses.length === 0) {
    // "아직 안 고름"과 "전부 풀었음"을 구분한다. 이 표식이 없으면 체크를 전부
    // 푼 순간 첫 진입으로 오인돼 기본값이 다시 걸리고, 전체 보기가 불가능해진다.
    params.set('allStatus', '1')
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
  const requestedSort = searchParams.get('sort') ?? DEFAULT_SORT
  // 이전 링크의 내림차순 값도 화면 라벨의 의미대로 오래된 최초 발견부터 정렬한다.
  const sort = requestedSort === 'firstSeenAt,desc'
    ? 'firstSeenAt,asc'
    : requestedSort

  const [selectedDefectId, setSelectedDefectId] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [checkedIds, setCheckedIds] = useState<string[]>([])
  const [merging, setMerging] = useState(false)
  const [mergeNotice, setMergeNotice] = useState<string | null>(null)
  const mapCardRef = useRef<HTMLElement | null>(null)
  /** 늘려서 검색창을 새로 마운트하는 표식. 아래 `key` 주석 참고. */
  const [searchResetToken, setSearchResetToken] = useState(0)

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

  /**
   * 목록에서 고른 결함의 상세 카드는 **지도 위에** 뜬다. 목록은 지도 아래에 있어
   * 행을 눌러도 카드가 화면 밖일 수 있으니 지도까지 올려준다.
   *
   * 지도 마커로 고를 때는 이 함수를 쓰지 않는다. 이미 지도를 보고 있으므로 화면이
   * 움직일 이유가 없다.
   */
  const selectFromList = useCallback((defectId: string) => {
    setSelectedDefectId(defectId)
    // 접근성 설정을 켠 사용자에게 긴 스크롤 애니메이션은 멀미를 준다.
    const reduceMotion = window.matchMedia(
      '(prefers-reduced-motion: reduce)',
    ).matches
    mapCardRef.current?.scrollIntoView({
      behavior: reduceMotion ? 'auto' : 'smooth',
      block: 'start',
    })
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
  const { lastEvent } = useDefectRealtime(refresh)

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
    <section className="page-stack" aria-label="실시간 결함 상태 모니터링">
      {/*
        검색과 조건이 한 줄이다. 검색창은 결함 코드(`INC-20260805-000001`) 한 줄이
        들어갈 만큼만 차지하고, 남는 폭은 조건 알약들이 쓴다.

        key를 바꿔 초기화 때 검색창을 새로 마운트한다. 검색어를 비우는 것만으로는
        부족하다 — 이미 비어 있던 상태에서 무언가 치다가 초기화를 누르면 `value`가
        그대로 ''라 컴포넌트가 변화를 알 수 없고, 디바운스 대기 중이던 입력이
        300ms 뒤에 되살아난다.
      */}
      <div className="content-card defect-toolbar">
        <DefectSearchBar
          key={searchResetToken}
          value={filters.q}
          onChange={(q) => applyFilters({ ...filters, q })}
          disabled={loading && result === null}
        />
        <DefectFilterBar
          filters={filters}
          onChange={applyFilters}
          disabled={loading && result === null}
          onReset={() => setSearchResetToken((token) => token + 1)}
        />
      </div>

      {lastEvent !== null && lastEvent.eventType === 'defect.created' && (
        <p className="defect-toast" role="status">
          새 결함 {lastEvent.incidentCode} ·{' '}
          {DEFECT_TYPE_LABELS[lastEvent.defectType] ?? lastEvent.defectType} ·{' '}
          {RISK_LEVEL_LABELS[lastEvent.riskLevel] ?? lastEvent.riskLevel}
        </p>
      )}
      {/* 조건이 위 툴바로 올라가면서 지도가 가로 폭을 전부 쓴다. 지도는 목록 페이지와
          별개로 뷰포트 bbox 기준의 GeoJSON을 직접 조회한다 — 목록 20건에 묶이지 않고
          화면에 보이는 범위 전체를 그린다. */}
      <DefectMap
        filters={filters}
        selectedDefectId={selectedDefectId}
        onSelect={setSelectedDefectId}
        onChanged={refresh}
        cardRef={mapCardRef}
      />
      {/*
        선택이 없어도 자리는 남겨둔다. 조건부로 렌더하면 체크할 때마다 이 줄이 생겼다
        사라지며 아래 목록이 위아래로 움직여, 방금 누른 행이 손가락 밑에서 달아난다.
        보이지 않을 때는 `visibility: hidden`이라 포커스·스크린리더에서도 빠지지만,
        버튼도 함께 `disabled`로 잠근다.
      */}
      {canMerge && (
        <div
          className={`defect-merge-bar${
            checkedVisible.length === 0 ? ' is-hidden' : ''
          }`}
          role="status"
          aria-hidden={checkedVisible.length === 0}
        >
          <span>{checkedVisible.length}건 선택됨</span>
          <div className="defect-merge-bar-actions">
            <button
              type="button"
              className="secondary-button"
              disabled={checkedVisible.length === 0}
              onClick={() => setCheckedIds([])}
            >
              선택 해제
            </button>
            <button
              type="button"
              className="primary-button"
              // 한 건만 고르면 합칠 상대가 없다. 0건도 여기서 함께 막힌다.
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
        onSelect={selectFromList}
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
