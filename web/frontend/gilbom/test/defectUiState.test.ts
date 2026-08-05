import assert from 'node:assert/strict'
import test from 'node:test'
import {
  bboxForDefectMapFilter,
  defectMapFilterKey,
  defectTypeTone,
} from '../src/features/defect/defectMapState.ts'
import {
  DEFECT_SORT_OPTIONS,
  EMPTY_DEFECT_FILTERS,
} from '../src/features/defect/defectTypes.ts'

test('날짜 필터가 바뀌면 이전 지도 bbox를 재사용하지 않는다', () => {
  const initialFilters = {
    ...EMPTY_DEFECT_FILTERS,
    from: '2026-08-01T00:00',
  }
  const initialKey = defectMapFilterKey(initialFilters)
  const viewport = {
    filterKey: initialKey,
    bbox: '128.1,36.1,128.4,36.4',
  }

  assert.equal(bboxForDefectMapFilter(viewport, initialKey), viewport.bbox)

  const changedKey = defectMapFilterKey({
    ...initialFilters,
    from: '2026-08-05T00:00',
  })
  assert.equal(bboxForDefectMapFilter(viewport, changedKey), '')
})

test('파손과 미설치 마커는 서로 다른 유형 색상 클래스를 사용한다', () => {
  assert.equal(defectTypeTone('DAMAGED'), 'damaged')
  assert.equal(defectTypeTone('MISSING'), 'missing')
  assert.notEqual(defectTypeTone('DAMAGED'), defectTypeTone('MISSING'))
  assert.equal(defectTypeTone('INSTALLATION_INVALID'), 'installation-invalid')
  assert.equal(defectTypeTone('UNKNOWN_ANOMALY'), 'unknown-anomaly')
})

test('최초 발견순은 오래된 최초 발견부터 정렬한다', () => {
  const option = DEFECT_SORT_OPTIONS.find(
    ({ label }) => label === '최초 발견순',
  )
  assert.equal(option?.value, 'firstSeenAt,asc')
})
