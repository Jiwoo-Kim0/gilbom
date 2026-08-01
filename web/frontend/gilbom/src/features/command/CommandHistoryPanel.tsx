import {
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Radio,
  X,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type MouseEvent,
} from 'react'
import { getApiErrorMessage } from '../../shared/api/apiClient'
import { lockBodyScroll } from '../../shared/dom/bodyScrollLock'
import type { RobotStreamStatus } from '../robot/robotTypes'
import {
  getRobotCommand,
  getRobotCommandResults,
} from './commandApi'
import {
  formatCommandDateTime,
  getCommandOperatorGuidance,
  getCommandResultLabel,
  getCommandStatusPresentation,
  getCommandTypeLabel,
} from './commandPresentation'
import type {
  CommandListResponse,
  CommandResponse,
  CommandResultResponse,
} from './commandTypes'

type CommandHistoryPanelProps = {
  commands: CommandListResponse | null
  loading: boolean
  streamStatus: RobotStreamStatus
  refreshRevision: number
}

function streamStatusLabel(status: RobotStreamStatus): string {
  switch (status) {
    case 'connected':
      return '실시간 연결됨'
    case 'connecting':
      return '실시간 연결 중'
    case 'reconnecting':
      return '실시간 재연결 중'
  }
}

function resultTone(result: CommandResultResponse): string {
  switch (result.resultStatus) {
    case 'COMPLETED':
      return 'success'
    case 'ACCEPTED':
      return 'ack'
    case 'EXPIRED':
      return 'expired'
    case 'FAILED':
    case 'REJECTED':
      return 'failure'
  }
}

export function CommandHistoryPanel({
  commands,
  loading,
  streamStatus,
  refreshRevision,
}: CommandHistoryPanelProps) {
  const titleId = useId()
  const [selectedCommandId, setSelectedCommandId] = useState<string | null>(null)
  const [detail, setDetail] = useState<CommandResponse | null>(null)
  const [results, setResults] = useState<CommandResultResponse[]>([])
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState<string | null>(null)
  const detailRequestRef = useRef(0)

  const loadDetail = useCallback(async (commandId: string) => {
    detailRequestRef.current += 1
    const requestId = detailRequestRef.current
    setDetailLoading(true)
    setDetailError(null)
    try {
      const [nextDetail, resultResponse] = await Promise.all([
        getRobotCommand(commandId),
        getRobotCommandResults(commandId),
      ])
      if (requestId !== detailRequestRef.current) {
        return
      }
      setDetail(nextDetail)
      setResults(resultResponse.results)
    } catch (error) {
      if (requestId !== detailRequestRef.current) {
        return
      }
      setDetailError(getApiErrorMessage(error))
    } finally {
      if (requestId === detailRequestRef.current) {
        setDetailLoading(false)
      }
    }
  }, [])

  const selectedSummary = commands?.content.find((command) => (
    command.commandId === selectedCommandId
  )) ?? null

  useEffect(() => {
    if (selectedCommandId === null) {
      return
    }
    const timer = window.setTimeout(() => {
      void loadDetail(selectedCommandId)
    }, 0)
    return () => window.clearTimeout(timer)
  }, [
    loadDetail,
    refreshRevision,
    selectedCommandId,
    selectedSummary?.updatedAt,
  ])

  const closeDetail = useCallback(() => {
    detailRequestRef.current += 1
    setSelectedCommandId(null)
    setDetail(null)
    setResults([])
    setDetailError(null)
    setDetailLoading(false)
  }, [])

  useEffect(() => {
    if (selectedCommandId === null) {
      return
    }
    const releaseBodyScroll = lockBodyScroll()

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        closeDetail()
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      releaseBodyScroll()
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [closeDetail, selectedCommandId])

  function openDetail(command: CommandResponse) {
    setSelectedCommandId(command.commandId)
    setDetail(command)
    setResults([])
    setDetailError(null)
  }

  function handleBackdropMouseDown(event: MouseEvent<HTMLDivElement>) {
    if (event.target === event.currentTarget) {
      closeDetail()
    }
  }

  const displayedDetail = detail ?? selectedSummary
  const statusPresentation = displayedDetail === null
    ? null
    : getCommandStatusPresentation(displayedDetail.commandStatus)
  const guidance = displayedDetail === null
    ? null
    : getCommandOperatorGuidance(displayedDetail)

  return (
    <>
      <article
        className="content-card command-history-card"
        data-testid="command-history"
      >
        <div className="command-history-heading">
          <div>
            <p className="eyebrow">ACK &amp; RESULT</p>
            <h3>명령 상태·결과</h3>
            <p>최근 명령 20건의 전달, ACK, 최종 결과를 확인합니다.</p>
          </div>
          <span
            className={`command-stream-status command-stream-${streamStatus}`}
            data-testid="command-stream-status"
          >
            <Radio size={13} aria-hidden="true" />
            {streamStatusLabel(streamStatus)}
          </span>
        </div>

        {loading && commands === null ? (
          <p aria-live="polite">명령 상태를 불러오는 중입니다.</p>
        ) : commands === null ? (
          <p>명령 상태를 불러오지 못했습니다.</p>
        ) : commands.content.length === 0 ? (
          <p className="command-history-empty">아직 발행한 명령이 없습니다.</p>
        ) : (
          <>
            <div className="command-history-meta">
              <span>전체 {commands.totalElements}건</span>
              {loading ? <span aria-live="polite">정본 갱신 중</span> : null}
            </div>
            <div className="command-history-list">
              {commands.content.map((command) => {
                const presentation = getCommandStatusPresentation(
                  command.commandStatus,
                )
                return (
                  <button
                    key={command.commandId}
                    className="command-history-row"
                    type="button"
                    data-command-id={command.commandId}
                    data-command-type={command.commandType}
                    data-command-status={command.commandStatus}
                    onClick={() => openDetail(command)}
                  >
                    <span className="command-history-row-main">
                      <strong>{getCommandTypeLabel(command.commandType)}</strong>
                      <code>{command.commandId}</code>
                    </span>
                    <span className="command-history-row-time">
                      <Clock3 size={13} aria-hidden="true" />
                      {formatCommandDateTime(command.issuedAt)}
                    </span>
                    <span
                      className={`command-status-badge command-status-${
                        presentation.tone
                      }`}
                    >
                      {presentation.label}
                    </span>
                    <ChevronRight size={17} aria-hidden="true" />
                  </button>
                )
              })}
            </div>
          </>
        )}
      </article>

      {selectedCommandId !== null && displayedDetail !== null
        && statusPresentation !== null ? (
          <div
            className="command-detail-backdrop"
            role="presentation"
            onMouseDown={handleBackdropMouseDown}
          >
            <section
              className="command-detail-drawer"
              role="dialog"
              aria-modal="true"
              aria-labelledby={titleId}
            >
              <div className="command-detail-heading">
                <div>
                  <p className="eyebrow">COMMAND DETAIL</p>
                  <h3 id={titleId}>
                    {getCommandTypeLabel(displayedDetail.commandType)}
                  </h3>
                  <code>{displayedDetail.commandId}</code>
                </div>
                <button
                  className="command-modal-close"
                  type="button"
                  aria-label="명령 상세 닫기"
                  onClick={closeDetail}
                >
                  <X size={18} aria-hidden="true" />
                </button>
              </div>

              <div
                className={`command-detail-state command-detail-${
                  statusPresentation.tone
                }`}
              >
                {statusPresentation.tone === 'success' ? (
                  <CheckCircle2 size={19} aria-hidden="true" />
                ) : statusPresentation.terminal ? (
                  <AlertTriangle size={19} aria-hidden="true" />
                ) : (
                  <Radio size={19} aria-hidden="true" />
                )}
                <span>
                  <strong>{statusPresentation.label}</strong>
                  <small>{statusPresentation.description}</small>
                </span>
              </div>

              {guidance !== null ? (
                <div className="command-detail-guidance">
                  <AlertTriangle size={18} aria-hidden="true" />
                  <span>{guidance}</span>
                </div>
              ) : null}

              <dl className="command-detail-timeline">
                <div className="is-complete">
                  <dt>요청 생성</dt>
                  <dd>{formatCommandDateTime(displayedDetail.issuedAt)}</dd>
                </div>
                <div className={displayedDetail.sentAt === null ? '' : 'is-complete'}>
                  <dt>Broker 전송</dt>
                  <dd>{formatCommandDateTime(displayedDetail.sentAt)}</dd>
                </div>
                <div className={displayedDetail.acceptedAt === null ? '' : 'is-complete'}>
                  <dt>Robot 수신 ACK</dt>
                  <dd>{formatCommandDateTime(displayedDetail.acceptedAt)}</dd>
                </div>
                <div className={displayedDetail.completedAt === null ? '' : 'is-complete'}>
                  <dt>최종 Result</dt>
                  <dd>{formatCommandDateTime(displayedDetail.completedAt)}</dd>
                </div>
              </dl>

              <dl className="command-detail-grid">
                <div>
                  <dt>요청자</dt>
                  <dd>
                    {displayedDetail.requestedByUserCode
                      ?? displayedDetail.requestedByType}
                  </dd>
                </div>
                <div>
                  <dt>만료 시각</dt>
                  <dd>{formatCommandDateTime(displayedDetail.expiresAt)}</dd>
                </div>
                <div>
                  <dt>Publish 시도</dt>
                  <dd>{displayedDetail.publishAttemptCount}회</dd>
                </div>
                <div>
                  <dt>오류 코드</dt>
                  <dd>{displayedDetail.lastErrorCode ?? '—'}</dd>
                </div>
              </dl>

              <div className="command-result-section">
                <div className="command-result-heading">
                  <h4>ACK·Result 이력</h4>
                  <span>{results.length}건</span>
                </div>

                {detailLoading ? (
                  <p aria-live="polite">REST 정본을 갱신하는 중입니다.</p>
                ) : detailError !== null ? (
                  <div className="page-alert" role="alert">
                    {detailError}
                  </div>
                ) : results.length === 0 ? (
                  <p className="command-history-empty">
                    아직 Robot ACK 또는 최종 Result가 없습니다.
                  </p>
                ) : (
                  <ol className="command-result-list">
                    {results.map((result) => (
                      <li
                        key={result.resultId}
                        data-result-status={result.resultStatus}
                      >
                        <span
                          className={`command-result-step command-result-${
                            resultTone(result)
                          }`}
                        >
                          {result.resultStatus === 'ACCEPTED' ? 'ACK' : 'FINAL'}
                        </span>
                        <span className="command-result-summary">
                          <strong>{getCommandResultLabel(result.resultStatus)}</strong>
                          <small>
                            서버 수신 {formatCommandDateTime(result.serverReceivedAt)}
                          </small>
                        </span>
                        <dl>
                          <div>
                            <dt>Robot 수신</dt>
                            <dd>{formatCommandDateTime(result.robotReceivedAt)}</dd>
                          </div>
                          <div>
                            <dt>Robot 완료</dt>
                            <dd>{formatCommandDateTime(result.robotCompletedAt)}</dd>
                          </div>
                          <div>
                            <dt>Reason</dt>
                            <dd>{result.reasonCode || '—'}</dd>
                          </div>
                          <div>
                            <dt>결과 Mode</dt>
                            <dd>{result.resultingMode || '—'}</dd>
                          </div>
                          <div>
                            <dt>상태 사유</dt>
                            <dd>{result.resultingReasonCode || '—'}</dd>
                          </div>
                        </dl>
                      </li>
                    ))}
                  </ol>
                )}
              </div>
            </section>
          </div>
        ) : null}
    </>
  )
}
