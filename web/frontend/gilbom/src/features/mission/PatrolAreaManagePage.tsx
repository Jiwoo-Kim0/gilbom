import {
  CheckCircle2,
  CircleAlert,
  ClipboardCheck,
  Eye,
  MapPinned,
  Pencil,
  Plus,
  RotateCcw,
  Undo2,
  X,
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
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import { lockBodyScroll } from '../../shared/dom/bodyScrollLock'
import { useAuthentication } from '../auth/authenticationContext'
import { MISSION_MANAGE } from '../auth/permissions'
import {
  checkPatrolAreaCodeAvailability,
  createPatrolArea,
  getPatrolAreas,
} from './missionApi'
import {
  createPolygonBoundary,
  hasBoundarySelfIntersections,
  orderBoundaryPointsForPolygon,
  polygonHasArea,
} from './patrolAreaBoundary'
import { PatrolAreaBoundaryEditor } from './PatrolAreaBoundaryEditor'
import { PatrolAreaEditDialog } from './PatrolAreaEditDialog'
import { PatrolAreaViewDialog } from './PatrolAreaViewDialog'
import type {
  GeoJsonCoordinate,
  GeoJsonPolygon,
  PatrolArea,
  PatrolAreaCodeAvailability,
} from './missionTypes'
import './mission.css'

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function PatrolAreaManagePage() {
  const [areas, setAreas] = useState<PatrolArea[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [registrationOpen, setRegistrationOpen] = useState(false)
  const [viewingArea, setViewingArea] = useState<PatrolArea | null>(null)
  const [editingArea, setEditingArea] = useState<PatrolArea | null>(null)
  const [areaCode, setAreaCode] = useState('')
  const [areaCodeAvailability, setAreaCodeAvailability] =
    useState<PatrolAreaCodeAvailability | null>(null)
  const [checkingAreaCode, setCheckingAreaCode] = useState(false)
  const [areaName, setAreaName] = useState('')
  const [boundaryPoints, setBoundaryPoints] = useState<GeoJsonCoordinate[]>([])
  const [formError, setFormError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)
  const registrationTriggerRef = useRef<HTMLButtonElement>(null)
  const viewTriggerRef = useRef<HTMLButtonElement | null>(null)
  const { user } = useAuthentication()
  const canManage = user?.permissions.includes(MISSION_MANAGE) ?? false

  const activeAreaCount = useMemo(
    () => areas.filter((area) => area.lifecycleStatus === 'ACTIVE').length,
    [areas],
  )

  const loadAreas = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const response = await getPatrolAreas()
      setAreas(response.areas)
    } catch (nextError) {
      setError(nextError)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadAreas()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [loadAreas])

  useEffect(() => {
    if (!registrationOpen) {
      return undefined
    }

    const releaseBodyScroll = lockBodyScroll()
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !submitting) {
        setRegistrationOpen(false)
        window.requestAnimationFrame(() => registrationTriggerRef.current?.focus())
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      releaseBodyScroll()
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [registrationOpen, submitting])

  function openRegistration() {
    setFormError(null)
    setRegistrationOpen(true)
  }

  function closeRegistration() {
    if (submitting) {
      return
    }
    setRegistrationOpen(false)
    window.requestAnimationFrame(() => registrationTriggerRef.current?.focus())
  }

  function closeViewingArea() {
    setViewingArea(null)
    window.requestAnimationFrame(() => viewTriggerRef.current?.focus())
  }

  function resetRegistrationForm() {
    setAreaCode('')
    setAreaCodeAvailability(null)
    setAreaName('')
    setBoundaryPoints([])
    setFormError(null)
  }

  function handleAreaSaved(savedArea: PatrolArea) {
    setAreas((currentAreas) => currentAreas.map((area) => (
      area.patrolAreaId === savedArea.patrolAreaId ? savedArea : area
    )))
    setEditingArea(null)
    setNotice(
      `${savedArea.areaName} (${savedArea.areaCode}) 구역 정보를 변경했습니다.`,
    )
  }

  function lifecycleStatusText(area: PatrolArea): string {
    if (area.lifecycleStatus === 'ACTIVE') {
      return '활성화'
    }
    if (area.lifecycleStatus === 'INACTIVE') {
      return '비활성화'
    }
    return '구역 폐기'
  }

  async function handleAreaCodeAvailabilityCheck() {
    const normalizedAreaCode = areaCode.trim()
    if (normalizedAreaCode.length === 0) {
      setFormError(new Error('중복 확인할 구역 코드를 입력해주세요.'))
      return
    }

    setCheckingAreaCode(true)
    setFormError(null)
    try {
      const availability = await checkPatrolAreaCodeAvailability(
        normalizedAreaCode,
      )
      setAreaCodeAvailability(availability)
    } catch (nextError) {
      setAreaCodeAvailability(null)
      setFormError(nextError)
    } finally {
      setCheckingAreaCode(false)
    }
  }

  async function handleCreateArea(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canManage) {
      return
    }

    const normalizedAreaCode = areaCode.trim()
    const normalizedAreaName = areaName.trim()
    if (normalizedAreaCode.length === 0 || normalizedAreaName.length === 0) {
      setFormError(new Error('구역 코드와 구역 이름을 입력해주세요.'))
      return
    }
    if (
      areaCodeAvailability === null
      || areaCodeAvailability.areaCode !== normalizedAreaCode
    ) {
      setFormError(new Error('구역 코드 중복 확인을 완료해주세요.'))
      return
    }
    if (!areaCodeAvailability.available) {
      return
    }

    if (boundaryPoints.length < 3) {
      setFormError(new Error(
        '지도에서 순찰 구역 경계점을 3개 이상 지정해주세요.',
      ))
      return
    }
    const uniquePointCount = new Set(
      boundaryPoints.map(([longitude, latitude]) => (
        `${longitude}:${latitude}`
      )),
    ).size
    const orderedPoints = orderBoundaryPointsForPolygon(boundaryPoints)
    if (uniquePointCount < 3 || !polygonHasArea(orderedPoints)) {
      setFormError(new Error(
        '서로 다른 위치의 경계점으로 면적이 있는 구역을 만들어주세요.',
      ))
      return
    }
    if (hasBoundarySelfIntersections(orderedPoints)) {
      setFormError(new Error(
        '경계점 연결에서 교차를 제거하지 못했습니다. 점 위치를 조정해주세요.',
      ))
      return
    }
    const boundary: GeoJsonPolygon = createPolygonBoundary(orderedPoints)

    if (boundary.coordinates[0].some(([longitude, latitude]) => (
      longitude < -180
      || longitude > 180
      || latitude < -90
      || latitude > 90
    ))) {
      setFormError(new Error(
        '생성되는 구역 경계가 GPS 좌표 범위를 벗어납니다. 경계점을 조정해주세요.',
      ))
      return
    }

    setSubmitting(true)
    setFormError(null)
    setNotice(null)
    try {
      const created = await createPatrolArea({
        areaCode: normalizedAreaCode,
        areaName: normalizedAreaName,
        boundary,
        lifecycleStatus: 'ACTIVE',
      })
      await loadAreas()
      resetRegistrationForm()
      setRegistrationOpen(false)
      window.requestAnimationFrame(() => registrationTriggerRef.current?.focus())
      setNotice(
        `${created.areaName} (${created.areaCode}) 구역을 활성화 상태로 등록했습니다. `
        + '이제 순찰 임무 관리에서 선택할 수 있습니다.',
      )
    } catch (nextError) {
      if (
        nextError instanceof ApiError
        && nextError.code === 'PATROL_AREA_CODE_CONFLICT'
      ) {
        setAreaCodeAvailability({
          areaCode: normalizedAreaCode,
          available: false,
        })
      } else {
        setFormError(nextError)
      }
    } finally {
      setSubmitting(false)
    }
  }

  const normalizedAreaCode = areaCode.trim()
  const matchingAreaCodeAvailability =
    areaCodeAvailability?.areaCode === normalizedAreaCode
      ? areaCodeAvailability
      : null
  const areaCodeConflict = matchingAreaCodeAvailability?.available === false

  return (
    <section className="page-stack" aria-label="순찰 구역 관리">
      <div className="summary-grid" aria-label="순찰 구역 현황">
        <article className="summary-card">
          <span>등록된 전체 구역</span>
          <strong>{loading && areas.length === 0 ? '—' : areas.length}</strong>
          <small>현재 권한 범위 기준</small>
        </article>
        <article className="summary-card">
          <span>미션 선택 가능</span>
          <strong>{loading && areas.length === 0 ? '—' : activeAreaCount}</strong>
          <small>활성화 구역</small>
        </article>
        <article className="summary-card">
          <span>미션 선택 제외</span>
          <strong>
            {loading && areas.length === 0 ? '—' : areas.length - activeAreaCount}
          </strong>
          <small>비활성화 · 구역 폐기</small>
        </article>
      </div>

      {notice ? (
        <div className="page-notice" role="status">
          {notice}
        </div>
      ) : null}
      {error ? (
        <div className="page-alert" role="alert">
          순찰 구역을 불러오지 못했습니다. {getApiErrorMessage(error)}
        </div>
      ) : null}

      {canManage ? (
        registrationOpen ? (
        <div
          className="mission-area-modal-backdrop"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) {
              closeRegistration()
            }
          }}
        >
          <section
            className="mission-area-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="patrol-area-registration-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="mission-area-modal-heading">
              <div>
                <p className="eyebrow">PATROL AREA REGISTRATION</p>
                <h3 id="patrol-area-registration-title">순찰 구역 등록</h3>
              </div>
              <button
                className="icon-button"
                type="button"
                aria-label="등록 창 닫기"
                onClick={closeRegistration}
                disabled={submitting}
              >
                <X size={18} aria-hidden="true" />
              </button>
            </div>
          {formError ? (
            <div className="page-alert" role="alert">
              등록하지 못했습니다. {getApiErrorMessage(formError)}
            </div>
          ) : null}
          <form
            className="mission-create-form mission-area-create-form"
            onSubmit={handleCreateArea}
          >
            <div className="mission-area-form-fields">
              <div className="admin-form-field mission-area-code-check">
              <label htmlFor="patrol-area-code">구역 코드</label>
              <div className="input-action-row">
                <input
                  id="patrol-area-code"
                  type="text"
                  autoFocus
                  maxLength={50}
                  value={areaCode}
                  onChange={(event) => {
                    setAreaCode(event.target.value)
                    setAreaCodeAvailability(null)
                  }}
                  aria-invalid={areaCodeConflict}
                  aria-describedby={matchingAreaCodeAvailability
                    ? 'patrol-area-code-status'
                    : undefined}
                  placeholder="A-01"
                  disabled={submitting}
                  required
                />
                <button
                  className="secondary-button"
                  type="button"
                  onClick={() => void handleAreaCodeAvailabilityCheck()}
                  disabled={submitting || checkingAreaCode}
                >
                  {checkingAreaCode ? '확인 중…' : '중복 확인'}
                </button>
              </div>
              {matchingAreaCodeAvailability ? (
                <p
                  id="patrol-area-code-status"
                  className={matchingAreaCodeAvailability.available
                    ? 'availability-ok'
                    : 'availability-denied'}
                  role={matchingAreaCodeAvailability.available
                    ? 'status'
                    : 'alert'}
                >
                  {matchingAreaCodeAvailability.available ? (
                    <CheckCircle2 size={15} aria-hidden="true" />
                  ) : (
                    <CircleAlert size={15} aria-hidden="true" />
                  )}
                  {matchingAreaCodeAvailability.available
                    ? '사용 가능한 구역 코드입니다.'
                    : '이미 등록된 구역 코드입니다.'}
                </p>
              ) : null}
              </div>
              <label className="admin-form-field" htmlFor="patrol-area-name">
                구역 이름
                <input
                  id="patrol-area-name"
                  type="text"
                  maxLength={100}
                  value={areaName}
                  onChange={(event) => setAreaName(event.target.value)}
                  placeholder="시연 순찰 구역"
                  disabled={submitting}
                  required
                />
              </label>
            </div>
            {registrationOpen ? (
              <div className="mission-area-boundary-field">
                <PatrolAreaBoundaryEditor
                  points={boundaryPoints}
                  disabled={submitting}
                  onChange={setBoundaryPoints}
                  actions={(
                    <div className="mission-area-editor-toolbar">
                      <button
                        className="secondary-button button-with-icon"
                        type="button"
                        onClick={() => setBoundaryPoints(
                          (points) => points.slice(0, -1),
                        )}
                        disabled={submitting || boundaryPoints.length === 0}
                      >
                        <Undo2 size={15} aria-hidden="true" />
                        마지막 점 취소
                      </button>
                      <button
                        className="secondary-button button-with-icon"
                        type="button"
                        onClick={() => setBoundaryPoints([])}
                        disabled={submitting || boundaryPoints.length === 0}
                      >
                        <RotateCcw size={15} aria-hidden="true" />
                        전체 초기화
                      </button>
                      <button
                        className="primary-button mission-create-submit"
                        type="submit"
                        disabled={
                          submitting
                          || matchingAreaCodeAvailability?.available !== true
                        }
                      >
                        <MapPinned size={17} aria-hidden="true" />
                        {submitting ? '등록 중…' : '순찰 구역 등록'}
                      </button>
                    </div>
                  )}
                />
              </div>
            ) : null}
          </form>
          </section>
        </div>
        ) : null
      ) : (
        <div className="mission-view-only" role="note">
          조회 전용 권한입니다. 순찰 구역 등록은{' '}
          <code>MISSION_MANAGE</code> 권한이 필요합니다.
        </div>
      )}

      {editingArea ? (
        <PatrolAreaEditDialog
          area={editingArea}
          onClose={() => setEditingArea(null)}
          onSaved={handleAreaSaved}
        />
      ) : null}

      {viewingArea ? (
        <PatrolAreaViewDialog
          area={viewingArea}
          onClose={closeViewingArea}
        />
      ) : null}

      <div className="content-card patrol-area-list-card">
        <div className="table-heading">
          <div>
            <h3>등록 구역 목록</h3>
            <p>활성화 구역만 순찰 임무 생성 화면의 선택 목록에 표시됩니다.</p>
          </div>
          <div className="table-heading-actions">
            <span className="status-badge">{areas.length}건</span>
            {canManage ? (
              <button
                ref={registrationTriggerRef}
                className="primary-button button-with-icon"
                type="button"
                onClick={openRegistration}
              >
                <Plus size={17} aria-hidden="true" />
                새 구역 등록
              </button>
            ) : null}
          </div>
        </div>

        {loading && areas.length === 0 ? (
          <div className="admin-loading" role="status">
            <span className="loading-spinner" aria-hidden="true" />
            순찰 구역을 불러오는 중입니다.
          </div>
        ) : areas.length === 0 ? (
          <div className="empty-state patrol-area-empty">
            <span className="empty-state-icon" aria-hidden="true">
              <MapPinned size={22} />
            </span>
            <h3>등록된 순찰 구역이 없습니다.</h3>
            <p>지도에서 첫 관할 구역의 경계를 등록해주세요.</p>
          </div>
        ) : (
          <div className="admin-table-wrap">
            <table className="admin-table patrol-area-table">
              <thead>
                <tr>
                  <th scope="col">구역</th>
                  <th scope="col">상태</th>
                  <th scope="col">미션 사용</th>
                  <th scope="col">최근 수정</th>
                  <th className="patrol-area-action-heading" scope="col">
                    조회
                  </th>
                  <th className="patrol-area-action-heading" scope="col">
                    수정
                  </th>
                </tr>
              </thead>
              <tbody>
                {areas.map((area) => (
                  <tr key={area.patrolAreaId}>
                    <td>
                      <strong>{area.areaName}</strong>
                      <small>{area.areaCode}</small>
                    </td>
                    <td>
                      <span
                        className={`patrol-area-status patrol-area-status-${
                          area.lifecycleStatus.toLowerCase()
                        }`}
                      >
                        {lifecycleStatusText(area)}
                      </span>
                    </td>
                    <td>
                      {area.lifecycleStatus === 'ACTIVE' ? (
                        <span className="patrol-area-mission-eligible">
                          <ClipboardCheck size={15} aria-hidden="true" />
                          선택 가능
                        </span>
                      ) : (
                        <span className="patrol-area-mission-unavailable">
                          선택 제외
                        </span>
                      )}
                    </td>
                    <td>{formatDateTime(area.updatedAt)}</td>
                    <td className="patrol-area-action-cell">
                      <button
                        className="patrol-area-view-button"
                        type="button"
                        aria-label={`${area.areaName} 조회`}
                        onClick={(event) => {
                          viewTriggerRef.current = event.currentTarget
                          setNotice(null)
                          setViewingArea(area)
                        }}
                      >
                        <Eye size={15} aria-hidden="true" />
                        조회
                      </button>
                    </td>
                    <td className="patrol-area-action-cell">
                      {canManage && area.lifecycleStatus !== 'ARCHIVED' ? (
                        <button
                          className="patrol-area-edit-button"
                          type="button"
                          aria-label={`${area.areaName} 수정`}
                          onClick={() => {
                            setNotice(null)
                            setEditingArea(area)
                          }}
                        >
                          <Pencil size={15} aria-hidden="true" />
                          수정
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}
