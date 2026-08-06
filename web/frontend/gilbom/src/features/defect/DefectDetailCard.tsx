import { useCallback, useEffect, useRef, useState } from 'react'
import type {
  KeyboardEvent as ReactKeyboardEvent,
  PointerEvent as ReactPointerEvent,
} from 'react'
import { useNavigate } from 'react-router'
import { GripHorizontal, X } from 'lucide-react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { BoundingBoxOverlay } from './DefectBoundingBox'
import {
  cardPositionClasses,
  cardPositionFromDrag,
  cardPositionFromKey,
} from './defectCardDrag'
import type {
  DefectCardDragGeometry,
  DefectCardPosition,
} from './defectCardDrag'
import { DefectEditModal } from './DefectEditModal'
import { DefectPhotoViewer } from './DefectPhotoViewer'
import {
  createReport,
  ReportCreateAttempt,
} from '../report/reportApi'
import { useAuthentication } from '../auth/authenticationContext'
import {
  DEFECT_STATUS_UPDATE,
  REPORT_CREATE,
  hasPermission,
} from '../auth/permissions'
import {
  changeDefectStatus,
  getDefect,
  getDefectHistory,
  getDefectImageUrl,
  getDefectObservations,
} from './defectApi'
import {
  allowedTransitions,
  DEFECT_TYPE_LABELS,
  isTerminal,
  RESOLUTIONS,
  RESOLUTION_LABELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type {
  DefectDetail,
  DefectHistoryItem,
  DefectImageUrl,
  DefectObservation,
  PageResponse,
  Resolution,
  WorkflowStatus,
} from './defectTypes'

/**
 * 결함이 처음 발견된 **그날 하루**를 보고서 기간으로 쓴다.
 *
 * <p>결함의 `firstSeenAt ~ lastSeenAt`을 그대로 쓸 수 없다. 관측이 1회인 결함은 둘이
 * 같은데 서버가 `from < to`를 강제하기 때문이다. 1초짜리 구간으로 억지로 맞추면
 * 통계가 전부 0이 되어, 통계를 기간 전체로 두는 취지가 무너진다.
 *
 * <p>하루 경계는 보는 사람의 시간대로 자른다. "그날"이 달력상 하루여야 말이 된다.
 */
function reportPeriodFor(firstSeenAt: string): { from: string; to: string } {
  const seen = new Date(firstSeenAt)
  const from = new Date(seen)
  from.setHours(0, 0, 0, 0)
  const to = new Date(from)
  to.setDate(to.getDate() + 1)
  return { from: from.toISOString(), to: to.toISOString() }
}

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

type CardTab = 'info' | 'observations' | 'history'

/**
 * 이력 탭은 열었을 때만 부른다. 카드를 띄울 때마다 세 번 조회하지 않는다.
 *
 * <p>`loading`을 따로 두지 않는 이유는 effect 본문에서 동기로 setState를 하지 않기
 * 위해서다(eslint `react-hooks/set-state-in-effect`). 탭이 열려 있는데 아직 `idle`이면
 * 조회 중이라는 뜻이다.
 */
type HistoryState<T> =
  | { kind: 'idle' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; page: PageResponse<T> }

function formatShortDateTime(value: string): string {
  return new Date(value).toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * 변경 이력의 before/after snapshot에서 실제로 바뀐 항목만 뽑는다.
 * 서버가 전체 snapshot을 주므로 그대로 보여주면 무엇이 바뀌었는지 알기 어렵다.
 */
function changedFields(
  before: Record<string, unknown> | null,
  after: Record<string, unknown> | null,
): { key: string; from: string; to: string }[] {
  if (after === null) {
    return []
  }
  const format = (value: unknown) =>
    value === null || value === undefined ? '—' : String(value)
  return Object.keys(after)
    .filter((key) => format(before?.[key]) !== format(after[key]))
    .map((key) => ({
      key,
      from: format(before?.[key]),
      to: format(after[key]),
    }))
}

interface DefectDetailCardProps {
  defectId: string
  onClose: () => void
  /** 상태를 바꾸면 목록·지도를 다시 읽어야 한다. */
  onChanged: () => void
  /** 병합된 결함에서 대표 결함으로 이동할 때 쓴다. */
  onSelectDefect: (defectId: string) => void
  /**
   * 끌어 옮긴 자리. `null`이면 기본 자리(오른쪽 위)다.
   *
   * <p>이 값을 카드 자기 state로 두지 않는 이유는, 다른 결함을 고르면 상위가 `key`로
   * 카드를 새로 마운트하기 때문이다. 옆으로 밀어둔 카드가 결함을 바꿀 때마다 제자리로
   * 튀어오르면 밀어둔 뜻이 없다.
   */
  position?: DefectCardPosition | null
  /**
   * 끌기를 허용하고 새 자리를 알린다. 넘기지 않으면 **끌 수 없다** — 손잡이도 그리지
   * 않는다. 지도 위에 얹힌 카드에서만 뜻이 있는 동작이다.
   */
  onMove?: (position: DefectCardPosition) => void
}

export function DefectDetailCard({
  defectId,
  onClose,
  onChanged,
  onSelectDefect,
  position = null,
  onMove,
}: DefectDetailCardProps) {
  const navigate = useNavigate()
  const { user } = useAuthentication()
  const permissions = user?.permissions ?? []
  const canUpdate = hasPermission(permissions, DEFECT_STATUS_UPDATE)
  const canCreateReport = hasPermission(permissions, REPORT_CREATE)

  const cardRef = useRef<HTMLElement | null>(null)
  /** 끌기 시작 시점의 좌표·크기. 끌는 동안 다시 재지 않는다. */
  const dragRef = useRef<{
    pointerId: number
    startX: number
    startY: number
    geometry: DefectCardDragGeometry
  } | null>(null)

  const [detail, setDetail] = useState<DefectDetail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [image, setImage] = useState<ImageState>({ kind: 'loading' })
  const [photoOpen, setPhotoOpen] = useState(false)

  const [tab, setTab] = useState<CardTab>('info')
  const [observations, setObservations] = useState<
    HistoryState<DefectObservation>
  >({ kind: 'idle' })
  const [history, setHistory] = useState<HistoryState<DefectHistoryItem>>({
    kind: 'idle',
  })

  const [nextStatus, setNextStatus] = useState<WorkflowStatus | ''>('')
  const [resolution, setResolution] = useState<Resolution | ''>('')
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [reportError, setReportError] = useState<string | null>(null)
  // 두 번 눌러도 보고서가 하나만 생기도록 멱등 키를 유지한다.
  const reportAttemptRef = useRef(new ReportCreateAttempt())

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

  // 탭을 처음 열 때만 조회한다. 상태를 바꾸면 이력이 달라지므로 그때 다시 읽는다.
  useEffect(() => {
    if (tab !== 'observations' || observations.kind !== 'idle') return
    let cancelled = false

    getDefectObservations(defectId)
      .then((page) => {
        if (!cancelled) setObservations({ kind: 'ready', page })
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setObservations({ kind: 'error', message: getApiErrorMessage(cause) })
        }
      })

    return () => {
      cancelled = true
    }
  }, [defectId, tab, observations.kind])

  useEffect(() => {
    if (tab !== 'history' || history.kind !== 'idle') return
    let cancelled = false

    getDefectHistory(defectId)
      .then((page) => {
        if (!cancelled) setHistory({ kind: 'ready', page })
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setHistory({ kind: 'error', message: getApiErrorMessage(cause) })
        }
      })

    return () => {
      cancelled = true
    }
  }, [defectId, tab, history.kind])

  const defect = detail?.defect ?? null
  const allowed: WorkflowStatus[] = defect
    ? allowedTransitions(defect.workflowStatus)
    : []
  // 수정을 막는 사유. null이면 수정할 수 있다. 서버 requireChangeable와 같은 규칙이라
  // 여기서 걸러내면 저장 눌러서 409를 받는 일이 없다.
  const mergedInto = detail?.mergedIntoDefectId ?? null
  const notEditable: string | null = (() => {
    if (defect === null) return '불러오는 중입니다.'
    if (mergedInto !== null) {
      return '병합된 결함은 대표 결함에서 수정해야 합니다.'
    }
    if (isTerminal(defect.workflowStatus)) {
      // 상태 전이는 열려 있으므로 되살린 뒤 고칠 수 있다. 막다른 길처럼 보이지 않게
      // 다음 행동을 함께 알려준다.
      return '종결된 결함은 처리 상태를 먼저 바꿔야 수정할 수 있습니다.'
    }
    return null
  })()
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
        // 상태가 바뀌면 변경 이력이 늘어난다. 다음에 탭을 열 때 다시 읽게 한다.
        setHistory({ kind: 'idle' })
        onChanged()
      })
      .catch((cause: unknown) => {
        setSubmitError(getApiErrorMessage(cause))
      })
      .finally(() => setSubmitting(false))
  }

  /**
   * 지금 그려진 카드와 그것을 담은 지도 영역의 크기. **끌기를 시작할 때와 방향키를
   * 누른 순간에만** 잰다 — 움직이는 동안 다시 재면 자기 위치 변화가 다음 계산에
   * 섞여 카드가 손끝에서 미끄러진다.
   */
  const measureDrag = useCallback((): DefectCardDragGeometry | null => {
    const card = cardRef.current
    // 카드는 `.defect-map-canvas-wrap`(position: relative) 안에 절대 배치된다.
    const container = card?.offsetParent
    if (card == null || !(container instanceof HTMLElement)) {
      return null
    }
    const cardRect = card.getBoundingClientRect()
    const containerRect = container.getBoundingClientRect()
    return {
      originLeft: cardRect.left - containerRect.left,
      originTop: cardRect.top - containerRect.top,
      containerWidth: containerRect.width,
      containerHeight: containerRect.height,
      cardWidth: cardRect.width,
      cardHeight: cardRect.height,
    }
  }, [])

  const startDrag = (event: ReactPointerEvent<HTMLElement>) => {
    // 왼쪽 버튼·터치·펜만. 가운데 버튼 끌기는 브라우저의 자동 스크롤이다.
    if (onMove === undefined || event.button !== 0) {
      return
    }
    /*
     * 헤더 안의 조작 요소(닫기 버튼)에서 시작한 것은 끌기가 아니다. 손잡이는 예외로
     * 둔다 — 그것으로 끄는 것이 가장 자연스러운 동작이다.
     */
    const target = event.target as HTMLElement
    const interactive = target.closest(
      'button:not(.defect-card-grip), a, input, select, textarea',
    )
    if (interactive !== null) {
      return
    }
    const geometry = measureDrag()
    if (geometry === null) {
      return
    }
    dragRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      geometry,
    }
    // 포인터를 잡아두면 카드 밖으로 빠르게 끌어도 move가 계속 들어온다.
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  const continueDrag = (event: ReactPointerEvent<HTMLElement>) => {
    const drag = dragRef.current
    if (
      drag === null
      || drag.pointerId !== event.pointerId
      || onMove === undefined
    ) {
      return
    }
    onMove(
      cardPositionFromDrag(
        drag.geometry,
        event.clientX - drag.startX,
        event.clientY - drag.startY,
      ),
    )
  }

  const endDrag = (event: ReactPointerEvent<HTMLElement>) => {
    if (dragRef.current?.pointerId !== event.pointerId) {
      return
    }
    dragRef.current = null
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  /** 방향키로도 옮긴다. 끌기는 포인터가 있는 사람만 쓸 수 있다. */
  const moveByKey = (event: ReactKeyboardEvent<HTMLButtonElement>) => {
    if (onMove === undefined) {
      return
    }
    const geometry = measureDrag()
    if (geometry === null) {
      return
    }
    // 아직 끌어본 적이 없으면 기본 자리를 백분율로 환산해 그 자리에서 출발한다.
    const current = position ?? {
      x: Math.round((geometry.originLeft / geometry.containerWidth) * 100),
      y: Math.round((geometry.originTop / geometry.containerHeight) * 100),
    }
    const next = cardPositionFromKey(current, event.key, geometry)
    if (next === null) {
      return
    }
    // 방향키의 기본 동작은 페이지 스크롤이다. 카드를 옮길 때는 막는다.
    event.preventDefault()
    onMove(next)
  }

  const draggable = onMove !== undefined

  return (
    <aside
      className={`defect-card ${cardPositionClasses(position)}`.trimEnd()}
      aria-label="결함 상세"
      ref={cardRef}
    >
      <header
        className={`defect-card-header${
          draggable ? ' defect-card-header-draggable' : ''
        }`}
        onPointerDown={draggable ? startDrag : undefined}
        onPointerMove={draggable ? continueDrag : undefined}
        onPointerUp={draggable ? endDrag : undefined}
        onPointerCancel={draggable ? endDrag : undefined}
      >
        {draggable && (
          <button
            type="button"
            className="defect-card-grip"
            aria-label="카드 위치 옮기기 — 끌거나 방향키를 누르세요"
            title="끌어서 옮기기"
            onKeyDown={moveByKey}
          >
            <GripHorizontal size={14} aria-hidden="true" />
          </button>
        )}
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
              /*
               * 카드 안 사진은 340px 폭이라 파손 부위를 눈으로 가리기 어렵다. 눌러서
               * 크게 보는 창을 연다. button으로 감싸 키보드로도 열 수 있게 한다.
               */
              <button
                type="button"
                className="defect-card-photo-button"
                aria-label="증거 사진 크게 보기"
                onClick={() => setPhotoOpen(true)}
              >
                <figure className="defect-card-figure">
                  <img src={image.image.imageUrl} alt="결함 증거 이미지" />
                  <BoundingBoxOverlay bboxNorm={image.image.bboxNorm} />
                </figure>
              </button>
            )}
          </div>
          {image.kind === 'ready' && image.image.bboxNorm !== null && (
            <p className="defect-card-bbox-caption">
              노란 상자는 AI가 결함으로 판단한 영역입니다.
            </p>
          )}

          <div className="defect-card-tabs" role="tablist">
            {(
              [
                ['info', '정보'],
                ['observations', `관측 ${defect.observationCount}`],
                ['history', '변경 이력'],
              ] as [CardTab, string][]
            ).map(([value, label]) => (
              <button
                key={value}
                type="button"
                role="tab"
                aria-selected={tab === value}
                className={`defect-card-tab${tab === value ? ' is-selected' : ''}`}
                onClick={() => setTab(value)}
              >
                {label}
              </button>
            ))}
          </div>

          {tab === 'observations' && (
            <div className="defect-card-timeline">
              {observations.kind === 'idle' && <p>불러오는 중…</p>}
              {observations.kind === 'error' && (
                <p className="defect-card-error" role="alert">
                  {observations.message}
                </p>
              )}
              {observations.kind === 'ready'
                && observations.page.content.length === 0 && (
                <p className="defect-card-terminal">관측 기록이 없습니다.</p>
              )}
              {observations.kind === 'ready'
                && observations.page.content.map((observation) => (
                  <div key={observation.eventId} className="defect-timeline-row">
                    <div className="defect-timeline-head">
                      <strong>
                        {formatShortDateTime(
                          observation.evidenceCapturedAt
                            ?? observation.createdAt,
                        )}
                      </strong>
                      {observation.representative && (
                        <em className="defect-review-flag">대표</em>
                      )}
                    </div>
                    <p>
                      신뢰도{' '}
                      {observation.confidence === null
                        ? '—'
                        : `${Math.round(observation.confidence * 100)}%`}
                      {observation.damageRatio !== null
                        && ` · 파손 ${Math.round(observation.damageRatio * 100)}%`}
                      {observation.imageId === null && ' · 이미지 없음'}
                    </p>
                    <p className="defect-card-coord">
                      {observation.robotName ?? '이름 없는 로봇'}
                      {' 탐지'}
                    </p>
                    {observation.modelName !== null && (
                      <p className="defect-card-coord">
                        {observation.modelName}
                        {observation.modelVersion !== null
                          && ` v${observation.modelVersion}`}
                      </p>
                    )}
                  </div>
                ))}
              {observations.kind === 'ready'
                && observations.page.totalElements
                  > observations.page.content.length && (
                <p className="defect-card-coord">
                  외 {observations.page.totalElements
                    - observations.page.content.length}건 더 있음
                </p>
              )}
            </div>
          )}

          {tab === 'history' && (
            <div className="defect-card-timeline">
              {history.kind === 'idle' && <p>불러오는 중…</p>}
              {history.kind === 'error' && (
                <p className="defect-card-error" role="alert">
                  {history.message}
                </p>
              )}
              {history.kind === 'ready' && history.page.content.length === 0 && (
                <p className="defect-card-terminal">
                  아직 변경 이력이 없습니다.
                </p>
              )}
              {history.kind === 'ready'
                && history.page.content.map((item) => (
                  <div key={item.historyId} className="defect-timeline-row">
                    <div className="defect-timeline-head">
                      <strong>{formatShortDateTime(item.createdAt)}</strong>
                      <em className="defect-card-coord">{item.changeType}</em>
                    </div>
                    {item.beforeStatus !== null && item.afterStatus !== null && (
                      <p>
                        {WORKFLOW_STATUS_LABELS[item.beforeStatus]
                          ?? item.beforeStatus}
                        {' → '}
                        {WORKFLOW_STATUS_LABELS[item.afterStatus]
                          ?? item.afterStatus}
                      </p>
                    )}
                    {changedFields(item.beforeValues, item.afterValues).map(
                      (change) => (
                        <p key={change.key} className="defect-card-coord">
                          {change.key}: {change.from} → {change.to}
                        </p>
                      ),
                    )}
                    {item.reason !== null && (
                      <p className="defect-card-reason">{item.reason}</p>
                    )}
                  </div>
                ))}
            </div>
          )}

          <dl
            className={`defect-card-facts${tab === 'info' ? '' : ' is-hidden'}`}
          >
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
            {detail !== null && detail.mergedDefectIds.length > 0 && (
              <div>
                <dt>병합</dt>
                <dd>
                  다른 결함 {detail.mergedDefectIds.length}건을 흡수한 대표 결함
                  <span className="defect-card-coord">
                    관측 {defect.observationCount}회는 흡수된 결함까지 합산한
                    값입니다.
                  </span>
                </dd>
              </div>
            )}
            {mergedInto !== null && (
              <div>
                <dt>병합</dt>
                <dd>
                  다른 결함으로 병합됨
                  <button
                    type="button"
                    className="defect-card-link"
                    onClick={() => onSelectDefect(mergedInto)}
                  >
                    대표 결함 열기
                  </button>
                </dd>
              </div>
            )}
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
            {canUpdate && (
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

            {canUpdate && (
              <button
                type="button"
                className="secondary-button"
                // 서버 requireChangeable와 같은 조건이다. 종결·병합된 결함은
                // 저장 시점에 409가 나므로 애초에 열지 않는다.
                disabled={notEditable !== null}
                title={notEditable ?? undefined}
                onClick={() => setEditing(true)}
              >
                결함 수정
              </button>
            )}

            {canCreateReport && (
              <>
                {reportError !== null && (
                  <p className="defect-card-error" role="alert">
                    {reportError}
                  </p>
                )}
                <button
                  type="button"
                  className="secondary-button"
                  disabled={generating}
                  onClick={() => {
                    // 중간 폼 없이 바로 만든다. 서버가 filterCriteria.defectId로
                    // 근거를 이 결함에 고정하고, 통계는 그날 하루를 집계한다.
                    setGenerating(true)
                    setReportError(null)
                    const { from, to } = reportPeriodFor(defect.firstSeenAt)
                    createReport(
                      {
                        generationMethod: 'LLM',
                        reportType: 'PERIODIC',
                        periodFrom: from,
                        periodTo: to,
                        filterCriteria: { defectId: defect.defectId },
                      },
                      reportAttemptRef.current,
                    )
                      .then((report) => {
                        navigate(
                          `/reports/${encodeURIComponent(report.reportId)}`,
                          { state: { notice: '보고서를 생성했습니다.' } },
                        )
                      })
                      .catch((cause: unknown) => {
                        setReportError(getApiErrorMessage(cause))
                        setGenerating(false)
                      })
                  }}
                >
                  {generating ? '보고서 생성 중…' : '보고서 생성'}
                </button>
                {generating && (
                  <p className="defect-card-coord">
                    AI가 결함 정보와 사진을 읽고 있습니다. 최대 30초 걸립니다.
                  </p>
                )}
              </>
            )}
          </div>

          {photoOpen && image.kind === 'ready' && (
            <DefectPhotoViewer
              imageUrl={image.image.imageUrl}
              bboxNorm={image.image.bboxNorm}
              incidentCode={defect.incidentCode}
              onClose={() => setPhotoOpen(false)}
            />
          )}

          {editing && (
            <DefectEditModal
              defect={defect}
              onClose={() => setEditing(false)}
              onUpdated={(updated) => {
                setDetail(updated)
                // 유형·심각도가 바뀌면 축마다 이력이 남는다. 다시 읽게 한다.
                setHistory({ kind: 'idle' })
                onChanged()
              }}
            />
          )}
        </>
      )}
    </aside>
  )
}
