import {
  KeyRound,
  ShieldCheck,
  X,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react'
import {
  ApiError,
  getApiErrorMessage,
} from '../../shared/api/apiClient'
import { useAuthentication } from '../auth/authenticationContext'
import { ROBOT_REGISTER } from '../auth/permissions'
import {
  issueRobotCredential,
  reconcileRobotCredentialRegistry,
  type IssuedMqttCredential,
} from './robotCredentialApi'
import { getRobots } from './robotApi'
import { getRobotStatusLabel } from './robotStatusPresentation'
import type { RobotListItem } from './robotTypes'
import './robot-credential-manager.css'

type RobotCredentialManagerProps = {
  onCredentialChanged?: () => void | Promise<void>
}

export function RobotCredentialManager({
  onCredentialChanged,
}: RobotCredentialManagerProps) {
  const [robots, setRobots] = useState<RobotListItem[]>([])
  const [loading, setLoading] = useState(true)
  const [listError, setListError] = useState<unknown>(null)
  const [targetRobot, setTargetRobot] = useState<RobotListItem | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<unknown>(null)
  const [issuedCredential, setIssuedCredential] =
    useState<IssuedMqttCredential | null>(null)
  const idempotencyKeyRef = useRef<string | null>(null)
  const { user } = useAuthentication()
  const canRegister = user?.permissions.includes(ROBOT_REGISTER) ?? false
  const registryConflict =
    submitError instanceof ApiError
    && submitError.code === 'ROBOT_CREDENTIAL_CONFLICT'

  const loadRobots = useCallback(async () => {
    if (!canRegister) {
      setRobots([])
      setLoading(false)
      return
    }

    setLoading(true)
    setListError(null)
    try {
      const response = await getRobots(0, 100, '')
      setRobots(response.content.filter((robot) => (
        robot.operationalStatus === 'PROVISIONING'
        || robot.operationalStatus === 'ACTIVE'
      )))
    } catch (error) {
      setListError(error)
    } finally {
      setLoading(false)
    }
  }, [canRegister])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadRobots()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [loadRobots])

  function openCredentialModal(robot: RobotListItem) {
    idempotencyKeyRef.current = crypto.randomUUID()
    setTargetRobot(robot)
    setSubmitError(null)
    setIssuedCredential(null)
  }

  function closeCredentialModal() {
    if (submitting) {
      return
    }
    idempotencyKeyRef.current = null
    setTargetRobot(null)
    setSubmitError(null)
    setIssuedCredential(null)
  }

  async function refreshAfterIssuance() {
    await loadRobots()
    await onCredentialChanged?.()
  }

  async function requestCredential(robot: RobotListItem) {
    const idempotencyKey = idempotencyKeyRef.current ?? crypto.randomUUID()
    idempotencyKeyRef.current = idempotencyKey
    const credential = await issueRobotCredential(
      robot.robotId,
      idempotencyKey,
    )
    setIssuedCredential(credential)
    await refreshAfterIssuance()
  }

  async function handleIssue() {
    if (targetRobot === null) {
      return
    }

    setSubmitting(true)
    setSubmitError(null)
    try {
      await requestCredential(targetRobot)
    } catch (error) {
      setSubmitError(error)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleRegistryReconcileAndRetry() {
    if (targetRobot === null) {
      return
    }

    setSubmitting(true)
    setSubmitError(null)
    try {
      await reconcileRobotCredentialRegistry(
        targetRobot.robotId,
        '로봇 관리 Credential 발급 전 Broker 잔존 계정 정리',
      )
      await requestCredential(targetRobot)
    } catch (error) {
      setSubmitError(error)
    } finally {
      setSubmitting(false)
    }
  }

  if (!canRegister) {
    return null
  }

  return (
    <>
      <section
        className="content-card robot-credential-manager"
        aria-labelledby="robot-credential-manager-title"
      >
        <div className="robot-credential-manager-heading">
          <div>
            <p className="eyebrow">MQTT CREDENTIAL</p>
            <h2 id="robot-credential-manager-title">Robot Credential 관리</h2>
            <p>
              최초 등록 후 한 번 발급하며, 분실·노출·정기 교체가 필요할 때만
              회전합니다. 임무를 생성할 때마다 바꾸지 않습니다.
            </p>
          </div>
        </div>

        {listError ? (
          <div className="page-alert" role="alert">
            Credential 대상 Robot을 불러오지 못했습니다.{' '}
            {getApiErrorMessage(listError)}
          </div>
        ) : null}

        <div className="robot-credential-list">
          {loading && robots.length === 0 ? (
            <div className="empty-state">Robot 상태를 불러오는 중입니다.</div>
          ) : null}
          {!loading && robots.length === 0 && !listError ? (
            <div className="empty-state">
              Credential을 관리할 수 있는 Robot이 없습니다.
            </div>
          ) : null}
          {robots.map((robot) => {
            const provisioning = robot.operationalStatus === 'PROVISIONING'
            return (
              <article
                className="robot-credential-item"
                key={robot.robotId}
              >
                <div className="robot-credential-item-identity">
                  <ShieldCheck size={19} aria-hidden="true" />
                  <div>
                    <strong>{robot.robotName}</strong>
                    <span>{robot.mqttId} · {robot.serialNumber}</span>
                  </div>
                </div>
                <div className="robot-credential-item-controls">
                  <div className="robot-credential-item-state">
                    <span
                      className={[
                        'robot-credential-status',
                        provisioning ? 'is-provisioning' : 'is-active',
                      ].join(' ')}
                    >
                      {getRobotStatusLabel(robot.operationalStatus)}
                    </span>
                    <small>
                      {getRobotStatusLabel(robot.connectivityStatus)}
                      {robot.missionStatus === null
                        ? ' · 임무 미수행'
                        : ` · 임무 ${getRobotStatusLabel(robot.missionStatus)}`}
                    </small>
                  </div>
                  <button
                    className={provisioning
                      ? 'primary-button'
                      : 'secondary-button'}
                    type="button"
                    onClick={() => openCredentialModal(robot)}
                    disabled={submitting}
                  >
                    <KeyRound size={16} aria-hidden="true" />
                    {provisioning ? '최초 발급·활성화' : 'Credential 회전'}
                  </button>
                </div>
              </article>
            )
          })}
        </div>
      </section>

      {targetRobot !== null ? (
        <div
          className="robot-credential-backdrop"
          onMouseDown={closeCredentialModal}
        >
          <section
            className="robot-credential-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="robot-credential-dialog-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="robot-credential-dialog-heading">
              <div>
                <p className="eyebrow">MQTT CREDENTIAL</p>
                <h3 id="robot-credential-dialog-title">
                  {targetRobot.operationalStatus === 'ACTIVE'
                    ? 'Robot Credential 회전'
                    : 'Robot 최초 발급·활성화'}
                </h3>
              </div>
              <button
                className="icon-button"
                type="button"
                aria-label="Credential 창 닫기"
                onClick={closeCredentialModal}
                disabled={submitting}
              >
                <X size={18} aria-hidden="true" />
              </button>
            </div>

            {issuedCredential === null ? (
              <>
                <p>
                  <strong>{targetRobot.robotName} ({targetRobot.mqttId})</strong>
                  {targetRobot.operationalStatus === 'ACTIVE'
                    ? '의 기존 MQTT 비밀번호를 폐기하고 새 Credential을 발급합니다.'
                    : '의 MQTT Credential을 최초 발급하고 ACTIVE로 전환합니다.'}
                </p>
                <p className="robot-credential-warning">
                  {targetRobot.operationalStatus === 'ACTIVE'
                    ? '기존 비밀번호는 즉시 사용할 수 없게 됩니다.'
                    : '발급 후 Jetson MQTT Client에 연결 정보를 주입해야 합니다.'}
                  {' '}새 비밀번호는 성공 직후 이 창에서 한 번만 표시됩니다.
                </p>
                {submitError ? (
                  <>
                    <div className="page-alert" role="alert">
                      처리하지 못했습니다. {getApiErrorMessage(submitError)}
                    </div>
                    {registryConflict ? (
                      <p className="robot-credential-registry-recovery">
                        DB에는 활성 Credential이 없지만 Broker에 계정이 남아
                        있습니다. 정합성 복구 후 최초 발급을 다시 수행할 수
                        있습니다.
                      </p>
                    ) : null}
                  </>
                ) : null}
                <div className="robot-credential-actions">
                  <button
                    className="secondary-button"
                    type="button"
                    onClick={closeCredentialModal}
                    disabled={submitting}
                  >
                    취소
                  </button>
                  <button
                    className="primary-button"
                    type="button"
                    onClick={() => void (
                      registryConflict
                        ? handleRegistryReconcileAndRetry()
                        : handleIssue()
                    )}
                    disabled={submitting}
                  >
                    {submitting
                      ? '처리 중…'
                      : registryConflict
                        ? '잔존 계정 정리 후 다시 발급'
                        : targetRobot.operationalStatus === 'ACTIVE'
                          ? 'Credential 회전'
                          : '최초 발급·활성화'}
                  </button>
                </div>
              </>
            ) : (
              <>
                <div className="page-alert page-alert-success" role="status">
                  새 Credential이 발급되었습니다.
                </div>
                <dl className="robot-credential-result">
                  <div>
                    <dt>Robot UUID</dt>
                    <dd>{issuedCredential.robotId}</dd>
                  </div>
                  <div>
                    <dt>MQTT ID</dt>
                    <dd>{issuedCredential.mqttId}</dd>
                  </div>
                  <div>
                    <dt>Broker</dt>
                    <dd>{issuedCredential.mqtt.brokerUri}</dd>
                  </div>
                  <div>
                    <dt>Username</dt>
                    <dd>{issuedCredential.mqtt.username}</dd>
                  </div>
                  <div>
                    <dt>Client ID</dt>
                    <dd>{issuedCredential.mqtt.clientId}</dd>
                  </div>
                  <div>
                    <dt>1회 비밀번호</dt>
                    <dd>{issuedCredential.mqtt.password}</dd>
                  </div>
                  <div>
                    <dt>이미지 업로드 URL</dt>
                    <dd>{issuedCredential.imageUpload.url}</dd>
                  </div>
                  <div>
                    <dt>1회 이미지 API Key</dt>
                    <dd>{issuedCredential.imageUpload.apiKey}</dd>
                  </div>
                </dl>
                <p className="robot-credential-warning">
                  창을 닫기 전에 Robot UUID, MQTT 연결 정보와 이미지 업로드 정보를
                  승인된 경로로 Jetson에 주입하세요. 두 Secret은 다시 표시되지 않습니다.
                </p>
                <div className="robot-credential-actions">
                  <button
                    className="primary-button"
                    type="button"
                    onClick={closeCredentialModal}
                  >
                    확인 후 닫기
                  </button>
                </div>
              </>
            )}
          </section>
        </div>
      ) : null}
    </>
  )
}
