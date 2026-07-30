import {
  Bot,
  CalendarClock,
  ChevronRight,
  CircleGauge,
  ClipboardList,
  Filter,
  History,
  MapPinned,
  Plus,
  RefreshCw,
  RotateCcw,
  Route,
  ShieldCheck,
  Timer,
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
import { NavLink } from 'react-router'
import {
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import { MISSION_MANAGE, ROBOT_REGISTER } from '../auth/permissions'
import {
  issueRobotCredential,
  reconcileRobotCredentialRegistry,
  type IssuedMqttCredential,
} from '../robot/robotCredentialApi'
import { getRobotMap } from '../robot/robotApi'
import type { RobotMapItem } from '../robot/robotTypes'
import { useRobotRealtime } from '../robot/useRobotRealtime'
import {
  createMission,
  getMission,
  getMissionHistory,
  getMissionRobotOptions,
  getMissions,
  getPatrolAreas,
  getPatrolRoutes,
} from './missionApi'
import { MissionMap } from './MissionMap'
import { PatrolAreaSelectionPreview } from './PatrolAreaSelectionPreview'
import type {
  Mission,
  MissionFilters,
  MissionListResponse,
  MissionStatus,
  MissionStatusHistoryItem,
  PatrolArea,
  RobotOption,
} from './missionTypes'
import './mission.css'

const PAGE_SIZE = 20

const emptyFilters: MissionFilters = {
  robotId: '',
  patrolAreaId: '',
  status: '',
  startedFrom: '',
  startedTo: '',
}

const statusPresentation: Record<
  MissionStatus,
  { label: string; tone: string }
> = {
  CREATED: { label: '생성됨', tone: 'neutral' },
  READY: { label: '준비', tone: 'ready' },
  RUNNING: { label: '진행 중', tone: 'running' },
  PAUSED: { label: '일시정지', tone: 'paused' },
  COMPLETED: { label: '완료', tone: 'completed' },
  CANCELLED: { label: '취소', tone: 'cancelled' },
  FAILED: { label: '실패', tone: 'failed' },
}

function formatDateTime(value: string | null): string {
  if (value === null) {
    return '—'
  }
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function formatDuration(seconds: number | null): string {
  if (seconds === null) {
    return '—'
  }
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainingSeconds = seconds % 60
  if (hours > 0) {
    return `${hours}시간 ${minutes}분`
  }
  if (minutes > 0) {
    return `${minutes}분 ${remainingSeconds}초`
  }
  return `${remainingSeconds}초`
}

function MissionStatusBadge({ status }: { status: MissionStatus }) {
  const presentation = statusPresentation[status]
  return (
    <span
      className={`mission-status mission-status-${presentation.tone}`}
      data-status={status}
    >
      {presentation.label}
    </span>
  )
}

function HistoryItem({ item }: { item: MissionStatusHistoryItem }) {
  return (
    <li className="mission-history-item">
      <span className="mission-history-marker" aria-hidden="true" />
      <div>
        <div className="mission-history-heading">
          <strong>
            {item.beforeStatus === null
              ? statusPresentation[item.afterStatus].label
              : `${statusPresentation[item.beforeStatus].label} → ${
                statusPresentation[item.afterStatus].label
              }`}
          </strong>
          <span>{item.transitionSource}</span>
        </div>
        <time dateTime={item.occurredAt}>
          {formatDateTime(item.occurredAt)}
        </time>
        <p>{item.transitionReason ?? '별도 전이 사유 없음'}</p>
        {item.changedByUserCode ? (
          <small>작업자 {item.changedByUserCode}</small>
        ) : null}
      </div>
    </li>
  )
}

export function MissionManagePage() {
  const requestedListKeyRef = useRef<string | null>(null)
  const requestedDetailIdRef = useRef<string | null>(null)
  const catalogRequestedRef = useRef(false)
  const realtimeRefreshTimerRef = useRef<number | null>(null)
  const [draftFilters, setDraftFilters] = useState<MissionFilters>(emptyFilters)
  const [appliedFilters, setAppliedFilters] = useState<MissionFilters>(emptyFilters)
  const [missions, setMissions] = useState<MissionListResponse | null>(null)
  const [page, setPage] = useState(0)
  const [selectedMissionId, setSelectedMissionId] = useState<string | null>(null)
  const [selectedMission, setSelectedMission] = useState<Mission | null>(null)
  const [history, setHistory] = useState<MissionStatusHistoryItem[]>([])
  const [robots, setRobots] = useState<RobotOption[]>([])
  const [robotMapItems, setRobotMapItems] = useState<RobotMapItem[]>([])
  const [areas, setAreas] = useState<PatrolArea[]>([])
  const [createRobotId, setCreateRobotId] = useState('')
  const [createAreaId, setCreateAreaId] = useState('')
  const [autonomousRouteId, setAutonomousRouteId] = useState('')
  const [plannedDurationMinutes, setPlannedDurationMinutes] = useState('60')
  const [scheduledStartAt, setScheduledStartAt] = useState('')
  const [createExpanded, setCreateExpanded] = useState(false)
  const [listLoading, setListLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [catalogLoading, setCatalogLoading] = useState(true)
  const [createRoutesLoading, setCreateRoutesLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [listError, setListError] = useState<unknown>(null)
  const [detailError, setDetailError] = useState<unknown>(null)
  const [catalogError, setCatalogError] = useState<unknown>(null)
  const [formError, setFormError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [activationRobot, setActivationRobot] = useState<RobotOption | null>(
    null,
  )
  const [activationSubmitting, setActivationSubmitting] = useState(false)
  const [activationError, setActivationError] = useState<unknown>(null)
  const [issuedCredential, setIssuedCredential] =
    useState<IssuedMqttCredential | null>(null)
  const activationIdempotencyKeyRef = useRef<string | null>(null)
  const { user } = useAuthentication()
  const canManage = user?.permissions.includes(MISSION_MANAGE) ?? false
  const canRegisterRobot =
    user?.permissions.includes(ROBOT_REGISTER) ?? false
  const credentialRegistryConflict =
    activationError instanceof ApiError
    && activationError.code === 'ROBOT_CREDENTIAL_CONFLICT'

  const activeAreas = useMemo(
    () => areas.filter((area) => area.lifecycleStatus === 'ACTIVE'),
    [areas],
  )
  const activeRobots = useMemo(
    () => robots.filter((robot) => (
      robot.operationalStatus === 'ACTIVE' && robot.missionStatus === null
    )),
    [robots],
  )
  const provisioningRobots = useMemo(
    () => robots.filter((robot) => (
      robot.operationalStatus === 'PROVISIONING'
      && robot.missionStatus === null
    )),
    [robots],
  )
  const selectedCreateArea = useMemo(
    () => activeAreas.find((area) => area.patrolAreaId === createAreaId) ?? null,
    [activeAreas, createAreaId],
  )
  const selectedCreateRobot = useMemo(
    () => activeRobots.find((robot) => robot.robotId === createRobotId) ?? null,
    [activeRobots, createRobotId],
  )
  const selectedCreateRobotPosition = useMemo(
    () => robotMapItems.find((robot) => robot.robotId === createRobotId) ?? null,
    [createRobotId, robotMapItems],
  )
  const pageRunningCount = missions?.content.filter(
    (mission) => mission.status === 'RUNNING',
  ).length ?? 0
  const pageAttentionCount = missions?.content.filter(
    (mission) => mission.status === 'PAUSED' || mission.status === 'FAILED',
  ).length ?? 0

  const loadMissions = useCallback(async (
    targetPage: number,
    filters: MissionFilters,
  ) => {
    setListLoading(true)
    setListError(null)
    try {
      const result = await getMissions(targetPage, PAGE_SIZE, filters)
      setMissions(result)
      setSelectedMissionId((current) => (
        result.content.some((mission) => mission.missionId === current)
          ? current
          : (result.content.at(0)?.missionId ?? null)
      ))
    } catch (error) {
      setListError(error)
    } finally {
      setListLoading(false)
    }
  }, [])

  const loadCatalogs = useCallback(async () => {
    setCatalogLoading(true)
    setCatalogError(null)
    const [robotResult, areaResult, robotMapResult] = await Promise.allSettled([
      getMissionRobotOptions(),
      getPatrolAreas(),
      getRobotMap(),
    ])

    if (robotResult.status === 'fulfilled') {
      setRobots(robotResult.value.content)
    }
    if (areaResult.status === 'fulfilled') {
      setAreas(areaResult.value.areas)
    }
    if (robotMapResult.status === 'fulfilled') {
      setRobotMapItems(robotMapResult.value.robots)
    } else {
      setRobotMapItems([])
    }
    if (robotResult.status === 'rejected') {
      setCatalogError(robotResult.reason)
    } else if (areaResult.status === 'rejected') {
      setCatalogError(areaResult.reason)
    }
    setCatalogLoading(false)
  }, [])

  const loadRobotPositions = useCallback(async () => {
    try {
      const result = await getRobotMap()
      setRobotMapItems(result.robots)
    } catch {
      setRobotMapItems([])
    }
  }, [])

  function openRobotActivation(robot: RobotOption) {
    activationIdempotencyKeyRef.current = crypto.randomUUID()
    setActivationRobot(robot)
    setActivationError(null)
    setIssuedCredential(null)
  }

  function closeRobotActivation() {
    if (activationSubmitting) {
      return
    }
    activationIdempotencyKeyRef.current = null
    setActivationRobot(null)
    setActivationError(null)
    setIssuedCredential(null)
  }

  async function requestRobotCredential(robot: RobotOption) {
    const idempotencyKey = activationIdempotencyKeyRef.current
      ?? crypto.randomUUID()
    activationIdempotencyKeyRef.current = idempotencyKey
    const credential = await issueRobotCredential(
      robot.robotId,
      idempotencyKey,
    )
    setIssuedCredential(credential)
    await loadCatalogs()
    setCreateRobotId(robot.robotId)
  }

  async function handleRobotActivation() {
    if (activationRobot === null) {
      return
    }

    setActivationSubmitting(true)
    setActivationError(null)
    try {
      await requestRobotCredential(activationRobot)
    } catch (nextError) {
      setActivationError(nextError)
    } finally {
      setActivationSubmitting(false)
    }
  }

  async function handleRegistryReconcileAndRetry() {
    if (activationRobot === null) {
      return
    }

    setActivationSubmitting(true)
    setActivationError(null)
    try {
      await reconcileRobotCredentialRegistry(
        activationRobot.robotId,
        '임무 배정 전 잔존 MQTT Broker 계정 정리',
      )
      await requestRobotCredential(activationRobot)
    } catch (nextError) {
      setActivationError(nextError)
    } finally {
      setActivationSubmitting(false)
    }
  }

  const loadMissionDetail = useCallback(async (missionId: string) => {
    requestedDetailIdRef.current = missionId
    setDetailLoading(true)
    setDetailError(null)
    try {
      const [mission, statusHistory] = await Promise.all([
        getMission(missionId),
        getMissionHistory(missionId),
      ])
      if (requestedDetailIdRef.current !== missionId) {
        return
      }
      setSelectedMission(mission)
      setHistory(statusHistory.history)
    } catch (error) {
      if (requestedDetailIdRef.current === missionId) {
        setDetailError(error)
      }
    } finally {
      if (requestedDetailIdRef.current === missionId) {
        setDetailLoading(false)
      }
    }
  }, [])

  const refreshFromRealtime = useCallback(() => {
    if (realtimeRefreshTimerRef.current !== null) {
      window.clearTimeout(realtimeRefreshTimerRef.current)
    }
    realtimeRefreshTimerRef.current = window.setTimeout(() => {
      requestedListKeyRef.current = null
      void loadMissions(page, appliedFilters)
      if (selectedMissionId !== null) {
        requestedDetailIdRef.current = null
        void loadMissionDetail(selectedMissionId)
      }
      if (createExpanded && createRobotId.length > 0) {
        void loadRobotPositions()
      }
    }, 250)
  }, [
    appliedFilters,
    createExpanded,
    createRobotId,
    loadMissionDetail,
    loadMissions,
    loadRobotPositions,
    page,
    selectedMissionId,
  ])

  useRobotRealtime(refreshFromRealtime)

  useEffect(() => {
    const queryKey = JSON.stringify([page, appliedFilters])
    if (requestedListKeyRef.current === queryKey) {
      return
    }
    requestedListKeyRef.current = queryKey
    void loadMissions(page, appliedFilters)
  }, [appliedFilters, loadMissions, page])

  useEffect(() => {
    if (catalogRequestedRef.current) {
      return
    }
    catalogRequestedRef.current = true
    void loadCatalogs()
  }, [loadCatalogs])

  useEffect(() => {
    if (selectedMissionId === null) {
      requestedDetailIdRef.current = null
      const timer = window.setTimeout(() => {
        setSelectedMission(null)
        setHistory([])
        setDetailError(null)
      }, 0)
      return () => window.clearTimeout(timer)
    }
    if (requestedDetailIdRef.current === selectedMissionId) {
      return
    }

    void loadMissionDetail(selectedMissionId)
  }, [loadMissionDetail, selectedMissionId])

  useEffect(() => () => {
    if (realtimeRefreshTimerRef.current !== null) {
      window.clearTimeout(realtimeRefreshTimerRef.current)
    }
  }, [])

  useEffect(() => {
    if (createAreaId.length === 0) {
      const timer = window.setTimeout(() => {
        setAutonomousRouteId('')
        setCreateRoutesLoading(false)
      }, 0)
      return () => window.clearTimeout(timer)
    }

    let active = true
    const loadingTimer = window.setTimeout(() => {
      if (active) {
        setCreateRoutesLoading(true)
        setFormError(null)
      }
    }, 0)
    void getPatrolRoutes(createAreaId)
      .then((response) => {
        if (!active) {
          return
        }
        const routes = response.routes.filter(
          (route) => (
            route.lifecycleStatus === 'ACTIVE'
            && route.routeCode === 'AUTONOMOUS_DEFAULT'
          ),
        )
        setAutonomousRouteId(routes.at(0)?.patrolRouteId ?? '')
        if (routes.length === 0) {
          setFormError(new Error(
            '선택한 구역의 내부 자율주행 경로 설정을 찾지 못했습니다.',
          ))
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setAutonomousRouteId('')
          setFormError(error)
        }
      })
      .finally(() => {
        if (active) {
          setCreateRoutesLoading(false)
        }
      })
    return () => {
      active = false
      window.clearTimeout(loadingTimer)
    }
  }, [createAreaId])

  function selectMission(missionId: string) {
    if (selectedMissionId === missionId) {
      return
    }
    requestedDetailIdRef.current = null
    setSelectedMissionId(missionId)
    setSelectedMission(null)
    setHistory([])
    setDetailError(null)
    setNotice(null)
  }

  function handleFilter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (
      draftFilters.startedFrom.length > 0
      && draftFilters.startedTo.length > 0
      && new Date(draftFilters.startedFrom) >= new Date(draftFilters.startedTo)
    ) {
      setListError(new Error('시작 시각은 종료 시각보다 빨라야 합니다.'))
      return
    }
    requestedListKeyRef.current = null
    setPage(0)
    setListError(null)
    setAppliedFilters({ ...draftFilters })
  }

  function resetFilters() {
    requestedListKeyRef.current = null
    setDraftFilters({ ...emptyFilters })
    setAppliedFilters({ ...emptyFilters })
    setPage(0)
    setListError(null)
  }

  function changePage(targetPage: number) {
    requestedListKeyRef.current = null
    setPage(targetPage)
    setListError(null)
    setNotice(null)
  }

  function refresh() {
    requestedListKeyRef.current = null
    void loadMissions(page, appliedFilters)
    if (selectedMissionId !== null) {
      requestedDetailIdRef.current = null
      void loadMissionDetail(selectedMissionId)
    }
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canManage) {
      return
    }
    if (
      createRobotId.length === 0
      || createAreaId.length === 0
      || autonomousRouteId.length === 0
    ) {
      setFormError(new Error(
        'Robot과 순찰 구역을 선택하고 내부 경로 설정을 확인해주세요.',
      ))
      return
    }
    const durationMinutes = Number(plannedDurationMinutes)
    if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
      setFormError(new Error('계획 시간은 1분 이상의 정수여야 합니다.'))
      return
    }
    if (
      scheduledStartAt.length > 0
      && new Date(scheduledStartAt).getTime() <= Date.now()
    ) {
      setFormError(new Error('예약 시작 시각은 현재보다 이후여야 합니다.'))
      return
    }

    setSubmitting(true)
    setFormError(null)
    setNotice(null)
    try {
      const created = await createMission({
        robotId: createRobotId,
        patrolAreaId: createAreaId,
        patrolRouteId: autonomousRouteId,
        plannedDurationSec: durationMinutes * 60,
        scheduledStartAt: scheduledStartAt.length > 0
          ? new Date(scheduledStartAt).toISOString()
          : null,
      })
      setNotice(`${created.missionCode} 임무를 생성했습니다.`)
      setCreateRobotId('')
      setCreateAreaId('')
      setAutonomousRouteId('')
      setPlannedDurationMinutes('60')
      setScheduledStartAt('')
      setCreateExpanded(false)
      setDraftFilters({ ...emptyFilters })
      setAppliedFilters({ ...emptyFilters })
      setPage(0)
      requestedListKeyRef.current = null
      await loadMissions(0, emptyFilters)
      requestedDetailIdRef.current = null
      setSelectedMissionId(created.missionId)
    } catch (error) {
      setFormError(error)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="page-stack" aria-labelledby="mission-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">PATROL MISSION</p>
          <h2 id="mission-page-title">순찰 임무 관리</h2>
          <p>배정된 Robot의 임무를 생성하고 진행 상태와 전이 이력을 확인합니다.</p>
        </div>
        <div className="mission-page-actions">
          <button
            className="secondary-button button-with-icon"
            type="button"
            onClick={refresh}
            disabled={listLoading}
          >
            <RefreshCw size={17} aria-hidden="true" />
            새로고침
          </button>
          <NavLink
            className="secondary-button button-with-icon"
            to="/patrol-areas"
          >
            <MapPinned size={17} aria-hidden="true" />
            구역 관리
          </NavLink>
          {canManage ? (
            <button
              className="primary-button button-with-icon"
              type="button"
              onClick={() => setCreateExpanded(true)}
            >
              <Plus size={17} aria-hidden="true" />
              새 임무
            </button>
          ) : null}
        </div>
      </div>

      <div className="summary-grid" aria-label="현재 임무 현황">
        <article className="summary-card">
          <span>조회된 전체 임무</span>
          <strong>{missions?.totalElements ?? '—'}</strong>
          <small>현재 권한 범위 기준</small>
        </article>
        <article className="summary-card">
          <span>현재 페이지 진행 중</span>
          <strong>{missions === null ? '—' : pageRunningCount}</strong>
          <small>RUNNING 상태</small>
        </article>
        <article className="summary-card">
          <span>확인 필요</span>
          <strong>{missions === null ? '—' : pageAttentionCount}</strong>
          <small>PAUSED·FAILED 상태</small>
        </article>
      </div>

      {canManage ? (
        <details
          className="content-card mission-create-card"
          open={createExpanded}
          onToggle={(event) => setCreateExpanded(event.currentTarget.open)}
        >
          <summary>
            <Plus size={17} aria-hidden="true" />
            순찰 임무 생성
          </summary>
          <p className="mission-create-description">
            활성 Robot과 순찰 구역, 계획 시간을 설정합니다. 경로는 로봇이
            구역 안에서 자율 결정하며 임무 코드는 서버에서 자동 생성됩니다.
          </p>
          {catalogError ? (
            <div className="page-alert" role="alert">
              선택 목록 일부를 불러오지 못했습니다. {getApiErrorMessage(catalogError)}
            </div>
          ) : null}
          {formError ? (
            <div className="page-alert" role="alert">
              {getApiErrorMessage(formError)}
            </div>
          ) : null}
          <form className="mission-create-form" onSubmit={handleCreate}>
            <label className="admin-form-field" htmlFor="mission-create-robot">
              Robot
              <select
                id="mission-create-robot"
                value={createRobotId}
                onChange={(event) => setCreateRobotId(event.target.value)}
                disabled={catalogLoading || submitting}
                required
              >
                <option value="">
                  {catalogLoading ? 'Robot 불러오는 중…' : 'Robot 선택'}
                </option>
                {activeRobots.map((robot) => (
                  <option key={robot.robotId} value={robot.robotId}>
                    {robot.robotName} ({robot.mqttId})
                  </option>
                ))}
              </select>
              <small>ACTIVE 상태이며 현재 임무가 없는 Robot만 표시됩니다.</small>
            </label>
            {canRegisterRobot && provisioningRobots.length > 0 ? (
              <div className="mission-robot-activation-notice" role="status">
                <div>
                  <ShieldCheck size={18} aria-hidden="true" />
                  <span>
                    Credential 발급 후 임무 배정 가능한 Robot
                  </span>
                </div>
                <div className="mission-robot-activation-list">
                  {provisioningRobots.map((robot) => (
                    <button
                      key={robot.robotId}
                      className="secondary-button"
                      type="button"
                      onClick={() => openRobotActivation(robot)}
                      disabled={activationSubmitting}
                    >
                      {robot.robotName} ({robot.mqttId}) 발급·활성화
                    </button>
                  ))}
                </div>
              </div>
            ) : null}
            <label className="admin-form-field" htmlFor="mission-create-area">
              순찰 구역
              <select
                id="mission-create-area"
                value={createAreaId}
                onChange={(event) => {
                  setCreateAreaId(event.target.value)
                  setAutonomousRouteId('')
                }}
                disabled={catalogLoading || submitting}
                required
              >
                <option value="">
                  {catalogLoading ? '구역 불러오는 중…' : '활성 구역 선택'}
                </option>
                {activeAreas.map((area) => (
                  <option key={area.patrolAreaId} value={area.patrolAreaId}>
                    {area.areaName} ({area.areaCode})
                  </option>
                ))}
              </select>
              <small>
                등록된 ACTIVE 구역만 표시됩니다. 새 구역은{' '}
                <NavLink to="/patrol-areas">순찰 구역 관리</NavLink>에서
                등록하세요.
              </small>
            </label>
            {selectedCreateArea ? (
              <PatrolAreaSelectionPreview
                area={selectedCreateArea}
                selectedRobot={selectedCreateRobot}
                robotPosition={selectedCreateRobotPosition}
              />
            ) : null}
            <label className="admin-form-field" htmlFor="mission-create-duration">
              계획 시간(분)
              <input
                id="mission-create-duration"
                type="number"
                min="1"
                step="1"
                value={plannedDurationMinutes}
                onChange={(event) => setPlannedDurationMinutes(event.target.value)}
                disabled={submitting}
                required
              />
              <small>진행률과 예상 완료 시각 계산의 기준입니다.</small>
            </label>
            <label className="admin-form-field" htmlFor="mission-create-schedule">
              예약 시작 시각
              <input
                id="mission-create-schedule"
                type="datetime-local"
                value={scheduledStartAt}
                onChange={(event) => setScheduledStartAt(event.target.value)}
                disabled={submitting}
              />
              <small>선택 사항 · 즉시 생성 시 비워두세요.</small>
            </label>
            <button
              className="primary-button mission-create-submit"
              type="submit"
              disabled={
                submitting
                || catalogLoading
                || createRoutesLoading
                || (
                  createAreaId.length > 0
                  && autonomousRouteId.length === 0
                )
              }
            >
              <Plus size={17} aria-hidden="true" />
              {submitting ? '생성 중…' : '임무 생성'}
            </button>
          </form>
        </details>
      ) : (
        <div className="mission-view-only" role="note">
          조회 전용 권한입니다. 임무 생성은 <code>MISSION_MANAGE</code> 권한이
          필요합니다.
        </div>
      )}

      {activationRobot !== null ? (
        <div
          className="mission-robot-activation-backdrop"
          onMouseDown={closeRobotActivation}
        >
          <section
            className="mission-robot-activation-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="mission-robot-activation-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="mission-robot-activation-heading">
              <div>
                <p className="eyebrow">MQTT CREDENTIAL</p>
                <h3 id="mission-robot-activation-title">
                  Robot 발급·활성화
                </h3>
              </div>
              <button
                className="icon-button"
                type="button"
                aria-label="Credential 창 닫기"
                onClick={closeRobotActivation}
                disabled={activationSubmitting}
              >
                <X size={18} aria-hidden="true" />
              </button>
            </div>

            {issuedCredential === null ? (
              <>
                <p>
                  <strong>
                    {activationRobot.robotName} ({activationRobot.mqttId})
                  </strong>
                  의 MQTT Credential을 발급합니다. 성공하면 Robot이 ACTIVE로
                  전환되고 임무 생성 후보로 선택됩니다.
                </p>
                <p className="mission-robot-activation-warning">
                  발급 비밀번호는 성공 직후 이 창에서 한 번만 표시됩니다.
                </p>
                {activationError ? (
                  <>
                    <div className="page-alert" role="alert">
                      발급하지 못했습니다. {getApiErrorMessage(activationError)}
                    </div>
                    {credentialRegistryConflict ? (
                      <p className="mission-robot-registry-recovery">
                        DB에는 활성 Credential이 없지만 Broker에 같은 계정이 남아
                        있습니다. 기존 정합성 복구 API로 잔존 계정을 제거한 뒤
                        새 Credential을 발급할 수 있습니다.
                      </p>
                    ) : null}
                  </>
                ) : null}
                <div className="mission-robot-activation-actions">
                  <button
                    className="secondary-button"
                    type="button"
                    onClick={closeRobotActivation}
                    disabled={activationSubmitting}
                  >
                    취소
                  </button>
                  <button
                    className="primary-button"
                    type="button"
                    onClick={() => void (
                      credentialRegistryConflict
                        ? handleRegistryReconcileAndRetry()
                        : handleRobotActivation()
                    )}
                    disabled={activationSubmitting}
                  >
                    {activationSubmitting
                      ? '처리 중…'
                      : credentialRegistryConflict
                        ? '잔존 계정 정리 후 다시 발급'
                        : '발급·활성화'}
                  </button>
                </div>
              </>
            ) : (
              <>
                <div className="page-alert page-alert-success" role="status">
                  Robot이 ACTIVE로 전환되어 임무 생성 후보에 선택되었습니다.
                </div>
                <dl className="mission-robot-credential-result">
                  <div>
                    <dt>Broker</dt>
                    <dd>{issuedCredential.connection.brokerUri}</dd>
                  </div>
                  <div>
                    <dt>Username</dt>
                    <dd>{issuedCredential.connection.username}</dd>
                  </div>
                  <div>
                    <dt>Client ID</dt>
                    <dd>{issuedCredential.connection.clientId}</dd>
                  </div>
                  <div>
                    <dt>1회 비밀번호</dt>
                    <dd>{issuedCredential.connection.password}</dd>
                  </div>
                </dl>
                <p className="mission-robot-activation-warning">
                  창을 닫기 전에 승인된 경로로 Robot에 연결 정보를 주입하세요.
                </p>
                <div className="mission-robot-activation-actions">
                  <button
                    className="primary-button"
                    type="button"
                    onClick={closeRobotActivation}
                  >
                    확인 후 닫기
                  </button>
                </div>
              </>
            )}
          </section>
        </div>
      ) : null}

      {notice ? (
        <div className="page-notice" role="status">
          {notice}
        </div>
      ) : null}

      <form className="content-card mission-filter-grid" onSubmit={handleFilter}>
        <label className="admin-form-field" htmlFor="mission-filter-robot">
          Robot
          <select
            id="mission-filter-robot"
            value={draftFilters.robotId}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              robotId: event.target.value,
            }))}
          >
            <option value="">전체 Robot</option>
            {robots.map((robot) => (
              <option key={robot.robotId} value={robot.robotId}>
                {robot.robotName} ({robot.mqttId})
              </option>
            ))}
          </select>
        </label>
        <label className="admin-form-field" htmlFor="mission-filter-area">
          순찰 구역
          <select
            id="mission-filter-area"
            value={draftFilters.patrolAreaId}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              patrolAreaId: event.target.value,
            }))}
          >
            <option value="">전체 구역</option>
            {areas.map((area) => (
              <option key={area.patrolAreaId} value={area.patrolAreaId}>
                {area.areaName}
              </option>
            ))}
          </select>
        </label>
        <label className="admin-form-field" htmlFor="mission-filter-status">
          상태
          <select
            id="mission-filter-status"
            value={draftFilters.status}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              status: event.target.value as MissionFilters['status'],
            }))}
          >
            <option value="">전체 상태</option>
            {Object.entries(statusPresentation).map(([status, presentation]) => (
              <option key={status} value={status}>
                {presentation.label}
              </option>
            ))}
          </select>
        </label>
        <label className="admin-form-field" htmlFor="mission-filter-from">
          시작 시각 From
          <input
            id="mission-filter-from"
            type="datetime-local"
            value={draftFilters.startedFrom}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              startedFrom: event.target.value,
            }))}
          />
        </label>
        <label className="admin-form-field" htmlFor="mission-filter-to">
          시작 시각 To
          <input
            id="mission-filter-to"
            type="datetime-local"
            value={draftFilters.startedTo}
            onChange={(event) => setDraftFilters((current) => ({
              ...current,
              startedTo: event.target.value,
            }))}
          />
        </label>
        <div className="mission-filter-actions">
          <button
            className="secondary-button"
            type="button"
            onClick={resetFilters}
          >
            <RotateCcw size={16} aria-hidden="true" />
            초기화
          </button>
          <button className="primary-button" type="submit">
            <Filter size={16} aria-hidden="true" />
            조회
          </button>
        </div>
      </form>

      {listError ? (
        <div className="page-alert" role="alert">
          {getApiErrorMessage(listError)}
        </div>
      ) : null}

      <div className="mission-master-detail">
        <div className="content-card mission-list-card">
          <div className="table-heading">
            <div>
              <h3>임무 목록</h3>
              <p>최근 생성된 임무부터 표시합니다.</p>
            </div>
            <span className="status-badge">{missions?.totalElements ?? 0}건</span>
          </div>

          {listLoading && missions === null ? (
            <div className="admin-loading" role="status">
              <span className="loading-spinner" aria-hidden="true" />
              임무를 불러오는 중입니다.
            </div>
          ) : missions?.content.length === 0 ? (
            <div className="empty-state mission-empty">
              <span className="empty-state-icon" aria-hidden="true">
                <ClipboardList size={22} />
              </span>
              <h3>조건에 맞는 임무가 없습니다.</h3>
              <p>필터를 초기화하거나 새 순찰 임무를 생성해주세요.</p>
            </div>
          ) : (
            <div className="admin-table-wrap">
              <table className="admin-table mission-table">
                <thead>
                  <tr>
                    <th scope="col">임무</th>
                    <th scope="col">Robot</th>
                    <th scope="col">순찰 구역</th>
                    <th scope="col">상태</th>
                    <th scope="col">진행률</th>
                    <th scope="col"><span className="sr-only">상세</span></th>
                  </tr>
                </thead>
                <tbody>
                  {missions?.content.map((mission) => (
                    <tr
                      className={
                        selectedMissionId === mission.missionId
                          ? 'is-selected'
                          : undefined
                      }
                      key={mission.missionId}
                    >
                      <td>
                        <strong>{mission.missionCode}</strong>
                        <small>{formatDateTime(
                          mission.startedAt ?? mission.scheduledStartAt,
                        )}</small>
                      </td>
                      <td>
                        <strong>{mission.robotName}</strong>
                        <small>{mission.mqttId}</small>
                      </td>
                      <td>
                        <strong>{mission.patrolAreaName}</strong>
                        <small>계획 {formatDuration(mission.plannedDurationSec)}</small>
                      </td>
                      <td>
                        <MissionStatusBadge status={mission.status} />
                      </td>
                      <td>
                        <strong className="mission-progress-value">
                          {mission.progressPercent.toFixed(1)}%
                          {mission.progressStale ? (
                            <span className="mission-stale-badge">갱신 지연</span>
                          ) : null}
                        </strong>
                        <small>
                          {formatDuration(mission.elapsedSec)} /{' '}
                          {formatDuration(mission.plannedDurationSec)}
                        </small>
                      </td>
                      <td>
                        <button
                          className="mission-select-button"
                          type="button"
                          onClick={() => selectMission(mission.missionId)}
                          aria-label={`${mission.missionCode} 상세 보기`}
                        >
                          <ChevronRight size={17} aria-hidden="true" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="pagination" aria-label="임무 목록 페이지">
            <button
              className="secondary-button"
              type="button"
              onClick={() => changePage(Math.max(0, page - 1))}
              disabled={page === 0 || listLoading}
            >
              이전
            </button>
            <span>
              {page + 1} / {Math.max(missions?.totalPages ?? 1, 1)}
            </span>
            <button
              className="secondary-button"
              type="button"
              onClick={() => changePage(page + 1)}
              disabled={
                listLoading
                || missions === null
                || page + 1 >= missions.totalPages
              }
            >
              다음
            </button>
          </div>
        </div>

        <aside
          className="content-card mission-detail-card"
          aria-live="polite"
          data-testid="mission-detail"
        >
          {detailLoading ? (
            <div className="admin-loading" role="status">
              <span className="loading-spinner" aria-hidden="true" />
              임무 상세와 상태 이력을 불러오는 중입니다.
            </div>
          ) : detailError ? (
            <div className="page-alert" role="alert">
              {getApiErrorMessage(detailError)}
            </div>
          ) : selectedMission === null ? (
            <div className="empty-state mission-detail-empty">
              <span className="empty-state-icon" aria-hidden="true">
                <History size={22} />
              </span>
              <h3>임무를 선택해주세요.</h3>
              <p>목록에서 임무를 선택하면 상세와 상태 이력이 표시됩니다.</p>
            </div>
          ) : (
            <>
              <div className="mission-detail-heading">
                <div>
                  <MissionStatusBadge status={selectedMission.status} />
                  <h3>{selectedMission.missionCode}</h3>
                  <p>{selectedMission.robotName} · {selectedMission.mqttId}</p>
                </div>
                <span className="mission-detail-updated">
                  갱신 {formatDateTime(selectedMission.updatedAt)}
                </span>
              </div>

              <div
                className="mission-progress-card"
                data-testid="mission-detail-progress"
              >
                <div>
                  <span>
                    <CircleGauge size={16} aria-hidden="true" />
                    임무 진행률
                  </span>
                  <strong>{selectedMission.progressPercent.toFixed(1)}%</strong>
                </div>
                <progress
                  max={100}
                  value={selectedMission.progressPercent}
                  aria-label="임무 진행률"
                />
                <small>
                  예상 완료 {formatDateTime(
                    selectedMission.estimatedCompletionAt,
                  )}
                </small>
                {selectedMission.progressStale ? (
                  <p className="mission-stale-notice" role="status">
                    최근 15초 동안 진행 텔레메트리가 없어 표시값이 지연되고 있습니다.
                  </p>
                ) : null}
                <div className="mission-time-grid">
                  <div>
                    <span>계획</span>
                    <strong>
                      {formatDuration(selectedMission.plannedDurationSec)}
                    </strong>
                  </div>
                  <div>
                    <span>경과</span>
                    <strong>{formatDuration(selectedMission.elapsedSec)}</strong>
                  </div>
                  <div>
                    <span>남은 시간</span>
                    <strong>
                      {formatDuration(selectedMission.remainingDurationSec)}
                    </strong>
                  </div>
                  <div>
                    <span>기준 시각</span>
                    <strong>
                      {formatDateTime(selectedMission.progressObservedAt)}
                    </strong>
                  </div>
                </div>
              </div>

              <MissionMap
                missionId={selectedMission.missionId}
                missionCode={selectedMission.missionCode}
                patrolAreaName={selectedMission.patrolAreaName}
              />

              <dl className="mission-detail-grid">
                <div>
                  <dt><Bot size={14} aria-hidden="true" /> Robot</dt>
                  <dd>{selectedMission.robotName}</dd>
                </div>
                <div>
                  <dt><Route size={14} aria-hidden="true" /> 순찰 구역</dt>
                  <dd>{selectedMission.patrolAreaName}</dd>
                </div>
                <div>
                  <dt><CalendarClock size={14} aria-hidden="true" /> 예약 시각</dt>
                  <dd>{formatDateTime(selectedMission.scheduledStartAt)}</dd>
                </div>
                <div>
                  <dt>실제 시작</dt>
                  <dd>{formatDateTime(selectedMission.startedAt)}</dd>
                </div>
                <div>
                  <dt>종료</dt>
                  <dd>{formatDateTime(selectedMission.endedAt)}</dd>
                </div>
                <div>
                  <dt><Timer size={14} aria-hidden="true" /> 예상 완료</dt>
                  <dd>{formatDateTime(selectedMission.estimatedCompletionAt)}</dd>
                </div>
              </dl>

              {selectedMission.completionReason ? (
                <div className="mission-completion-reason">
                  <strong>종료 사유</strong>
                  <p>{selectedMission.completionReason}</p>
                </div>
              ) : null}

              <div className="mission-result-grid">
                <div>
                  <span>운행 시간</span>
                  <strong>{formatDuration(selectedMission.resultDurationSec)}</strong>
                </div>
                <div>
                  <span>탐지 이벤트</span>
                  <strong>{selectedMission.resultIncidentCount ?? '—'}</strong>
                </div>
                <div>
                  <span>정상 탐지</span>
                  <strong>
                    {selectedMission.resultNormalDetectionCount ?? '—'}
                  </strong>
                </div>
              </div>

              <section
                className="mission-history"
                aria-labelledby="history-title"
                data-testid="mission-detail-history"
              >
                <div className="mission-section-heading">
                  <div>
                    <History size={17} aria-hidden="true" />
                    <h4 id="history-title">상태 이력</h4>
                  </div>
                  <span>{history.length}건</span>
                </div>
                {history.length === 0 ? (
                  <p className="mission-history-empty">기록된 상태 전이가 없습니다.</p>
                ) : (
                  <ol className="mission-history-list">
                    {history.map((item) => (
                      <HistoryItem key={item.historyId} item={item} />
                    ))}
                  </ol>
                )}
              </section>
            </>
          )}
        </aside>
      </div>
    </section>
  )
}
