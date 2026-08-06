import { useEffect, useRef, useState } from 'react'

const DEFECT_EVENT_TYPES = ['defect.created', 'defect.updated'] as const

export type MissionDefectStreamStatus =
  | 'connecting'
  | 'connected'
  | 'reconnecting'

export type MissionDefectRealtimeEvent = {
  eventType: (typeof DEFECT_EVENT_TYPES)[number]
  defectId: string
  patrolAreaId: string | null
}

function parseEvent(rawEvent: Event): MissionDefectRealtimeEvent | null {
  try {
    const payload = JSON.parse(
      (rawEvent as MessageEvent<string>).data,
    ) as Partial<MissionDefectRealtimeEvent>
    if (
      typeof payload.eventType !== 'string'
      || !DEFECT_EVENT_TYPES.includes(
        payload.eventType as MissionDefectRealtimeEvent['eventType'],
      )
      || typeof payload.defectId !== 'string'
    ) {
      return null
    }
    return payload as MissionDefectRealtimeEvent
  } catch {
    return null
  }
}

/**
 * 기존 결함 SSE를 변경하지 않고 Mission 지도에서 직접 구독한다. 이벤트가 오면
 * 지연 없이 Mission 전용 조회를 다시 호출해 다른 Mission의 결함이 섞이지 않게 한다.
 */
export function useMissionDefectRealtime({
  enabled,
  patrolAreaId,
  onEvent,
  onConnected,
}: {
  enabled: boolean
  patrolAreaId: string | null
  onEvent: (event: MissionDefectRealtimeEvent) => void
  onConnected: () => void
}): MissionDefectStreamStatus {
  const onEventRef = useRef(onEvent)
  const onConnectedRef = useRef(onConnected)
  const [status, setStatus] = useState<MissionDefectStreamStatus>('connecting')

  useEffect(() => {
    onEventRef.current = onEvent
  }, [onEvent])

  useEffect(() => {
    onConnectedRef.current = onConnected
  }, [onConnected])

  useEffect(() => {
    if (!enabled || patrolAreaId === null) {
      return
    }
    const query = new URLSearchParams({ patrolAreaId })
    const source = new EventSource(`/api/v1/stream/defects?${query}`, {
      withCredentials: true,
    })

    source.onopen = () => setStatus('connected')
    source.onerror = () => setStatus('reconnecting')
    source.addEventListener('CONNECTED', () => {
      setStatus('connected')
      onConnectedRef.current()
    })

    const handleEvent = (rawEvent: Event) => {
      const event = parseEvent(rawEvent)
      if (event !== null) {
        onEventRef.current(event)
      }
    }
    for (const eventType of DEFECT_EVENT_TYPES) {
      source.addEventListener(eventType, handleEvent)
    }

    return () => source.close()
  }, [enabled, patrolAreaId])

  return status
}
