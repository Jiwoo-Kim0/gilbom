import {
  REPORT_APPROVE,
  REPORT_CREATE,
  REPORT_UPDATE,
} from '../auth/permissions.ts'
import type {
  ReportDetail,
  ReportStatus,
} from './reportTypes.ts'

export type ReportActionPolicy = {
  canCreate: boolean
  canEdit: boolean
  canSubmit: boolean
  canDelete: boolean
  canApprove: boolean
  canReject: boolean
  readOnly: boolean
}

export function reportStatusLabel(status: ReportStatus): string {
  switch (status) {
    case 'DRAFT':
      return '초안'
    case 'IN_REVIEW':
      return '검토 중'
    case 'APPROVED':
      return '승인'
    case 'REJECTED':
      return '반려'
  }
}

export function getReportActionPolicy(
  report: ReportDetail | null,
  userId: string | null,
  permissions: readonly string[],
): ReportActionPolicy {
  const canCreate = permissions.includes(REPORT_CREATE)
  if (report === null) {
    return {
      canCreate,
      canEdit: false,
      canSubmit: false,
      canDelete: false,
      canApprove: false,
      canReject: false,
      readOnly: true,
    }
  }

  const canUpdate = permissions.includes(REPORT_UPDATE)
  const canDecide = permissions.includes(REPORT_APPROVE)
  const editable = (
    report.reportStatus === 'DRAFT'
    || report.reportStatus === 'REJECTED'
  )
  const inReview = report.reportStatus === 'IN_REVIEW'
  const latestAuthorId = report.currentVersion.createdBy

  return {
    canCreate,
    canEdit: canUpdate && editable,
    canSubmit: canUpdate && report.reportStatus === 'DRAFT',
    canDelete: canUpdate && editable,
    canApprove: (
      canDecide
      && inReview
      && userId !== null
      && userId !== latestAuthorId
    ),
    // 현재 확정 계약은 자기 작성 버전의 반려는 허용한다.
    canReject: canDecide && inReview,
    readOnly: !editable || !canUpdate,
  }
}
