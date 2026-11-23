import assert from 'node:assert/strict'
import test from 'node:test'
import {
  formatReportContentIdentifiers,
  formatReportDisplayTitle,
  toReportPeriodEndInstant,
  toReportPeriodStartInstant,
} from '../src/features/report/reportFormat.ts'

test('구형 보고서의 내부 UUID를 사용자용 결함 ID로 정규화한다', () => {
  const defectId = '76d5ee01-b36b-4fa3-973c-ab7a46a824b4'
  const incidentCode = 'INC-20260805-000004'
  const content = [
    '## 주요 결함',
    `- 결함 ID: ${defectId} · 사건 코드: ${incidentCode} · 결함 유형: 파손`,
    '',
    '## 조치 권고',
    `- 현장 확인 — 관련 결함 ID: ${defectId}`,
  ].join('\n')

  const formatted = formatReportContentIdentifiers(content)

  assert.equal(formatted.includes(defectId), false)
  assert.equal(
    formatted.includes(`결함 ID: ${incidentCode} · 결함 유형: 파손`),
    true,
  )
  assert.equal(
    formatted.includes(`관련 결함 ID: ${incidentCode}`),
    true,
  )
})

test('검증 가능한 UUID와 사건 코드 쌍이 없으면 본문을 변경하지 않는다', () => {
  const content = [
    'UUID 76d5ee01-b36b-4fa3-973c-ab7a46a824b4',
    '<script>alert(1)</script>',
  ].join('\n')

  assert.equal(formatReportContentIdentifiers(content), content)
})

test('단건 결함 보고서 제목을 결함코드 보고서 버전 형식으로 고정한다', () => {
  assert.equal(
    formatReportDisplayTitle(
      '2026-08-04 ~ 2026-08-05 이상징후 보고',
      '# 보고서\n\n주요 결함 INC-20260805-000004',
      'RPT-F123',
      1,
      true,
    ),
    'INC-20260805-000004 보고서 v1',
  )
})

test('새 서버 제목에서는 결함코드를 직접 사용하고 일반 보고서는 원래 제목을 보존한다', () => {
  assert.equal(
    formatReportDisplayTitle(
      'INC-20260805-000004 보고서',
      '본문',
      'RPT-F123',
      3,
      true,
    ),
    'INC-20260805-000004 보고서 v3',
  )
  assert.equal(
    formatReportDisplayTitle(
      '주간 운영 보고서',
      '본문',
      'RPT-F124',
      2,
      false,
    ),
    '주간 운영 보고서 v2',
  )
})

test('기간 검색 날짜를 사용자 지역의 하루 전체 범위로 변환한다', () => {
  const start = new Date(toReportPeriodStartInstant('2026-08-01') ?? '')
  const end = new Date(toReportPeriodEndInstant('2026-08-05') ?? '')

  assert.deepEqual(
    [start.getFullYear(), start.getMonth(), start.getDate(), start.getHours()],
    [2026, 7, 1, 0],
  )
  assert.deepEqual(
    [
      end.getFullYear(),
      end.getMonth(),
      end.getDate(),
      end.getHours(),
      end.getMinutes(),
      end.getSeconds(),
      end.getMilliseconds(),
    ],
    [2026, 7, 5, 23, 59, 59, 999],
  )
  assert.equal(toReportPeriodStartInstant(''), undefined)
  assert.equal(toReportPeriodEndInstant('2026-02-30'), undefined)
})
