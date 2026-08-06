import {
  CheckCircle2,
  CircleStop,
  Pause,
  Play,
  RefreshCw,
  RotateCcw,
  ShieldCheck,
  XCircle,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import {
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import {
  MISSION_VIEW,
  ROBOT_COMMAND_EXECUTE,
} from '../auth/permissions'
import { getMissions } from '../mission/missionApi'
import { MissionMap } from '../mission/MissionMap'
import type { Mission } from '../mission/missionTypes'
import type {
  RobotDetail,
  RobotStreamStatus,
} from '../robot/robotTypes'
import {
  CommandCreateAttempt,
  createRobotCommand,
  listRobotCommands,
} from './commandApi'
import { CommandConfirmModal } from './CommandConfirmModal'
import { CommandHistoryPanel } from './CommandHistoryPanel'
import {
  COMMAND_DEFINITIONS,
  IN_FLIGHT_COMMAND_STATUSES,
  buildCommandRequest,
  getCommandAvailability,
} from './commandPolicy'
import type {
  CommandDefinition,
} from './commandPolicy'
import type {
  CommandListResponse,
  CommandResponse,
  CommandType,
} from './commandTypes'
import './command.css'

const PAGE_SIZE = 20
const MISSION_PAGE_SIZE = 100
const IMMEDIATE_COMMAND_REASONS: Partial<Record<CommandType, string>> = {
  PAUSE_MISSION: '운영자 즉시 일시정지',
  EMERGENCY_STOP: '운영자 즉시 긴급정지',
}

const commandIcons = {
  START_MISSION: Play,
  PAUSE_MISSION: Pause,
  RESUME_MISSION: RotateCcw,
  COMPLETE_MISSION: CheckCircle2,
  CANCEL_MISSION: XCircle,
  EMERGENCY_STOP: CircleStop,
  CLEAR_EMERGENCY_STOP: ShieldCheck,
} as const

function commandLoadErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 404) {
    return '로봇 또는 제어 내역을 찾을 수 없습니다.'
  }
  return getApiErrorMessage(error)
}

function findCurrentMission(
  missions: readonly Mission[],
  missionStatus: string | null,
): Mission | null {
  if (missionStatus !== null) {
    const exact = missions.find((mission) => mission.status === missionStatus)
    if (exact !== undefined) {
      return exact
    }
  }
  return missions.find((mission) => (
    mission.status === 'READY'
    || mission.status === 'RUNNING'
    || mission.status === 'PAUSED'
  )) ?? null
}

function upsertCreatedCommand(
  current: CommandListResponse | null,
  command: CommandResponse,
): CommandListResponse {
  if (current === null) {
    return {
      content: [command],
      page: 0,
      size: PAGE_SIZE,
      totalElements: 1,
      totalPages: 1,
    }
  }

  const existed = current.content.some((item) => (
    item.commandId === command.commandId
  ))
  const totalElements = existed
    ? current.totalElements
    : current.totalElements + 1
  return {
    ...current,
    content: [
      command,
      ...current.content.filter((item) => (
        item.commandId !== command.commandId
      )),
    ].slice(0, current.size),
    totalElements,
    totalPages: Math.ceil(totalElements / current.size),
  }
}

type CommandControlPageProps = {
  robot: RobotDetail
  streamStatus: RobotStreamStatus
  realtimeRevision: number
}

export function CommandControlPage({
  robot,
  streamStatus,
  realtimeRevision,
}: CommandControlPageProps) {
  const { user } = useAuthentication()
  const [commands, setCommands] = useState<CommandListResponse | null>(null)
  const [commandSnapshotRevision, setCommandSnapshotRevision] = useState(0)
  const [currentMission, setCurrentMission] = useState<Mission | null>(null)
  const [loading, setLoading] = useState(false)
  const [missionLoading, setMissionLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [missionError, setMissionError] = useState<unknown>(null)
  const [selectedCommand, setSelectedCommand] = useState<CommandType | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [createdCommand, setCreatedCommand] = useState<CommandResponse | null>(
    null,
  )
  const [immediateCommandError, setImmediateCommandError] = useState<
    string | null
  >(null)
  const [immediateCommandNotice, setImmediateCommandNotice] = useState<
    string | null
  >(null)
  const submitInFlightRef = useRef(false)
  const attemptRef = useRef<CommandCreateAttempt | null>(null)
  const canExecute = user?.permissions.includes(ROBOT_COMMAND_EXECUTE) ?? false
  const canViewMissions = user?.permissions.includes(MISSION_VIEW) ?? false

  if (attemptRef.current === null) {
    attemptRef.current = new CommandCreateAttempt()
  }

  const loadCommands = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setCommands(await listRobotCommands(robot.robotId, 0, PAGE_SIZE))
      setCommandSnapshotRevision((current) => current + 1)
    } catch (nextError) {
      setCommands(null)
      setError(nextError)
    } finally {
      setLoading(false)
    }
  }, [robot.robotId])

  const loadMission = useCallback(async () => {
    if (!canViewMissions) {
      setCurrentMission(null)
      setMissionError(null)
      return
    }
    setMissionLoading(true)
    setMissionError(null)
    try {
      const response = await getMissions(0, MISSION_PAGE_SIZE, {
        robotId: robot.robotId,
        patrolAreaId: '',
        scope: 'ALL',
        status: '',
        startedFrom: '',
        startedTo: '',
      })
      setCurrentMission(findCurrentMission(
        response.content,
        robot.missionStatus,
      ))
    } catch (nextError) {
      setCurrentMission(null)
      setMissionError(nextError)
    } finally {
      setMissionLoading(false)
    }
  }, [canViewMissions, robot.missionStatus, robot.robotId])

  const loadControlData = useCallback(async () => {
    await Promise.all([
      loadCommands(),
      loadMission(),
    ])
  }, [loadCommands, loadMission])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadControlData()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [loadControlData, realtimeRevision])

  const pendingCommandTypes = useMemo(() => (
    commands?.content
      .filter((command) => (
        IN_FLIGHT_COMMAND_STATUSES.includes(command.commandStatus)
      ))
      .map((command) => command.commandType) ?? []
  ), [commands])
  const missionCatalogIsAuthoritative = canViewMissions
    && !missionLoading
    && missionError === null
  const effectiveMissionStatus = currentMission?.status
    ?? (missionCatalogIsAuthoritative ? null : robot.missionStatus)

  const selectedDefinition = selectedCommand === null
    ? null
    : COMMAND_DEFINITIONS.find((definition) => (
      definition.commandType === selectedCommand
    )) ?? null

  function openConfirmModal(definition: CommandDefinition) {
    const availability = getCommandAvailability(
      definition.commandType,
      {
        connectivityStatus: robot.connectivityStatus,
        operationalStatus: robot.operationalStatus,
        robotMode: robot.robotMode,
        missionStatus: effectiveMissionStatus,
        hasMissionTarget: currentMission !== null,
        pendingCommandTypes,
      },
    )
    if (!availability.enabled || !canExecute) {
      return
    }

    attemptRef.current?.reset()
    setSelectedCommand(definition.commandType)
    setSubmitError(null)
    setCreatedCommand(null)
    setImmediateCommandError(null)
    setImmediateCommandNotice(null)
  }

  function issueImmediateCommand(definition: CommandDefinition) {
    const reason = IMMEDIATE_COMMAND_REASONS[definition.commandType]
    if (reason === undefined) {
      openConfirmModal(definition)
      return
    }

    const availability = getCommandAvailability(
      definition.commandType,
      {
        connectivityStatus: robot.connectivityStatus,
        operationalStatus: robot.operationalStatus,
        robotMode: robot.robotMode,
        missionStatus: effectiveMissionStatus,
        hasMissionTarget: currentMission !== null,
        pendingCommandTypes,
      },
    )
    if (!availability.enabled || !canExecute) {
      return
    }

    attemptRef.current?.reset()
    setSelectedCommand(null)
    setSubmitError(null)
    setCreatedCommand(null)
    setImmediateCommandError(null)
    setImmediateCommandNotice(null)
    void submitCommand(definition.commandType, reason, true)
  }

  const closeConfirmModal = useCallback(() => {
    if (submitInFlightRef.current) {
      return
    }
    attemptRef.current?.reset()
    setSelectedCommand(null)
    setSubmitError(null)
    setCreatedCommand(null)
  }, [])

  async function submitCommand(
    commandType: CommandType,
    reason: string,
    immediate = false,
  ) {
    if (
      submitInFlightRef.current
      || attemptRef.current === null
    ) {
      return
    }

    let input
    try {
      input = buildCommandRequest(
        commandType,
        currentMission,
        reason,
      )
    } catch (nextError) {
      const message = getApiErrorMessage(nextError)
      if (immediate) {
        setImmediateCommandError(message)
      } else {
        setSubmitError(message)
      }
      return
    }

    submitInFlightRef.current = true
    setSubmitting(true)
    if (immediate) {
      setImmediateCommandError(null)
      setImmediateCommandNotice(null)
    } else {
      setSubmitError(null)
    }
    try {
      const command = await createRobotCommand(
        robot.robotId,
        input,
        attemptRef.current,
      )
      if (immediate) {
        const label = COMMAND_DEFINITIONS.find((definition) => (
          definition.commandType === commandType
        ))?.label ?? commandType
        setImmediateCommandNotice(`${label} 명령을 즉시 발행했습니다.`)
      } else {
        setCreatedCommand(command)
      }
      setCommands((current) => upsertCreatedCommand(current, command))
    } catch (nextError) {
      const message = commandLoadErrorMessage(nextError)
      if (immediate) {
        setImmediateCommandError(message)
      } else {
        setSubmitError(message)
      }
    } finally {
      submitInFlightRef.current = false
      setSubmitting(false)
    }
  }

  return (
    <section className="command-section" aria-labelledby="command-section-title">
      {canViewMissions && (missionLoading || currentMission !== null) ? (
        <article
          className="content-card command-mission-map-card"
          data-testid="command-mission-map"
        >
          {currentMission === null ? (
            <div className="admin-loading" role="status">
              <span className="loading-spinner" aria-hidden="true" />
              현재 임무 지도를 준비하는 중입니다.
            </div>
          ) : (
            <MissionMap
              key={currentMission.missionId}
              missionId={currentMission.missionId}
              missionCode={currentMission.missionCode}
              patrolAreaName={currentMission.patrolAreaName}
            />
          )}
        </article>
      ) : null}
      <div className="command-section-heading">
        <div>
          <p className="eyebrow">CONTROL</p>
          <h3 id="command-section-title">명령 관리</h3>
          <p>현재 로봇·Mission 상태에 허용되는 명령만 실행할 수 있습니다.</p>
        </div>
        <div className="command-page-actions">
          <span className={`command-access-badge${canExecute ? ' can-execute' : ''}`}>
            {canExecute ? '명령 발행 가능' : '조회 전용'}
          </span>
          <button
            className="secondary-button"
            type="button"
            onClick={() => void loadControlData()}
            disabled={loading || missionLoading}
          >
            <RefreshCw size={16} aria-hidden="true" />
            제어 상태 새로고침
          </button>
        </div>
      </div>

      {error ? (
        <div className="page-alert" role="alert">
          {commandLoadErrorMessage(error)}
        </div>
      ) : null}
      {missionError ? (
        <div className="page-alert" role="alert">
          Mission 정보를 불러오지 못했습니다. {getApiErrorMessage(missionError)}
        </div>
      ) : null}
      {immediateCommandError !== null ? (
        <div className="page-alert command-immediate-feedback" role="alert">
          {immediateCommandError}
        </div>
      ) : null}
      {immediateCommandNotice !== null ? (
        <div
          className="command-immediate-feedback success"
          role="status"
        >
          {immediateCommandNotice}
        </div>
      ) : null}

      <article className="content-card command-controls-card">
        <div className="command-controls-heading">
          <div>
            <p className="eyebrow">REMOTE CONTROL</p>
            <h3>로봇 제어</h3>
          </div>
          <div className="command-current-context">
            <span>Robot <strong>{robot.robotMode ?? '—'}</strong></span>
            <span>Mission <strong>{effectiveMissionStatus ?? '—'}</strong></span>
            {currentMission !== null ? (
              <span>Code <strong>{currentMission.missionCode}</strong></span>
            ) : null}
          </div>
        </div>

        {!canExecute ? (
          <div className="command-readonly-notice">
            <ShieldCheck size={18} aria-hidden="true" />
            명령 실행 권한이 없어 조회 전용으로 표시됩니다.
          </div>
        ) : (
          <div className="command-button-grid">
            {COMMAND_DEFINITIONS.map((definition) => {
              const availability = getCommandAvailability(
                definition.commandType,
                {
                  connectivityStatus: robot.connectivityStatus,
                  operationalStatus: robot.operationalStatus,
                  robotMode: robot.robotMode,
                  missionStatus: effectiveMissionStatus,
                  hasMissionTarget: currentMission !== null,
                  pendingCommandTypes,
                },
              )
              const Icon = commandIcons[definition.commandType]
              const helpId = `command-help-${definition.commandType}`
              return (
                <div
                  key={definition.commandType}
                  className={`command-action${
                    definition.tone === 'danger' ? ' danger' : ''
                  }`}
                >
                  <button
                    className="command-action-button"
                    type="button"
                    aria-describedby={helpId}
                    disabled={!availability.enabled || submitting}
                    onClick={() => issueImmediateCommand(definition)}
                  >
                    <Icon size={18} aria-hidden="true" />
                    {definition.label}
                  </button>
                  <small id={helpId}>
                    {availability.reason ?? definition.impact}
                  </small>
                </div>
              )
            })}
          </div>
        )}
      </article>

      <article className="content-card command-foundation-card">
        <div>
          <p className="eyebrow">COMMAND API</p>
          <h3>명령 데이터 연결</h3>
        </div>
        {loading ? (
          <p aria-live="polite">명령 정본을 불러오는 중입니다.</p>
        ) : commands === null ? (
          <p>명령 데이터를 불러오지 못했습니다.</p>
        ) : (
          <dl className="command-foundation-summary">
            <div>
              <dt>전체 명령</dt>
              <dd>{commands.totalElements}</dd>
            </div>
            <div>
              <dt>처리 중</dt>
              <dd>{pendingCommandTypes.length}</dd>
            </div>
            <div>
              <dt>접근 수준</dt>
              <dd>{canExecute ? '조회·발행' : '조회'}</dd>
            </div>
            <div>
              <dt>실시간 연결</dt>
              <dd>{streamStatus}</dd>
            </div>
          </dl>
        )}
        <p className="command-foundation-note">
          화면의 상태 검사는 조작 실수를 줄이기 위한 보조 정책이며 Backend가
          권한·Robot 범위·최종 업무 상태를 다시 검증합니다.
        </p>
      </article>

      <CommandHistoryPanel
        commands={commands}
        loading={loading}
        streamStatus={streamStatus}
        refreshRevision={commandSnapshotRevision}
      />

      {selectedDefinition !== null ? (
        <CommandConfirmModal
          key={selectedDefinition.commandType}
          definition={selectedDefinition}
          robot={robot}
          mission={currentMission}
          submitting={submitting}
          submitError={submitError}
          createdCommand={createdCommand}
          onClose={closeConfirmModal}
          onConfirm={(reason) => void submitCommand(
            selectedDefinition.commandType,
            reason,
          )}
        />
      ) : null}
    </section>
  )
}
