import {
  AlertTriangle,
  CheckCircle2,
  X,
} from 'lucide-react'
import {
  useEffect,
  useId,
  useState,
  type FormEvent,
  type MouseEvent,
} from 'react'
import type { Mission } from '../mission/missionTypes'
import type { RobotDetail } from '../robot/robotTypes'
import type {
  CommandDefinition,
} from './commandPolicy'
import type { CommandResponse } from './commandTypes'

type CommandConfirmModalProps = {
  definition: CommandDefinition
  robot: RobotDetail
  mission: Mission | null
  submitting: boolean
  submitError: string | null
  createdCommand: CommandResponse | null
  onClose: () => void
  onConfirm: (reason: string) => void
}

export function CommandConfirmModal({
  definition,
  robot,
  mission,
  submitting,
  submitError,
  createdCommand,
  onClose,
  onConfirm,
}: CommandConfirmModalProps) {
  const titleId = useId()
  const descriptionId = useId()
  const reasonId = useId()
  const safetyId = useId()
  const [reason, setReason] = useState('')
  const [safetyConfirmed, setSafetyConfirmed] = useState(false)
  const reasonReady = !definition.requiresReason || reason.trim().length > 0
  const safetyReady = (
    !definition.requiresSafetyConfirmation
    || safetyConfirmed
  )
  const canSubmit = (
    !submitting
    && createdCommand === null
    && reasonReady
    && safetyReady
  )

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !submitting) {
        onClose()
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [onClose, submitting])

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canSubmit) {
      return
    }
    onConfirm(reason)
  }

  function handleBackdropMouseDown(event: MouseEvent<HTMLDivElement>) {
    if (event.target === event.currentTarget && !submitting) {
      onClose()
    }
  }

  const danger = definition.tone === 'danger'

  return (
    <div
      className="command-modal-backdrop"
      role="presentation"
      onMouseDown={handleBackdropMouseDown}
    >
      <section
        className={`command-modal${danger ? ' command-modal-danger' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
      >
        <div className="command-modal-heading">
          <span className="command-modal-icon" aria-hidden="true">
            {danger ? <AlertTriangle size={22} /> : <CheckCircle2 size={22} />}
          </span>
          <div>
            <p className="eyebrow">COMMAND CONFIRM</p>
            <h3 id={titleId}>{definition.label} 확인</h3>
          </div>
          <button
            className="command-modal-close"
            type="button"
            aria-label="확인창 닫기"
            onClick={onClose}
            disabled={submitting}
          >
            <X size={18} aria-hidden="true" />
          </button>
        </div>

        <p id={descriptionId} className="command-modal-impact">
          {definition.impact}
        </p>

        <dl className="command-modal-context">
          <div>
            <dt>대상 로봇</dt>
            <dd>{robot.robotName} · {robot.mqttId}</dd>
          </div>
          <div>
            <dt>로봇 상태</dt>
            <dd>{robot.connectivityStatus} · {robot.robotMode ?? '모드 없음'}</dd>
          </div>
          <div>
            <dt>현재 Mission</dt>
            <dd>
              {mission === null
                ? robot.missionStatus ?? '진행 중인 Mission 없음'
                : `${mission.missionCode} · ${mission.status}`}
            </dd>
          </div>
          <div>
            <dt>명령</dt>
            <dd>{definition.commandType}</dd>
          </div>
        </dl>

        {definition.safetyMessage !== null ? (
          <div className={`command-safety-note${danger ? ' danger' : ''}`}>
            <AlertTriangle size={17} aria-hidden="true" />
            <span>{definition.safetyMessage}</span>
          </div>
        ) : null}

        <form className="command-modal-form" onSubmit={handleSubmit}>
          {definition.requiresReason ? (
            <label className="command-reason-field" htmlFor={reasonId}>
              <span>명령 사유</span>
              <textarea
                id={reasonId}
                value={reason}
                maxLength={500}
                rows={3}
                placeholder="현장 상황과 명령 사유를 입력해주세요."
                disabled={submitting || createdCommand !== null}
                autoFocus
                required
                onChange={(event) => setReason(event.target.value)}
              />
              <small>{reason.length} / 500</small>
            </label>
          ) : null}

          {definition.requiresSafetyConfirmation ? (
            <label className="command-safety-confirm" htmlFor={safetyId}>
              <input
                id={safetyId}
                type="checkbox"
                checked={safetyConfirmed}
                disabled={submitting || createdCommand !== null}
                onChange={(event) => setSafetyConfirmed(event.target.checked)}
              />
              <span>{definition.safetyMessage}</span>
            </label>
          ) : null}

          {submitError !== null ? (
            <div className="page-alert command-modal-alert" role="alert">
              {submitError}
            </div>
          ) : null}

          {createdCommand !== null ? (
            <div className="command-created-result" role="status">
              <CheckCircle2 size={18} aria-hidden="true" />
              <span>
                명령이 접수되었습니다.
                <strong>{createdCommand.commandId}</strong>
                <small>현재 상태 {createdCommand.commandStatus}</small>
              </span>
            </div>
          ) : null}

          <div className="command-modal-actions">
            <button
              className="secondary-button"
              type="button"
              onClick={onClose}
              disabled={submitting}
            >
              {createdCommand === null ? '취소' : '닫기'}
            </button>
            {createdCommand === null ? (
              <button
                className={danger ? 'command-danger-button' : 'primary-button'}
                type="submit"
                disabled={!canSubmit}
                autoFocus={!definition.requiresReason}
              >
                {submitting ? '전송 중…' : `${definition.label} 실행`}
              </button>
            ) : null}
          </div>
        </form>
      </section>
    </div>
  )
}
