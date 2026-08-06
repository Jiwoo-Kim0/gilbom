import {
  Filter,
  Plus,
  RotateCcw,
  Search,
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
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import { ROBOT_REGISTER } from '../auth/permissions'
import { RobotFleetGrid } from './RobotFleetGrid'
import { RobotMap } from './RobotMap'
import { RobotCredentialManager } from './RobotCredentialManager'
import { RobotSystemLog } from './RobotSystemLog'
import {
  getRobotMap,
  getRobots,
  getRobotSummary,
  registerRobot,
} from './robotApi'
import type {
  RobotFleetItem,
  RobotListItem,
  RobotListResponse,
  RobotMapItem,
  RobotMarker,
  RobotStatus,
  RobotSummary,
  RobotStreamStatus,
} from './robotTypes'
import { useRobotRealtime } from './useRobotRealtime'
import { SEOUL_TIME_ZONE } from '../../shared/time/seoulTime.ts'

const PAGE_SIZE = 20
const SNAPSHOT_DEBOUNCE_MILLIS = 400

const streamStatusLabel: Record<RobotStreamStatus, string> = {
  connecting: '연결 중',
  connected: '실시간 연결',
  reconnecting: '재연결 중',
}

function toRobotStatus(robot: RobotListItem): RobotStatus {
  if (robot.connectivityStatus === 'OFFLINE') {
    return 'offline'
  }
  if (
    robot.overallHealthStatus === 'WARNING'
    || robot.overallHealthStatus === 'CRITICAL'
  ) {
    return 'warning'
  }
  if (robot.charging === true) {
    return 'charging'
  }
  return 'online'
}

function toFleetItem(robot: RobotListItem): RobotFleetItem {
  return {
    id: robot.robotId,
    name: robot.robotName,
    mqttId: robot.mqttId,
    serialNumber: robot.serialNumber,
    status: toRobotStatus(robot),
    connectivityStatus: robot.connectivityStatus,
    batteryPercent: robot.batteryPercent,
    jetsonTempC: robot.jetsonTempC,
    speedMps: robot.speedMps,
    lastReceivedAt: robot.lastReceivedAt,
    robotMode: robot.robotMode,
    missionStatus: robot.missionStatus,
    overallHealthStatus: robot.overallHealthStatus,
  }
}

function toMarker(
  robot: RobotMapItem,
  listItem: RobotListItem | undefined,
): RobotMarker | null {
  if (
    robot.latitude === null
    || robot.longitude === null
    || !Number.isFinite(robot.latitude)
    || !Number.isFinite(robot.longitude)
  ) {
    return null
  }

  return {
    id: robot.robotId,
    name: robot.robotName,
    lat: robot.latitude,
    lng: robot.longitude,
    headingDeg: robot.headingDeg,
    missionStatus: robot.missionStatus,
    status: listItem === undefined
      ? robot.connectivityStatus === 'ONLINE' ? 'online' : 'offline'
      : toRobotStatus(listItem),
  }
}

function formatDateTime(value: string | null): string {
  if (value === null) {
    return '아직 수신되지 않음'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: SEOUL_TIME_ZONE,
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(parsed)
}

export function RobotManagePage() {
  const [page, setPage] = useState(0)
  const [searchDraft, setSearchDraft] = useState('')
  const [search, setSearch] = useState('')
  const [robots, setRobots] = useState<RobotListResponse | null>(null)
  const [summary, setSummary] = useState<RobotSummary | null>(null)
  const [mapRobots, setMapRobots] = useState<RobotMapItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<unknown>(null)
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(null)
  const [registrationOpen, setRegistrationOpen] = useState(false)
  const [registrationName, setRegistrationName] = useState('')
  const [registrationSerial, setRegistrationSerial] = useState('')
  const [registrationError, setRegistrationError] = useState<unknown>(null)
  const [registrationSubmitting, setRegistrationSubmitting] = useState(false)
  const [registrationNotice, setRegistrationNotice] = useState<string | null>(null)
  const requestSequenceRef = useRef(0)
  const refreshTimerRef = useRef<number | null>(null)
  const { user } = useAuthentication()
  const canRegister = user?.permissions.includes(ROBOT_REGISTER) ?? false

  const loadSnapshot = useCallback(async () => {
    const requestSequence = requestSequenceRef.current + 1
    requestSequenceRef.current = requestSequence
    setLoading(true)
    setError(null)

    try {
      const [nextRobots, nextSummary, nextMap] = await Promise.all([
        getRobots(page, PAGE_SIZE, search),
        getRobotSummary(),
        getRobotMap(),
      ])
      if (requestSequence !== requestSequenceRef.current) {
        return
      }
      setRobots(nextRobots)
      setSummary(nextSummary)
      setMapRobots(nextMap.robots)
      setLastSyncedAt(new Date().toISOString())
    } catch (nextError) {
      if (requestSequence === requestSequenceRef.current) {
        setError(nextError)
      }
    } finally {
      if (requestSequence === requestSequenceRef.current) {
        setLoading(false)
      }
    }
  }, [page, search])

  const scheduleSnapshot = useCallback(() => {
    if (refreshTimerRef.current !== null) {
      window.clearTimeout(refreshTimerRef.current)
    }
    refreshTimerRef.current = window.setTimeout(() => {
      refreshTimerRef.current = null
      void loadSnapshot()
    }, SNAPSHOT_DEBOUNCE_MILLIS)
  }, [loadSnapshot])

  const {
    status: streamStatus,
    lastHeartbeatAt,
    logs,
  } = useRobotRealtime(scheduleSnapshot)

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadSnapshot()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [loadSnapshot])

  useEffect(() => () => {
    if (refreshTimerRef.current !== null) {
      window.clearTimeout(refreshTimerRef.current)
    }
  }, [scheduleSnapshot])

  const fleetItems = useMemo(
    () => robots?.content.map(toFleetItem) ?? [],
    [robots],
  )
  const markerItems = useMemo(() => {
    const listById = new Map(
      robots?.content.map((robot) => [robot.robotId, robot]) ?? [],
    )
    return mapRobots
      .map((robot) => toMarker(robot, listById.get(robot.robotId)))
      .filter((robot): robot is RobotMarker => robot !== null)
  }, [mapRobots, robots])
  const attentionCount = summary === null
    ? null
    : summary.warningRobots + summary.criticalRobots

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedSearch = searchDraft.trim()
    if (page === 0 && normalizedSearch === search) {
      void loadSnapshot()
      return
    }
    setPage(0)
    setSearch(normalizedSearch)
  }

  function resetSearch() {
    setSearchDraft('')
    if (page === 0 && search.length === 0) {
      void loadSnapshot()
      return
    }
    setPage(0)
    setSearch('')
  }

  function openRegistration() {
    setRegistrationName('')
    setRegistrationSerial('')
    setRegistrationError(null)
    setRegistrationOpen(true)
  }

  function closeRegistration() {
    if (!registrationSubmitting) {
      setRegistrationOpen(false)
    }
  }

  async function handleRegistration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const robotName = registrationName.trim()
    const serialNumber = registrationSerial.trim()
    if (robotName.length === 0 || serialNumber.length === 0) {
      setRegistrationError(new Error('로봇 이름과 시리얼 번호를 입력해주세요.'))
      return
    }

    setRegistrationSubmitting(true)
    setRegistrationError(null)
    try {
      const registered = await registerRobot({ robotName, serialNumber })
      setRegistrationOpen(false)
      setRegistrationNotice(
        '로봇이 등록되었습니다. MQTT ID: ' + registered.mqttId,
      )
      await loadSnapshot()
    } catch (nextError) {
      setRegistrationError(nextError)
    } finally {
      setRegistrationSubmitting(false)
    }
  }

  return (
    <section className="page-stack" aria-labelledby="robot-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">FLEET</p>
          <h2 id="robot-page-title">로봇 상태 모니터링</h2>
          <p>권한 범위 내 로봇의 최신 위치 및 운영 데이터를 추적합니다.</p>
        </div>

        <div className="robot-page-actions">
          {canRegister ? (
            <button
              className="primary-button button-with-icon"
              type="button"
              onClick={openRegistration}
            >
              <Plus size={16} aria-hidden="true" />
              로봇 등록
            </button>
          ) : null}
        </div>
      </div>

      {registrationNotice ? (
        <div className="page-alert page-alert-success" role="status">
          {registrationNotice}
        </div>
      ) : null}

      <div className="robot-realtime-bar" aria-live="polite">
        <span className={`robot-stream-status robot-stream-status-${streamStatus}`}>
          <span aria-hidden="true" />
          {streamStatusLabel[streamStatus]}
        </span>
        <span>서버 90초 기준 ONLINE/OFFLINE</span>
        <span>전체 현황 갱신 {formatDateTime(lastSyncedAt)}</span>
        <span>Heartbeat {formatDateTime(lastHeartbeatAt)}</span>
      </div>


      <form className="content-card robot-filter-bar" onSubmit={handleSearch}>
        <label htmlFor="robot-search">
          <Filter size={16} aria-hidden="true" />
          로봇 검색
        </label>
        <div className="robot-filter-controls">
          <div className="robot-search-field">
            <Search size={16} aria-hidden="true" />
            <input
              id="robot-search"
              type="search"
              value={searchDraft}
              maxLength={100}
              placeholder="이름, MQTT ID, 시리얼 번호"
              onChange={(event) => setSearchDraft(event.target.value)}
            />
          </div>
          <button className="primary-button" type="submit" disabled={loading}>
            조회
          </button>
          <button
            className="secondary-button"
            type="button"
            onClick={resetSearch}
            disabled={loading && search.length === 0}
          >
            <RotateCcw size={15} aria-hidden="true" />
            초기화
          </button>
        </div>
      </form>

      {error ? (
        <div className="page-alert" role="alert">
          최신 로봇 상태를 불러오지 못했습니다. {getApiErrorMessage(error)}
        </div>
      ) : null}

      <RobotMap markers={markerItems} />

      <div className="summary-grid" aria-label="로봇 현황">
        <article className="summary-card">
          <span>전체 로봇</span>
          <strong>{summary?.totalRobots ?? '—'}</strong>
          <small>현재 사용자 조회 범위</small>
        </article>
        <article className="summary-card">
          <span>온라인</span>
          <strong>{summary?.onlineRobots ?? '—'}</strong>
          <small>오프라인 {summary?.offlineRobots ?? '—'}대</small>
        </article>
        <article className="summary-card">
          <span>확인 필요</span>
          <strong>{attentionCount ?? '—'}</strong>
          <small>경고·심각 상태 합계</small>
        </article>
      </div>

      <RobotFleetGrid robots={fleetItems} loading={loading} />

      <div className="pagination" aria-label="로봇 목록 페이지">
        <button
          className="secondary-button"
          type="button"
          onClick={() => setPage((current) => Math.max(0, current - 1))}
          disabled={loading || page === 0}
        >
          이전
        </button>
        <span>
          {robots === null || robots.totalPages === 0
            ? '0 / 0'
            : `${page + 1} / ${robots.totalPages}`}
        </span>
        <button
          className="secondary-button"
          type="button"
          onClick={() => setPage((current) => current + 1)}
          disabled={
            loading
            || robots === null
            || robots.totalPages === 0
            || page + 1 >= robots.totalPages
          }
        >
          다음
        </button>
      </div>

      <RobotCredentialManager onCredentialChanged={loadSnapshot} />

      <RobotSystemLog logs={logs} />

      {registrationOpen ? (
        <div className="robot-registration-backdrop" onMouseDown={closeRegistration}>
          <section
            className="robot-registration-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="robot-registration-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="robot-registration-heading">
              <div>
                <p className="eyebrow">ROBOT REGISTRATION</p>
                <h3 id="robot-registration-title">로봇 등록</h3>
              </div>
              <button
                className="icon-button"
                type="button"
                aria-label="등록 창 닫기"
                onClick={closeRegistration}
                disabled={registrationSubmitting}
              >
                <X size={18} aria-hidden="true" />
              </button>
            </div>
            <p className="robot-registration-description">
              등록 후 MQTT ID가 발급되며, 이미지 업로드 API Key는 별도로 발급합니다.
            </p>
            <form className="robot-registration-form" onSubmit={handleRegistration}>
              <label className="admin-form-field" htmlFor="robot-registration-name">
                로봇 이름
                <input
                  id="robot-registration-name"
                  value={registrationName}
                  maxLength={100}
                  autoFocus
                  required
                  onChange={(event) => setRegistrationName(event.target.value)}
                  placeholder="시연 로봇 1호"
                />
              </label>
              <label className="admin-form-field" htmlFor="robot-registration-serial">
                시리얼 번호
                <input
                  id="robot-registration-serial"
                  value={registrationSerial}
                  maxLength={100}
                  required
                  onChange={(event) => setRegistrationSerial(event.target.value)}
                  placeholder="DEMO-ROBOT-001"
                />
              </label>
              {registrationError ? (
                <div className="page-alert" role="alert">
                  등록하지 못했습니다. {getApiErrorMessage(registrationError)}
                </div>
              ) : null}
              <div className="robot-registration-actions">
                <button
                  className="secondary-button"
                  type="button"
                  onClick={closeRegistration}
                  disabled={registrationSubmitting}
                >
                  취소
                </button>
                <button
                  className="primary-button"
                  type="submit"
                  disabled={registrationSubmitting}
                >
                  {registrationSubmitting ? '등록 중…' : '등록하기'}
                </button>
              </div>
            </form>
          </section>
        </div>
      ) : null}
    </section>
  )
}
