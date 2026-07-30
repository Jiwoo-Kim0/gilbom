import {
  ClipboardCheck,
  MapPinned,
  Plus,
  RefreshCw,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type FormEvent,
} from 'react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import { MISSION_MANAGE } from '../auth/permissions'
import {
  createPatrolArea,
  getPatrolAreas,
} from './missionApi'
import {
  createPolygonBoundary,
  createSquareBoundary,
  hasBoundarySelfIntersections,
  orderBoundaryPointsForPolygon,
  polygonHasArea,
} from './patrolAreaBoundary'
import { PatrolAreaBoundaryEditor } from './PatrolAreaBoundaryEditor'
import type {
  GeoJsonCoordinate,
  GeoJsonPolygon,
  PatrolArea,
  PatrolAreaBoundary,
} from './missionTypes'
import './mission.css'

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function boundaryPointCount(boundary: PatrolAreaBoundary): number {
  if (boundary.type === 'Polygon') {
    return Math.max(boundary.coordinates[0]?.length - 1, 0)
  }
  return boundary.coordinates.reduce((count, polygon) => (
    count + Math.max(polygon[0]?.length - 1, 0)
  ), 0)
}

export function PatrolAreaManagePage() {
  const [areas, setAreas] = useState<PatrolArea[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [registrationOpen, setRegistrationOpen] = useState(false)
  const [areaCode, setAreaCode] = useState('')
  const [areaName, setAreaName] = useState('')
  const [centerLatitude, setCenterLatitude] = useState('')
  const [centerLongitude, setCenterLongitude] = useState('')
  const [widthMeters, setWidthMeters] = useState('50')
  const [boundaryMode, setBoundaryMode] = useState<'map' | 'square'>('map')
  const [boundaryPoints, setBoundaryPoints] = useState<GeoJsonCoordinate[]>([])
  const [formError, setFormError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)
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

  function openRegistration() {
    setFormError(null)
    setRegistrationOpen(true)
  }

  function resetRegistrationForm() {
    setAreaCode('')
    setAreaName('')
    setCenterLatitude('')
    setCenterLongitude('')
    setWidthMeters('50')
    setBoundaryMode('map')
    setBoundaryPoints([])
    setFormError(null)
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

    let boundary: GeoJsonPolygon
    if (boundaryMode === 'map') {
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
      boundary = createPolygonBoundary(orderedPoints)
    } else {
      const latitude = Number(centerLatitude)
      const longitude = Number(centerLongitude)
      const width = Number(widthMeters)
      if (!Number.isFinite(latitude) || latitude < -85 || latitude > 85) {
        setFormError(new Error('중심 위도는 -85 이상 85 이하로 입력해주세요.'))
        return
      }
      if (
        !Number.isFinite(longitude)
        || longitude < -180
        || longitude > 180
      ) {
        setFormError(new Error('중심 경도는 -180 이상 180 이하로 입력해주세요.'))
        return
      }
      if (!Number.isFinite(width) || width < 5 || width > 5000) {
        setFormError(new Error('구역 가로폭은 5m 이상 5,000m 이하로 입력해주세요.'))
        return
      }
      boundary = createSquareBoundary(latitude, longitude, width)
    }

    if (boundary.coordinates[0].some(([longitude, latitude]) => (
      longitude < -180
      || longitude > 180
      || latitude < -90
      || latitude > 90
    ))) {
      setFormError(new Error(
        '생성되는 구역 경계가 GPS 좌표 범위를 벗어납니다. 중심 좌표나 가로폭을 조정해주세요.',
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
      setNotice(
        `${created.areaName} (${created.areaCode}) 구역을 ACTIVE로 등록했습니다. `
        + '이제 순찰 임무 관리에서 선택할 수 있습니다.',
      )
    } catch (nextError) {
      setFormError(nextError)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="page-stack" aria-labelledby="patrol-area-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">PATROL AREA</p>
          <h2 id="patrol-area-page-title">순찰 구역 관리</h2>
          <p>
            지도에서 관할 경계를 등록하고 임무에 사용할 활성 구역을 관리합니다.
          </p>
        </div>
        <div className="mission-page-actions">
          <button
            className="secondary-button button-with-icon"
            type="button"
            onClick={() => void loadAreas()}
            disabled={loading}
          >
            <RefreshCw size={17} aria-hidden="true" />
            새로고침
          </button>
          {canManage ? (
            <button
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

      <div className="summary-grid" aria-label="순찰 구역 현황">
        <article className="summary-card">
          <span>등록된 전체 구역</span>
          <strong>{loading && areas.length === 0 ? '—' : areas.length}</strong>
          <small>현재 권한 범위 기준</small>
        </article>
        <article className="summary-card">
          <span>미션 선택 가능</span>
          <strong>{loading && areas.length === 0 ? '—' : activeAreaCount}</strong>
          <small>ACTIVE 구역</small>
        </article>
        <article className="summary-card">
          <span>미션 선택 제외</span>
          <strong>
            {loading && areas.length === 0 ? '—' : areas.length - activeAreaCount}
          </strong>
          <small>INACTIVE · ARCHIVED 구역</small>
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
        <details
          className="content-card mission-create-card mission-area-create-card"
          open={registrationOpen}
          onToggle={(event) => setRegistrationOpen(event.currentTarget.open)}
        >
          <summary>
            <MapPinned size={17} aria-hidden="true" />
            순찰 구역 등록
          </summary>
          <p className="mission-create-description">
            지도에서 실제 순찰 경계를 지정합니다. 지도를 사용할 수 없으면 중심
            GPS 좌표와 가로폭으로 사각형 구역을 만들 수 있습니다.
          </p>
          {formError ? (
            <div className="page-alert" role="alert">
              등록하지 못했습니다. {getApiErrorMessage(formError)}
            </div>
          ) : null}
          <form
            className="mission-create-form mission-area-create-form"
            onSubmit={handleCreateArea}
          >
            <label className="admin-form-field" htmlFor="patrol-area-code">
              구역 코드
              <input
                id="patrol-area-code"
                type="text"
                maxLength={50}
                value={areaCode}
                onChange={(event) => setAreaCode(event.target.value)}
                placeholder="DEMO-ZONE-01"
                disabled={submitting}
                required
              />
              <small>Incident payload의 zone_id와 정확히 일치해야 합니다.</small>
            </label>
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
            <div
              className="mission-area-boundary-mode"
              role="group"
              aria-label="순찰 구역 경계 입력 방식"
            >
              <button
                type="button"
                className={boundaryMode === 'map' ? 'is-active' : ''}
                aria-pressed={boundaryMode === 'map'}
                onClick={() => {
                  setBoundaryMode('map')
                  setFormError(null)
                }}
                disabled={submitting}
              >
                지도에서 경계 지정
              </button>
              <button
                type="button"
                className={boundaryMode === 'square' ? 'is-active' : ''}
                aria-pressed={boundaryMode === 'square'}
                onClick={() => {
                  setBoundaryMode('square')
                  setFormError(null)
                }}
                disabled={submitting}
              >
                중심 좌표 직접 입력
              </button>
            </div>
            {boundaryMode === 'map' && registrationOpen ? (
              <div className="mission-area-boundary-field">
                <PatrolAreaBoundaryEditor
                  points={boundaryPoints}
                  disabled={submitting}
                  onChange={setBoundaryPoints}
                  onRequestFallback={() => setBoundaryMode('square')}
                />
              </div>
            ) : boundaryMode === 'square' ? (
              <>
                <label
                  className="admin-form-field"
                  htmlFor="patrol-area-latitude"
                >
                  중심 위도
                  <input
                    id="patrol-area-latitude"
                    type="number"
                    min="-85"
                    max="85"
                    step="any"
                    value={centerLatitude}
                    onChange={(event) => setCenterLatitude(event.target.value)}
                    placeholder="36.1070"
                    disabled={submitting}
                    required
                  />
                </label>
                <label
                  className="admin-form-field"
                  htmlFor="patrol-area-longitude"
                >
                  중심 경도
                  <input
                    id="patrol-area-longitude"
                    type="number"
                    min="-180"
                    max="180"
                    step="any"
                    value={centerLongitude}
                    onChange={(event) => setCenterLongitude(event.target.value)}
                    placeholder="128.4160"
                    disabled={submitting}
                    required
                  />
                </label>
                <label
                  className="admin-form-field"
                  htmlFor="patrol-area-width"
                >
                  구역 가로폭(m)
                  <input
                    id="patrol-area-width"
                    type="number"
                    min="5"
                    max="5000"
                    step="1"
                    value={widthMeters}
                    onChange={(event) => setWidthMeters(event.target.value)}
                    disabled={submitting}
                    required
                  />
                  <small>
                    중심 좌표를 기준으로 동일한 가로·세로 길이의 구역을 만듭니다.
                  </small>
                </label>
              </>
            ) : null}
            <div className="mission-area-create-summary" role="note">
              <strong>ACTIVE로 즉시 등록</strong>
              <span>AUTONOMOUS_DEFAULT 경로가 자동 생성됩니다.</span>
            </div>
            <button
              className="primary-button mission-create-submit"
              type="submit"
              disabled={submitting}
            >
              <MapPinned size={17} aria-hidden="true" />
              {submitting ? '등록 중…' : '순찰 구역 등록'}
            </button>
          </form>
        </details>
      ) : (
        <div className="mission-view-only" role="note">
          조회 전용 권한입니다. 순찰 구역 등록은{' '}
          <code>MISSION_MANAGE</code> 권한이 필요합니다.
        </div>
      )}

      <div className="content-card patrol-area-list-card">
        <div className="table-heading">
          <div>
            <h3>등록 구역 목록</h3>
            <p>ACTIVE 구역만 순찰 임무 생성 화면의 선택 목록에 표시됩니다.</p>
          </div>
          <span className="status-badge">{areas.length}건</span>
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
                  <th scope="col">경계점</th>
                  <th scope="col">미션 사용</th>
                  <th scope="col">최근 수정</th>
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
                        {area.lifecycleStatus}
                      </span>
                    </td>
                    <td>{boundaryPointCount(area.boundary)}개</td>
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
