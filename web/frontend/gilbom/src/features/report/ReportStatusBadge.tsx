import {
  reportStatusLabel,
} from './reportPolicy'
import type { ReportStatus } from './reportTypes'

export function ReportStatusBadge({ status }: { status: ReportStatus }) {
  return (
    <span className={`report-status-badge report-status-${status.toLowerCase()}`}>
      {reportStatusLabel(status)}
    </span>
  )
}
