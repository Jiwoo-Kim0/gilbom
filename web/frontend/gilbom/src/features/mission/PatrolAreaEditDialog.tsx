import {
  CheckCircle2,
  CircleAlert,
  RotateCcw,
  Save,
  Undo2,
  X,
} from 'lucide-react'
import {
  useEffect,
  useMemo,
  useState,
  type FormEvent,
} from 'react'
import {
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import { lockBodyScroll } from '../../shared/dom/bodyScrollLock'
import {
  changePatrolAreaStatus,
  checkPatrolAreaCodeAvailability,
  updatePatrolArea,
} from './missionApi'
import {
  createPolygonBoundary,
  hasBoundarySelfIntersections,
  orderBoundaryPointsForPolygon,
  polygonHasArea,
} from './patrolAreaBoundary'
import { PatrolAreaBoundaryEditor } from './PatrolAreaBoundaryEditor'
import type {
  GeoJsonCoordinate,
  GeoJsonPolygon,
  PatrolArea,
  PatrolAreaCodeAvailability,
  PatrolAreaLifecycleStatus,
  PatrolAreaUpdateInput,
} from './missionTypes'

type PatrolAreaEditDialogProps = {
  area: PatrolArea
  onClose: () => void
  onSaved: (area: PatrolArea) => void
}

const lifecycleStatusLabel: Record<PatrolAreaLifecycleStatus, string> = {
  ACTIVE: '활성화',
  INACTIVE: '비활성화',
  ARCHIVED: '구역 폐기',
}

function editablePolygonPoints(area: PatrolArea): GeoJsonCoordinate[] | null {
  if (area.boundary.type !== 'Polygon') {
    return null
  }
  const ring = area.boundary.coordinates[0] ?? []
  if (ring.length === 0) {
    return []
  }
  const [firstLongitude, firstLatitude] = ring[0]
  const [lastLongitude, lastLatitude] = ring.at(-1) ?? ring[0]
  const points = firstLongitude === lastLongitude && firstLatitude === lastLatitude
    ? ring.slice(0, -1)
    : ring
  return points.map(([longitude, latitude]) => [longitude, latitude])
}

function validatedBoundary(points: GeoJsonCoordinate[]): GeoJsonPolygon {
  if (points.length < 3) {
    throw new Error('지도에서 순찰 구역 경계점을 3개 이상 지정해주세요.')
  }
  const uniquePointCount = new Set(
    points.map(([longitude, latitude]) => `${longitude}:${latitude}`),
  ).size
  const orderedPoints = orderBoundaryPointsForPolygon(points)
  if (uniquePointCount < 3 || !polygonHasArea(orderedPoints)) {
    throw new Error('서로 다른 위치의 경계점으로 면적이 있는 구역을 만들어주세요.')
  }
  if (hasBoundarySelfIntersections(orderedPoints)) {
    throw new Error('경계점 연결에서 교차를 제거하지 못했습니다. 점 위치를 조정해주세요.')
  }
  const boundary = createPolygonBoundary(orderedPoints)
  if (boundary.coordinates[0].some(([longitude, latitude]) => (
    longitude < -180
    || longitude > 180
    || latitude < -90
    || latitude > 90
  ))) {
    throw new Error('생성되는 구역 경계가 GPS 좌표 범위를 벗어납니다.')
  }
  return boundary
}

function allowedStatuses(
  current: PatrolAreaLifecycleStatus,
): PatrolAreaLifecycleStatus[] {
  if (current === 'ACTIVE') {
    return ['ACTIVE', 'INACTIVE']
  }
  if (current === 'INACTIVE') {
    return ['ACTIVE', 'INACTIVE']
  }
  return ['ARCHIVED']
}

export function PatrolAreaEditDialog({
  area,
  onClose,
  onSaved,
}: PatrolAreaEditDialogProps) {
  const initialPoints = useMemo(() => editablePolygonPoints(area), [area])
  const [areaCode, setAreaCode] = useState(area.areaCode)
  const [areaName, setAreaName] = useState(area.areaName)
  const [lifecycleStatus, setLifecycleStatus] =
    useState<PatrolAreaLifecycleStatus>(area.lifecycleStatus)
  const [draftBoundaryPoints, setDraftBoundaryPoints] = useState<GeoJsonCoordinate[]>(
    initialPoints ?? [],
  )
  const [boundaryDirty, setBoundaryDirty] = useState(false)
  const [areaCodeAvailability, setAreaCodeAvailability] =
    useState<PatrolAreaCodeAvailability | null>(null)
  const [checkingAreaCode, setCheckingAreaCode] = useState(false)
  const [formError, setFormError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)
  const archived = area.lifecycleStatus === 'ARCHIVED'

  useEffect(() => {
    const releaseBodyScroll = lockBodyScroll()
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !submitting) {
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      releaseBodyScroll()
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [onClose, submitting])

  const normalizedAreaCode = areaCode.trim()
  const normalizedAreaName = areaName.trim()
  const areaCodeChanged = normalizedAreaCode !== area.areaCode
  const matchingAreaCodeAvailability = areaCodeChanged
    && areaCodeAvailability?.areaCode === normalizedAreaCode
      ? areaCodeAvailability
      : null
  const areaCodeValid = !areaCodeChanged
    || matchingAreaCodeAvailability?.available === true
  const boundaryDraftIncomplete = initialPoints !== null
    && boundaryDirty
    && draftBoundaryPoints.length < 3

  async function handleAreaCodeAvailabilityCheck() {
    if (normalizedAreaCode.length === 0) {
      setFormError(new Error('중복 확인할 구역 코드를 입력해주세요.'))
      return
    }
    if (!areaCodeChanged) {
      setAreaCodeAvailability({ areaCode: normalizedAreaCode, available: true })
      return
    }

    setCheckingAreaCode(true)
    setFormError(null)
    try {
      setAreaCodeAvailability(
        await checkPatrolAreaCodeAvailability(normalizedAreaCode),
      )
    } catch (nextError) {
      setAreaCodeAvailability(null)
      setFormError(nextError)
    } finally {
      setCheckingAreaCode(false)
    }
  }

  function changeBoundaryPoints(points: GeoJsonCoordinate[]) {
    setDraftBoundaryPoints(points)
    setBoundaryDirty(true)
  }

  function discardBoundaryChanges() {
    setDraftBoundaryPoints(initialPoints ?? [])
    setBoundaryDirty(false)
    setFormError(null)
  }

  async function saveChanges(targetStatus: PatrolAreaLifecycleStatus) {
    if (archived) {
      return
    }
    if (normalizedAreaCode.length === 0 || normalizedAreaName.length === 0) {
      setFormError(new Error('구역 코드와 구역 이름을 입력해주세요.'))
      return
    }
    if (!areaCodeValid) {
      setFormError(new Error('변경한 구역 코드의 중복 확인을 완료해주세요.'))
      return
    }

    const input: PatrolAreaUpdateInput = {}
    if (areaCodeChanged) {
      input.areaCode = normalizedAreaCode
    }
    if (normalizedAreaName !== area.areaName) {
      input.areaName = normalizedAreaName
    }
    try {
      if (boundaryDirty) {
        input.boundary = validatedBoundary(draftBoundaryPoints)
      }
    } catch (nextError) {
      setFormError(nextError)
      return
    }

    const informationChanged = Object.keys(input).length > 0
    const statusChanged = targetStatus !== area.lifecycleStatus
    if (!informationChanged && !statusChanged) {
      setFormError(new Error('변경된 내용이 없습니다.'))
      return
    }

    setSubmitting(true)
    setFormError(null)
    let savedArea = area
    try {
      if (informationChanged) {
        savedArea = await updatePatrolArea(area.patrolAreaId, input)
      }
      if (statusChanged) {
        savedArea = await changePatrolAreaStatus(
          area.patrolAreaId,
          targetStatus,
        )
      }
      onSaved(savedArea)
    } catch (nextError) {
      if (
        nextError instanceof ApiError
        && nextError.code === 'PATROL_AREA_CODE_CONFLICT'
      ) {
        setAreaCodeAvailability({
          areaCode: normalizedAreaCode,
          available: false,
        })
      }
      setFormError(nextError)
    } finally {
      setSubmitting(false)
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void saveChanges(lifecycleStatus)
  }

  function handleArchive() {
    const confirmed = window.confirm(
      `${area.areaName} 구역을 폐기하시겠습니까? 폐기한 구역은 복구할 수 없습니다.`,
    )
    if (confirmed) {
      void saveChanges('ARCHIVED')
    }
  }

  return (
    <div
      className="mission-area-modal-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !submitting) {
          onClose()
        }
      }}
    >
      <section
        className="mission-area-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="patrol-area-edit-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="mission-area-modal-heading">
          <div>
            <p className="eyebrow">PATROL AREA EDIT</p>
            <h3 id="patrol-area-edit-title">순찰 구역 수정</h3>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label="수정 창 닫기"
            onClick={onClose}
            disabled={submitting}
          >
            <X size={18} aria-hidden="true" />
          </button>
        </div>

        {formError ? (
          <div className="page-alert" role="alert">
            수정하지 못했습니다. {getApiErrorMessage(formError)}
          </div>
        ) : null}
        {archived ? (
          <div className="page-alert" role="note">
            폐기된 구역은 수정하거나 다른 상태로 변경할 수 없습니다.
          </div>
        ) : null}

        <form
          className="mission-create-form mission-area-create-form"
          onSubmit={handleSubmit}
        >
          <div className="mission-area-form-fields mission-area-edit-fields">
            <div className="admin-form-field mission-area-code-check">
              <label htmlFor="patrol-area-edit-code">구역 코드</label>
              <div className="input-action-row">
                <input
                  id="patrol-area-edit-code"
                  type="text"
                  autoFocus
                  maxLength={50}
                  value={areaCode}
                  onChange={(event) => {
                    setAreaCode(event.target.value)
                    setAreaCodeAvailability(null)
                  }}
                  aria-invalid={matchingAreaCodeAvailability?.available === false}
                  aria-describedby={matchingAreaCodeAvailability
                    ? 'patrol-area-edit-code-status'
                    : undefined}
                  disabled={submitting || archived}
                  required
                />
                <button
                  className="secondary-button"
                  type="button"
                  onClick={() => void handleAreaCodeAvailabilityCheck()}
                  disabled={
                    submitting
                    || archived
                    || checkingAreaCode
                    || !areaCodeChanged
                  }
                >
                  {checkingAreaCode ? '확인 중…' : '중복 확인'}
                </button>
              </div>
              {matchingAreaCodeAvailability ? (
                <p
                  id="patrol-area-edit-code-status"
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

            <label className="admin-form-field" htmlFor="patrol-area-edit-name">
              구역 이름
              <input
                id="patrol-area-edit-name"
                type="text"
                maxLength={100}
                value={areaName}
                onChange={(event) => setAreaName(event.target.value)}
                disabled={submitting || archived}
                required
              />
            </label>

            <label className="admin-form-field" htmlFor="patrol-area-edit-status">
              상태
              <select
                id="patrol-area-edit-status"
                value={lifecycleStatus}
                onChange={(event) => setLifecycleStatus(
                  event.target.value as PatrolAreaLifecycleStatus,
                )}
                disabled={submitting || archived}
              >
                {allowedStatuses(area.lifecycleStatus).map((status) => (
                  <option key={status} value={status}>
                    {lifecycleStatusLabel[status]}
                  </option>
                ))}
              </select>
              {area.lifecycleStatus !== 'INACTIVE' ? (
                <small>
                  {area.lifecycleStatus === 'ACTIVE'
                    ? '활성화와 비활성화를 자유롭게 변경할 수 있습니다.'
                    : '폐기된 구역입니다.'}
                </small>
              ) : null}
            </label>
          </div>

          {initialPoints === null ? (
            <div className="page-alert" role="note">
              복합 구역(MultiPolygon)의 경계는 이 화면에서 수정할 수 없습니다.
            </div>
          ) : (
            <div className="mission-area-boundary-field">
              <PatrolAreaBoundaryEditor
                points={draftBoundaryPoints}
                disabled={submitting || archived}
                onChange={changeBoundaryPoints}
                actions={archived ? null : (
                  <div className="mission-area-editor-toolbar">
                    {boundaryDirty ? (
                      <button
                        className="secondary-button"
                        type="button"
                        onClick={discardBoundaryChanges}
                        disabled={submitting}
                      >
                        경계 변경 취소
                      </button>
                    ) : null}
                    <button
                      className="secondary-button button-with-icon"
                      type="button"
                      onClick={() => changeBoundaryPoints(
                        draftBoundaryPoints.slice(0, -1),
                      )}
                      disabled={submitting || draftBoundaryPoints.length === 0}
                    >
                      <Undo2 size={15} aria-hidden="true" />
                      마지막 점 취소
                    </button>
                    <button
                      className="secondary-button button-with-icon"
                      type="button"
                      onClick={() => changeBoundaryPoints([])}
                      disabled={submitting || draftBoundaryPoints.length === 0}
                    >
                      <RotateCcw size={15} aria-hidden="true" />
                      전체 초기화
                    </button>
                  </div>
                )}
              />
            </div>
          )}

          <div className="mission-area-edit-actions">
            {area.lifecycleStatus === 'INACTIVE' ? (
              <button
                className="danger-button"
                type="button"
                onClick={handleArchive}
                disabled={
                  submitting || !areaCodeValid || boundaryDraftIncomplete
                }
              >
                구역 폐기
              </button>
            ) : null}
            <span className="mission-area-edit-action-spacer" />
            <button
              className="secondary-button"
              type="button"
              onClick={onClose}
              disabled={submitting}
            >
              닫기
            </button>
            {archived ? null : (
              <button
                className="primary-button button-with-icon"
                type="submit"
                disabled={
                  submitting || !areaCodeValid || boundaryDraftIncomplete
                }
              >
                <Save size={16} aria-hidden="true" />
                {submitting ? '저장 중…' : '변경사항 저장'}
              </button>
            )}
          </div>
        </form>
      </section>
    </div>
  )
}
