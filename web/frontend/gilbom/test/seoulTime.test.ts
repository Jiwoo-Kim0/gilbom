import assert from 'node:assert/strict'
import test from 'node:test'
import {
  instantToSeoulDateInput,
  instantToSeoulInput,
  SEOUL_TIME_ZONE,
  seoulDateTimeFormat,
  seoulInputToInstant,
  seoulParts,
} from '../src/shared/time/seoulTime.ts'

/*
 * 이 시험들은 **돌리는 기계의 타임존과 무관하게** 같은 결과여야 한다. 그래서 기대값을
 * 서울 기준으로 못박아 적는다. 구현이 로컬 타임존으로 돌아가면 KST가 아닌 기계에서
 * 바로 깨진다.
 */

test('서울 타임존이 실제로 박혀 있다', () => {
  // 옵션을 넘겨도 timeZone은 덮이지 않아야 한다.
  const formatter = seoulDateTimeFormat({ dateStyle: 'short' })
  assert.equal(formatter.resolvedOptions().timeZone, SEOUL_TIME_ZONE)
})

test('UTC 순간을 서울 벽시계로 읽는다', () => {
  // 2026-08-04T02:00Z = 같은 날 11:00 KST
  const parts = seoulParts(new Date('2026-08-04T02:00:00Z'))
  assert.deepEqual(parts, {
    year: 2026, month: 8, day: 4, hour: 11, minute: 0, second: 0,
  })
})

test('날짜가 넘어가는 경계도 서울 기준이다', () => {
  // 2026-08-05T16:00Z는 KST로 다음 날 새벽 1시다. UTC 날짜(5일)와 하루 다르다.
  const parts = seoulParts(new Date('2026-08-05T16:00:00Z'))
  assert.equal(parts.day, 6)
  assert.equal(parts.hour, 1)
})

test('자정은 0시로 읽는다', () => {
  // ko-KR 구현이 자정을 24시로 주는 경우가 있어 0으로 맞춘다.
  const parts = seoulParts(new Date('2026-08-05T15:00:00Z'))
  assert.equal(parts.hour, 0)
  assert.equal(parts.day, 6)
})

test('날짜 입력은 서울 자정으로 해석한다', () => {
  // 로컬로 파싱하면 보는 사람 기계에 따라 조회 경계가 달라진다.
  assert.equal(
    seoulInputToInstant('2026-08-03'),
    '2026-08-02T15:00:00.000Z',
  )
})

test('datetime-local 입력도 서울로 해석한다', () => {
  assert.equal(
    seoulInputToInstant('2026-08-03T09:30'),
    '2026-08-03T00:30:00.000Z',
  )
  assert.equal(
    seoulInputToInstant('2026-08-03T09:30:45'),
    '2026-08-03T00:30:45.000Z',
  )
})

test('형식이 아니면 null이다 — 빈 값도 조회 조건이 아니다', () => {
  assert.equal(seoulInputToInstant(''), null)
  assert.equal(seoulInputToInstant('2026-08'), null)
  assert.equal(seoulInputToInstant('어제'), null)
})

test('입력 값으로 되돌리는 변환은 서울 기준이다', () => {
  assert.equal(
    instantToSeoulInput('2026-08-04T02:00:00Z'),
    '2026-08-04T11:00',
  )
  assert.equal(
    instantToSeoulDateInput('2026-08-05T16:00:00Z'),
    '2026-08-06',
  )
})

test('입력 → 순간 → 입력이 제자리로 돌아온다', () => {
  const input = '2026-08-03T09:30'
  const instant = seoulInputToInstant(input)
  assert.ok(instant !== null)
  assert.equal(instantToSeoulInput(instant), input)
})
