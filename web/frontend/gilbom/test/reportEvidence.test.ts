import assert from 'node:assert/strict'
import test from 'node:test'
import {
  loadReportEvidenceImages,
} from '../src/features/report/reportEvidence.ts'
import type { DefectImageUrl } from '../src/features/defect/defectTypes.ts'

function image(defectId: string): DefectImageUrl {
  return {
    defectId,
    eventId: null,
    imageId: `image-${defectId}`,
    imageUrl: `/api/v1/images/${defectId}`,
    expiresAt: '2026-08-05T06:00:00Z',
    imageWidth: 1280,
    imageHeight: 720,
    bboxNorm: null,
  }
}

test('보고서 근거 결함마다 기존 이미지 URL 조회 기능을 재사용한다', async () => {
  const requested: string[] = []
  const results = await loadReportEvidenceImages(
    ['defect-1', 'defect-2'],
    async (defectId) => {
      requested.push(defectId)
      return image(defectId)
    },
  )

  assert.deepEqual(requested, ['defect-1', 'defect-2'])
  assert.deepEqual(
    results.map((result) => result.kind),
    ['ready', 'ready'],
  )
})

test('이미지 없음과 조회 실패를 보고서 전체 실패로 전파하지 않는다', async () => {
  const results = await loadReportEvidenceImages(
    ['missing', 'failed'],
    async (defectId) => {
      if (defectId === 'missing') {
        throw { status: 404 }
      }
      throw new Error('storage unavailable')
    },
  )

  assert.equal(results[0]?.kind, 'none')
  assert.equal(results[1]?.kind, 'error')
})
