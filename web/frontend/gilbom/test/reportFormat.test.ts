import assert from 'node:assert/strict'
import test from 'node:test'
import {
  formatReportDisplayTitle,
  toReportPeriodEndInstant,
  toReportPeriodStartInstant,
} from '../src/features/report/reportFormat.ts'

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
