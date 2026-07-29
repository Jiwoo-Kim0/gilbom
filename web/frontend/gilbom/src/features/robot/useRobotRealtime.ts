import {
  useEffect,
  useRef,
  useState,
} from 'react'
import type {
  RobotLogEntry,
  RobotRealtimeEvent,
  RobotRealtimeEventType,
  RobotStreamStatus,
} from './robotTypes'

const ROBOT_EVENT_TYPES: readonly RobotRealtimeEventType[] = [
  'ROBOT_CONNECTIVITY_CHANGED',
  'ROBOT_STATE_UPDATED',
  'ROBOT_TELEMETRY_UPDATED',
  'COMMAND_STATUS_CHANGED',
]

const eventMessage: Record<RobotRealtimeEventType, string> = {
  ROBOT_CONNECTIVITY_CHANGED: '통신 연결 상태가 변경되었습니다.',
  ROBOT_STATE_UPDATED: '로봇 운행 상태가 갱신되었습니다.',
  ROBOT_TELEMETRY_UPDATED: '위치·배터리·온도 정보가 갱신되었습니다.',
  COMMAND_STATUS_CHANGED: '로봇 명령 처리 상태가 변경되었습니다.',
}

function parseRobotEvent(event: MessageEvent<string>): RobotRealtimeEvent | null {
  try {
    const payload = JSON.parse(event.data) as Partial<RobotRealtimeEvent>
    if (
      typeof payload.eventType !== 'string'
      || !ROBOT_EVENT_TYPES.includes(
        payload.eventType as RobotRealtimeEventType,
      )
      || typeof payload.robotId !== 'string'
      || typeof payload.mqttId !== 'string'
      || typeof payload.observedAt !== 'string'
    ) {
      return null
    }
    return payload as RobotRealtimeEvent
  } catch {
    return null
  }
}

export function useRobotRealtime(onSnapshotRequired: () => void): {
  status: RobotStreamStatus
  lastHeartbeatAt: string | null
  logs: RobotLogEntry[]
} {
  const onSnapshotRequiredRef = useRef(onSnapshotRequired)
  const sequenceRef = useRef(0)
  const statusRef = useRef<RobotStreamStatus>('connecting')
  const [status, setStatus] = useState<RobotStreamStatus>('connecting')
  const [lastHeartbeatAt, setLastHeartbeatAt] = useState<string | null>(null)
  const [logs, setLogs] = useState<RobotLogEntry[]>([])

  useEffect(() => {
    onSnapshotRequiredRef.current = onSnapshotRequired
  }, [onSnapshotRequired])

  useEffect(() => {
    const source = new EventSource('/api/v1/stream/robots', {
      withCredentials: true,
    })

    function changeStatus(nextStatus: RobotStreamStatus) {
      statusRef.current = nextStatus
      setStatus(nextStatus)
    }

    function appendLog(
      robotId: string,
      timestamp: string,
      message: string,
      level: RobotLogEntry['level'],
    ) {
      sequenceRef.current += 1
      const entry: RobotLogEntry = {
        id: `${timestamp}-${sequenceRef.current}`,
        robotId,
        timestamp,
        message,
        level,
      }
      setLogs((current) => [entry, ...current].slice(0, 20))
    }

    source.onopen = () => {
      changeStatus('connected')
    }
    source.onerror = () => {
      if (statusRef.current !== 'reconnecting') {
        appendLog(
          'SYSTEM',
          new Date().toISOString(),
          '실시간 연결이 끊겨 자동 재연결을 시도합니다.',
          'warning',
        )
      }
      changeStatus('reconnecting')
    }

    source.addEventListener('CONNECTED', (event) => {
      const message = event as MessageEvent<string>
      let connectedAt = new Date().toISOString()
      try {
        const payload = JSON.parse(message.data) as { connectedAt?: string }
        if (typeof payload.connectedAt === 'string') {
          connectedAt = payload.connectedAt
        }
      } catch {
        // CONNECTED의 파싱 실패는 연결 자체를 무효화하지 않는다.
      }
      changeStatus('connected')
      appendLog(
        'SYSTEM',
        connectedAt,
        '실시간 로봇 관제 연결이 복구되었습니다.',
        'success',
      )
      onSnapshotRequiredRef.current()
    })

    source.addEventListener('HEARTBEAT', (event) => {
      const message = event as MessageEvent<string>
      try {
        const payload = JSON.parse(message.data) as { sentAt?: string }
        if (typeof payload.sentAt === 'string') {
          setLastHeartbeatAt(payload.sentAt)
        }
      } catch {
        setLastHeartbeatAt(new Date().toISOString())
      }
    })

    function handleRobotEvent(rawEvent: Event) {
      const event = parseRobotEvent(rawEvent as MessageEvent<string>)
      if (event === null) {
        return
      }
      appendLog(
        event.mqttId,
        event.observedAt,
        eventMessage[event.eventType],
        'info',
      )
      onSnapshotRequiredRef.current()
    }

    for (const eventType of ROBOT_EVENT_TYPES) {
      source.addEventListener(eventType, handleRobotEvent)
    }

    return () => {
      source.close()
    }
  }, [])

  return {
    status,
    lastHeartbeatAt,
    logs,
  }
}
