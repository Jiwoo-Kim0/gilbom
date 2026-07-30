import assert from 'node:assert/strict'
import test from 'node:test'
import {
  calculateBoundaryAverageCenter,
  getBoundaryOuterPoints,
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
