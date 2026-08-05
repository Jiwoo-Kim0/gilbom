import assert from 'node:assert/strict'
import test from 'node:test'
import {
  getReportActionPolicy,
} from '../src/features/report/reportPolicy.ts'
import type {
  ReportDetail,
  ReportStatus,
} from '../src/features/report/reportTypes.ts'

const AUTHOR_ID = '10000000-0000-4000-8000-000000000001'
const REVIEWER_ID = '20000000-0000-4000-8000-000000000002'

function report(status: ReportStatus): ReportDetail {
  return {
    reportId: '30000000-0000-4000-8000-000000000003',
    reportCode: 'RPT-001',
    title: '테스트 보고서',
    summary: null,
    reportType: 'PERIODIC',
    reportStatus: status,
    generationMethod: 'MANUAL',
    periodFrom: '2026-07-01T00:00:00Z',
    periodTo: '2026-07-02T00:00:00Z',
    currentVersionNo: 1,
    approvedVersionNo: status === 'APPROVED' ? 1 : null,
    creatorType: 'USER',
    createdBy: AUTHOR_ID,
    approvedBy: status === 'APPROVED' ? REVIEWER_ID : null,
    approvedAt: status === 'APPROVED' ? '2026-07-03T00:00:00Z' : null,
    createdAt: '2026-07-02T00:00:00Z',
    updatedAt: '2026-07-02T00:00:00Z',
    filterCriteria: {},
    currentVersion: {
      reportId: '30000000-0000-4000-8000-000000000003',
      versionNo: 1,
      title: '테스트 보고서',
      summary: null,
      periodFrom: '2026-07-01T00:00:00Z',
      periodTo: '2026-07-02T00:00:00Z',
      llmModel: null,
      promptVersion: null,
      changeNote: null,
      creatorType: 'USER',
      createdBy: AUTHOR_ID,
      createdAt: '2026-07-02T00:00:00Z',
      content: '본문',
      evidenceSnapshot: {
        defectIds: [],
        missionIds: [],
        patrolAreaIds: [],
      },
      statistics: {
        aggregate: {
          missionCount: 0,
          completedMissionCount: 0,
          cancelledMissionCount: 0,
          failedMissionCount: 0,
          completionRatePercent: 0,
          totalMissionDurationSeconds: 0,
          incidentEventCount: 0,
          defectCount: 0,
          observationCount: 0,
        },
        defectsByStatus: [],
        defectsByType: [],
      },
    },
  }
}

const ALL_PERMISSIONS = [
  'REPORT_VIEW',
  'REPORT_CREATE',
  'REPORT_UPDATE',
  'REPORT_APPROVE',
]

test('DRAFT와 REJECTED는 update 권한 사용자에게만 수정·삭제를 제공한다', () => {
  for (const status of ['DRAFT', 'REJECTED'] as const) {
    const policy = getReportActionPolicy(
      report(status),
      AUTHOR_ID,
      ALL_PERMISSIONS,
    )
    assert.equal(policy.canEdit, true)
    assert.equal(policy.canDelete, true)
    assert.equal(policy.canSubmit, status === 'DRAFT')
    assert.equal(policy.canApprove, false)
    assert.equal(policy.canReject, false)
  }
})

test('IN_REVIEW는 자기 승인을 막고 승인 권한자의 반려를 허용한다', () => {
  const authorPolicy = getReportActionPolicy(
    report('IN_REVIEW'),
    AUTHOR_ID,
    ALL_PERMISSIONS,
  )
  assert.equal(authorPolicy.canApprove, false)
  assert.equal(authorPolicy.canReject, true)
  assert.equal(authorPolicy.canEdit, false)

  const reviewerPolicy = getReportActionPolicy(
    report('IN_REVIEW'),
    REVIEWER_ID,
    ALL_PERMISSIONS,
  )
  assert.equal(reviewerPolicy.canApprove, true)
  assert.equal(reviewerPolicy.canReject, true)
})

test('IN_REVIEW라도 REPORT_APPROVE 권한이 없으면 승인·반려를 제공하지 않는다', () => {
  const policy = getReportActionPolicy(
    report('IN_REVIEW'),
    REVIEWER_ID,
    ['REPORT_VIEW', 'REPORT_UPDATE'],
  )

  assert.equal(policy.canApprove, false)
  assert.equal(policy.canReject, false)
})

test('APPROVED와 권한 없는 사용자는 모든 변경 동작이 읽기 전용이다', () => {
  const approved = getReportActionPolicy(
    report('APPROVED'),
    REVIEWER_ID,
    ALL_PERMISSIONS,
  )
  assert.equal(approved.readOnly, true)
  assert.equal(approved.canEdit, false)
  assert.equal(approved.canDelete, false)
  assert.equal(approved.canApprove, false)
  assert.equal(approved.canReject, false)

  const viewer = getReportActionPolicy(
    report('DRAFT'),
    REVIEWER_ID,
    ['REPORT_VIEW'],
  )
  assert.equal(viewer.readOnly, true)
  assert.equal(viewer.canEdit, false)
  assert.equal(viewer.canSubmit, false)
  assert.equal(viewer.canDelete, false)
})
