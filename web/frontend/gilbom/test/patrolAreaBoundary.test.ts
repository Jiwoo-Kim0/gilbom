import assert from 'node:assert/strict'
import test from 'node:test'
import {
  calculateBoundaryAverageCenter,
  createPolygonBoundary,
  getBoundaryOuterPoints,
  hasBoundarySelfIntersections,
  orderBoundaryPointsForPolygon,
  polygonSignedArea,
} from '../src/features/mission/patrolAreaBoundary.ts'
import type {
  GeoJsonMultiPolygon,
  GeoJsonPolygon,
} from '../src/features/mission/missionTypes.ts'

test('닫힌 Polygon의 중복 마지막 점을 제외해 경계점 평균 중심을 계산한다', () => {
  const boundary: GeoJsonPolygon = {
    type: 'Polygon',
    coordinates: [[
      [128.416, 36.107],
      [128.417, 36.107],
      [128.417, 36.108],
      [128.416, 36.107],
    ]],
  }

  assert.deepEqual(getBoundaryOuterPoints(boundary), [
    [128.416, 36.107],
    [128.417, 36.107],
    [128.417, 36.108],
  ])
  const center = calculateBoundaryAverageCenter(boundary)
  assert.ok(center !== null)
  assert.ok(Math.abs(center[0] - 128.41666666666666) < 1e-12)
  assert.ok(Math.abs(center[1] - 36.10733333333334) < 1e-12)
})

test('MultiPolygon은 각 외곽 Ring 점을 합쳐 평균을 계산하고 Hole은 제외한다', () => {
  const boundary: GeoJsonMultiPolygon = {
    type: 'MultiPolygon',
    coordinates: [
      [[
        [127, 36],
        [129, 36],
        [129, 38],
        [127, 36],
      ], [
        [127.5, 36.5],
        [128, 36.5],
        [127.5, 36.5],
      ]],
      [[
        [131, 40],
        [133, 40],
        [133, 42],
        [131, 40],
      ]],
    ],
  }

  assert.deepEqual(calculateBoundaryAverageCenter(boundary), [
    782 / 6,
    232 / 6,
  ])
})

test('외곽 경계점이 없으면 평균 중심을 만들지 않는다', () => {
  const boundary: GeoJsonPolygon = {
    type: 'Polygon',
    coordinates: [[]],
  }

  assert.equal(calculateBoundaryAverageCenter(boundary), null)
})

test('같은 점 집합은 클릭 순열과 무관하게 같은 반시계 Polygon 순서가 된다', () => {
  const firstPermutation = [
    [128.417, 36.108],
    [128.416, 36.107],
    [128.416, 36.108],
    [128.417, 36.107],
  ] as GeoJsonPolygon['coordinates'][number]
  const secondPermutation = [
    [128.416, 36.108],
    [128.417, 36.107],
    [128.416, 36.107],
    [128.417, 36.108],
  ] as GeoJsonPolygon['coordinates'][number]
  const expected = [
    [128.416, 36.107],
    [128.417, 36.107],
    [128.417, 36.108],
    [128.416, 36.108],
  ]

  assert.deepEqual(orderBoundaryPointsForPolygon(firstPermutation), expected)
  assert.deepEqual(orderBoundaryPointsForPolygon(secondPermutation), expected)
  assert.ok(polygonSignedArea(expected) > 0)
  assert.equal(hasBoundarySelfIntersections(expected), false)
})

test('교차 입력과 내부 점을 모든 점이 포함된 교차 없는 Tour로 재배치한다', () => {
  const clickedPoints = [
    [128.417, 36.108],
    [128.416, 36.107],
    [128.4165, 36.1075],
    [128.416, 36.108],
    [128.417, 36.107],
  ] as GeoJsonPolygon['coordinates'][number]

  const ordered = orderBoundaryPointsForPolygon(clickedPoints)

  assert.equal(ordered.length, clickedPoints.length)
  assert.deepEqual(
    new Set(ordered.map((point) => point.join(':'))),
    new Set(clickedPoints.map((point) => point.join(':'))),
  )
  assert.equal(hasBoundarySelfIntersections(ordered), false)
  assert.ok(polygonSignedArea(ordered) > 0)
})

test('GeoJSON 생성은 중복점을 제거하고 자동 정렬한 뒤 Ring을 닫는다', () => {
  const boundary = createPolygonBoundary([
    [128.417, 36.108],
    [128.416, 36.107],
    [128.417, 36.107],
    [128.416, 36.108],
    [128.416, 36.107],
  ])

  assert.equal(boundary.coordinates[0].length, 5)
  assert.deepEqual(
    boundary.coordinates[0][0],
    boundary.coordinates[0].at(-1),
  )
  assert.equal(
    hasBoundarySelfIntersections(boundary.coordinates[0].slice(0, -1)),
    false,
  )
  assert.ok(polygonSignedArea(boundary.coordinates[0].slice(0, -1)) > 0)
})
