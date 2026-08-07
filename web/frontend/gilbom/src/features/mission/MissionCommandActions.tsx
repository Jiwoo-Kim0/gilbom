import {
  CheckCircle2,
  Pause,
  Play,
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
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import {
  ROBOT_COMMAND_EXECUTE,
  ROBOT_COMMAND_VIEW,
} from '../auth/permissions'
import {
  CommandCreateAttempt,
  createRobotCommand,
  listRobotCommands,
} from '../command/commandApi'
import { CommandConfirmModal } from '../command/CommandConfirmModal'
import {
  COMMAND_DEFINITIONS,
  IMMEDIATE_COMMAND_REASONS,
  IN_FLIGHT_COMMAND_STATUSES,
  buildCommandRequest,
  getCommandAvailability,
} from '../command/commandPolicy'
import type { CommandDefinition } from '../command/commandPolicy'
import type {
  CommandResponse,
  CommandType,
} from '../command/commandTypes'
import '../command/command.css'
import { getRobot } from '../robot/robotApi'
import { getRobotStatusLabel } from '../robot/robotStatusPresentation'
import type { RobotDetail } from '../robot/robotTypes'
import type { Mission } from './missionTypes'

type MissionCommandType = Extract<
  CommandType,
  | 'START_MISSION'
  | 'PAUSE_MISSION'
  | 'RESUME_MISSION'
  | 'COMPLETE_MISSION'
  | 'CANCEL_MISSION'
>

const MISSION_COMMAND_TYPES: readonly MissionCommandType[] = [
  'START_MISSION',
  'PAUSE_MISSION',
  'RESUME_MISSION',
  'COMPLETE_MISSION',
  'CANCEL_MISSION',
]

const commandIcons = {
  START_MISSION: Play,
  PAUSE_MISSION: Pause,
  RESUME_MISSION: RotateCcw,
  COMPLETE_MISSION: CheckCircle2,
  CANCEL_MISSION: XCircle,
} as const

function isMissionCommandType(
  commandType: CommandType,
): commandType is MissionCommandType {
  return MISSION_COMMAND_TYPES.includes(commandType as MissionCommandType)
}

const missionCommandDefinitions = COMMAND_DEFINITIONS.filter(
  (definition): definition is CommandDefinition & {
    commandType: MissionCommandType
  } => isMissionCommandType(definition.commandType),
)

type MissionCommandActionsProps = {
  mission: Mission
  realtimeRevision: number
  onCommandCreated: () => void
}

export function MissionCommandActions({
  mission,
  realtimeRevision,
  onCommandCreated,
}: MissionCommandActionsProps) {
  const { user } = useAuthentication()
  const [robot, setRobot] = useState<RobotDetail | null>(null)
  const [pendingCommandTypes, setPendingCommandTypes] = useState<CommandType[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [selectedCommand, setSelectedCommand] = useState<CommandType | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [createdCommand, setCreatedCommand] = useState<CommandResponse | null>(null)
  const [immediateCommandError, setImmediateCommandError] = useState<
    string | null
  >(null)
  const [immediateCommandNotice, setImmediateCommandNotice] = useState<
    string | null
  >(null)
  const submitInFlightRef = useRef(false)
  const attemptRef = useRef(new CommandCreateAttempt())
  const canView = user?.permissions.includes(ROBOT_COMMAND_VIEW) ?? false
  const canExecute = user?.permissions.includes(ROBOT_COMMAND_EXECUTE) ?? false

  const loadControlState = useCallback(async () => {
    if (!canView) {
      setRobot(null)
      setPendingCommandTypes([])
      setLoadError(null)
      setLoading(false)
      return
    }

    setLoading(true)
    setLoadError(null)
    const [robotResult, commandsResult] = await Promise.allSettled([
      getRobot(mission.robotId),
      listRobotCommands(mission.robotId, 0, 20),
    ])

    if (robotResult.status === 'fulfilled') {
      setRobot(robotResult.value)
    } else {
      setRobot(null)
      setLoadError(robotResult.reason)
    }

    if (commandsResult.status === 'fulfilled') {
      setPendingCommandTypes(
        commandsResult.value.content
          .filter((command) => (
            IN_FLIGHT_COMMAND_STATUSES.includes(command.commandStatus)
          ))
          .map((command) => command.commandType),
      )
    } else {
      setPendingCommandTypes([])
      if (robotResult.status === 'fulfilled') {
        setLoadError(commandsResult.reason)
      }
    }
    setLoading(false)
  }, [canView, mission.robotId])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadControlState()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [loadControlState, realtimeRevision])

  const selectedDefinition = useMemo(() => (
    selectedCommand === null
      ? null
      : missionCommandDefinitions.find((definition) => (
        definition.commandType === selectedCommand
      )) ?? null
  ), [selectedCommand])

  function availabilityFor(commandType: CommandType) {
    if (robot === null) {
      return {
        enabled: false,
        reason: loading
          ? '로봇 제어 상태를 확인하는 중입니다.'
          : '로봇 제어 상태를 불러오지 못했습니다.',
      }
    }
    return getCommandAvailability(commandType, {
      connectivityStatus: robot.connectivityStatus,
      operationalStatus: robot.operationalStatus,
      robotMode: robot.robotMode,
      missionStatus: mission.status,
      hasMissionTarget: true,
      pendingCommandTypes,
    })
  }

  function openConfirmModal(definition: CommandDefinition) {
    const availability = availabilityFor(definition.commandType)
    if (!canExecute || !availability.enabled || robot === null) {
      return
    }
    attemptRef.current.reset()
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

    const availability = availabilityFor(definition.commandType)
    if (!canExecute || !availability.enabled || robot === null) {
      return
    }

    attemptRef.current.reset()
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
    attemptRef.current.reset()
    setSelectedCommand(null)
    setSubmitError(null)
    setCreatedCommand(null)
  }, [])

  async function submitCommand(
    commandType: CommandType,
    reason: string,
    immediate = false,
  ) {
    if (submitInFlightRef.current) {
      return
    }

    let input
    try {
      input = buildCommandRequest(commandType, mission, reason)
    } catch (error) {
      const message = getApiErrorMessage(error)
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
        mission.robotId,
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
      setPendingCommandTypes((current) => (
        current.includes(command.commandType)
          ? current
          : [...current, command.commandType]
      ))
      onCommandCreated()
    } catch (error) {
      const message = getApiErrorMessage(error)
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
    <section
      className="mission-command-panel"
      aria-labelledby="mission-command-title"
      data-testid="mission-command-actions"
    >
      <div className="mission-command-heading">
        <div>
          <p className="eyebrow">MISSION CONTROL</p>
          <h4 id="mission-command-title">임무 제어</h4>
        </div>
        {robot !== null ? (
          <span className="mission-command-connectivity">
            {getRobotStatusLabel(robot.connectivityStatus)}
          </span>
        ) : null}
      </div>

      {!canView ? (
        <div className="command-readonly-notice">
          <ShieldCheck size={18} aria-hidden="true" />
          로봇 명령 조회 권한이 없습니다.
        </div>
      ) : loadError && robot === null ? (
        <div className="page-alert" role="alert">
          {getApiErrorMessage(loadError)}
        </div>
      ) : !canExecute ? (
        <div className="command-readonly-notice">
          <ShieldCheck size={18} aria-hidden="true" />
          명령 실행 권한이 없어 조회 전용으로 표시됩니다.
        </div>
      ) : (
        <div className="mission-command-grid">
          {missionCommandDefinitions.map((definition) => {
            const availability = availabilityFor(definition.commandType)
            const Icon = commandIcons[definition.commandType]
            return (
              <div className="command-action" key={definition.commandType}>
                <button
                  className="command-action-button"
                  type="button"
                  disabled={!availability.enabled || submitting}
                  onClick={() => issueImmediateCommand(definition)}
                >
                  <Icon size={17} aria-hidden="true" />
                  {definition.label}
                </button>
                <small>{availability.reason ?? definition.impact}</small>
              </div>
            )
          })}
        </div>
      )}

      {loadError && robot !== null ? (
        <p className="mission-command-warning" role="status">
          최근 명령 상태를 갱신하지 못했습니다. {getApiErrorMessage(loadError)}
        </p>
      ) : null}
      {immediateCommandError !== null ? (
        <div className="page-alert command-immediate-feedback" role="alert">
          {immediateCommandError}
        </div>
      ) : null}
      {immediateCommandNotice !== null ? (
        <div className="command-immediate-feedback success" role="status">
          {immediateCommandNotice}
        </div>
      ) : null}

      {selectedDefinition !== null && robot !== null ? (
        <CommandConfirmModal
          key={selectedDefinition.commandType}
          definition={selectedDefinition}
          robot={robot}
          mission={mission}
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
