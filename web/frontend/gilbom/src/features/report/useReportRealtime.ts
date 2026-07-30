import {
  useEffect,
  useRef,
} from 'react'
import type {
  ReportStatus,
} from './reportTypes'

export type ReportRealtimeEvent = {
  reportId: string
  reportStatus: ReportStatus
  currentVersionNo: number
  deleted: boolean
  occurredAt: string
}

const REPORT_STATUSES: readonly ReportStatus[] = [
  'DRAFT',
  'IN_REVIEW',
  'APPROVED',
  'REJECTED',
]

export function parseReportRealtimeEvent(
  rawData: string,
): ReportRealtimeEvent | null {
  try {
    const payload = JSON.parse(rawData) as Partial<ReportRealtimeEvent>
    if (
      typeof payload.reportId !== 'string'
      || payload.reportId.length === 0
      || typeof payload.reportStatus !== 'string'
      || !REPORT_STATUSES.includes(payload.reportStatus as ReportStatus)
      || !Number.isInteger(payload.currentVersionNo)
      || (payload.currentVersionNo ?? 0) < 1
      || typeof payload.deleted !== 'boolean'
      || typeof payload.occurredAt !== 'string'
    ) {
      return null
    }
    return payload as ReportRealtimeEvent
  } catch {
    return null
  }
}

/**
 * SSE는 변경 알림으로만 사용한다. CONNECTED는 최초 연결과 자동 재연결 모두에서
 * 오므로 콜백은 항상 REST snapshot을 다시 조회해야 한다.
 */
export function useReportRealtime(
  onSnapshotRequired: (event: ReportRealtimeEvent | null) => void,
  reportId?: string,
): void {
  const onSnapshotRequiredRef = useRef(onSnapshotRequired)

  useEffect(() => {
    onSnapshotRequiredRef.current = onSnapshotRequired
  }, [onSnapshotRequired])

  useEffect(() => {
    const query = reportId === undefined || reportId.length === 0
      ? ''
      : `?reportId=${encodeURIComponent(reportId)}`
    const source = new EventSource(`/api/v1/stream/reports${query}`, {
      withCredentials: true,
    })

    const handleConnected = () => {
      onSnapshotRequiredRef.current(null)
    }
    const handleReportUpdated = (rawEvent: Event) => {
      const event = parseReportRealtimeEvent(
        (rawEvent as MessageEvent<string>).data,
      )
      if (event !== null) {
        onSnapshotRequiredRef.current(event)
      }
    }

    source.addEventListener('CONNECTED', handleConnected)
    source.addEventListener('report.updated', handleReportUpdated)

    return () => {
      source.close()
    }
  }, [reportId])
}
