import { useEffect, useRef, useState } from 'react'
import type { DefectType, RiskLevel, WorkflowStatus } from './defectTypes'

/** 서버 `DefectRealtimeEvent`의 SSE 이벤트 이름. */
const DEFECT_EVENT_TYPES = ['defect.created', 'defect.updated'] as const
export type DefectEventType = (typeof DEFECT_EVENT_TYPES)[number]

export type DefectStreamStatus = 'connecting' | 'connected' | 'reconnecting'

/** 서버 `DefectSseEventResponse`와 1:1. */
export type DefectRealtimeEvent = {
  eventType: DefectEventType
  defectId: string
  incidentCode: string
  patrolAreaId: string | null
  defectType: DefectType
  riskLevel: RiskLevel
  workflowStatus: WorkflowStatus
  latestConfidence: number | null
  beforeStatus: WorkflowStatus | null
  afterStatus: WorkflowStatus | null
  mergedIntoDefectId: string | null
  changedFields: string[] | null
  occurredAt: string
}

/**
 * 이벤트가 몰려와도 조회는 한 번만 한다. 로봇이 결함을 연속으로 올리면
 * defect.created가 초 단위로 여러 번 도착한다.
 */
const REFRESH_DEBOUNCE_MS = 400

function parseDefectEvent(
  event: MessageEvent<string>,
): DefectRealtimeEvent | null {
  try {
    const payload = JSON.parse(event.data) as Partial<DefectRealtimeEvent>
    if (
      typeof payload.eventType !== 'string'
      || !DEFECT_EVENT_TYPES.includes(payload.eventType as DefectEventType)
      || typeof payload.defectId !== 'string'
      || typeof payload.occurredAt !== 'string'
    ) {
      return null
    }
    return payload as DefectRealtimeEvent
  } catch {
    return null
  }
}

/**
 * 결함 실시간 갱신 알림을 구독한다.
 *
 * <p>이벤트 내용으로 화면을 직접 고치지 않는다. 계약(24장)이 "이벤트는 변경 알림만
 * 제공하고 클라이언트는 REST로 다시 조회한다"이므로, 여기서는 재조회를 요청하고
 * 실제 데이터는 목록·지도가 각자 다시 읽는다. 이벤트만 믿고 로컬 상태를 패치하면
 * 서버와 어긋난다.
 *
 * <p>재연결 직후에도 전체 재조회를 요청한다. 끊겨 있는 동안 놓친 이벤트가 있다.
 */
export function useDefectRealtime(onSnapshotRequired: () => void): {
  status: DefectStreamStatus
  lastEvent: DefectRealtimeEvent | null
} {
  const onSnapshotRequiredRef = useRef(onSnapshotRequired)
  const statusRef = useRef<DefectStreamStatus>('connecting')
  const [status, setStatus] = useState<DefectStreamStatus>('connecting')
  const [lastEvent, setLastEvent] = useState<DefectRealtimeEvent | null>(null)

  useEffect(() => {
    onSnapshotRequiredRef.current = onSnapshotRequired
  }, [onSnapshotRequired])

  useEffect(() => {
    const source = new EventSource('/api/v1/stream/defects', {
      withCredentials: true,
    })
    let refreshTimer: number | undefined

    function changeStatus(nextStatus: DefectStreamStatus) {
      statusRef.current = nextStatus
      setStatus(nextStatus)
    }

    function requestSnapshot() {
      window.clearTimeout(refreshTimer)
      refreshTimer = window.setTimeout(() => {
        onSnapshotRequiredRef.current()
      }, REFRESH_DEBOUNCE_MS)
    }

    source.onopen = () => changeStatus('connected')
    source.onerror = () => changeStatus('reconnecting')

    source.addEventListener('CONNECTED', () => {
      changeStatus('connected')
      // 재연결이라면 끊긴 동안의 변경을 여기서 따라잡는다.
      requestSnapshot()
    })

    // HEARTBEAT은 연결 유지 확인용이라 별도 처리가 필요 없다.

    function handleDefectEvent(rawEvent: Event) {
      const event = parseDefectEvent(rawEvent as MessageEvent<string>)
      if (event === null) {
        return
      }
      setLastEvent(event)
      requestSnapshot()
    }

    for (const eventType of DEFECT_EVENT_TYPES) {
      source.addEventListener(eventType, handleDefectEvent)
    }

    return () => {
      window.clearTimeout(refreshTimer)
      source.close()
    }
  }, [])

  return { status, lastEvent }
}
