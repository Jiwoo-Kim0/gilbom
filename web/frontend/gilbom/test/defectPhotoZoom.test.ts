import assert from 'node:assert/strict'
import test from 'node:test'
import {
  anchoredScroll,
  canZoomIn,
  canZoomOut,
  MAX_PHOTO_ZOOM,
  MIN_PHOTO_ZOOM,
  nextPhotoZoom,
  PHOTO_ZOOM_STEPS,
  photoZoomClass,
  photoZoomLabel,
} from '../src/features/defect/defectPhotoZoom.ts'
import type { PhotoZoom } from '../src/features/defect/defectPhotoZoom.ts'

test('한 단계씩 확대·축소한다', () => {
  // 시작 구간은 20%p씩이다. ×1 → ×1.5로 뛰면 한 번에 너무 많이 확대된다.
  assert.equal(nextPhotoZoom(100, 1), 120)
  assert.equal(nextPhotoZoom(120, 1), 140)
  assert.equal(nextPhotoZoom(180, 1), 200)
  // ×2를 넘으면 한 단계의 체감이 작아져 간격을 넓힌다.
  assert.equal(nextPhotoZoom(200, 1), 240)
  assert.equal(nextPhotoZoom(360, 1), 400)
  assert.equal(nextPhotoZoom(120, -1), 100)
  assert.equal(nextPhotoZoom(240, -1), 200)
})

test('한 단계가 20%p를 넘지 않는다 — ×2 이하 구간', () => {
  // 부위를 맞추는 구간이라 여기서 간격이 벌어지면 "너무 많이 확대된다"로 돌아온다.
  const gentle = PHOTO_ZOOM_STEPS.filter((zoom) => zoom <= 200)
  for (let index = 1; index < gentle.length; index += 1) {
    assert.ok(
      gentle[index] - gentle[index - 1] <= 20,
      `${gentle[index - 1]} → ${gentle[index]} 간격이 너무 크다`,
    )
  }
})

test('양 끝에서는 배율이 그대로다', () => {
  // 한 바퀴 돌면 400%에서 확대를 눌렀는데 원본으로 튀어 당황한다.
  assert.equal(nextPhotoZoom(MAX_PHOTO_ZOOM, 1), MAX_PHOTO_ZOOM)
  assert.equal(nextPhotoZoom(MIN_PHOTO_ZOOM, -1), MIN_PHOTO_ZOOM)
})

test('모르는 배율은 원본 크기로 되돌린다', () => {
  assert.equal(nextPhotoZoom(137 as PhotoZoom, 1), MIN_PHOTO_ZOOM)
})

test('버튼 활성 여부가 양 끝에서만 닫힌다', () => {
  assert.equal(canZoomOut(MIN_PHOTO_ZOOM), false)
  assert.equal(canZoomIn(MIN_PHOTO_ZOOM), true)
  assert.equal(canZoomIn(MAX_PHOTO_ZOOM), false)
  assert.equal(canZoomOut(MAX_PHOTO_ZOOM), true)
})

test('모든 단계에 짝이 되는 CSS class가 있다', () => {
  // 한쪽만 늘리면 그 단계는 화면에서 기준 배율로 보인다. App.css와 함께 고쳐야 한다.
  assert.deepEqual(
    PHOTO_ZOOM_STEPS.map((zoom) => photoZoomClass(zoom)),
    [
      'defect-photo-zoom-100',
      'defect-photo-zoom-120',
      'defect-photo-zoom-140',
      'defect-photo-zoom-160',
      'defect-photo-zoom-180',
      'defect-photo-zoom-200',
      'defect-photo-zoom-240',
      'defect-photo-zoom-280',
      'defect-photo-zoom-320',
      'defect-photo-zoom-360',
      'defect-photo-zoom-400',
    ],
  )
})

test('배율 표시는 기준 대비 배수로 적는다', () => {
  // 기준이 "사진 전체가 보이는 크기"라 원본 픽셀의 100%가 아니다. 퍼센트로 적으면
  // 100%인데 원본보다 작아 보이는 상황이 생긴다.
  assert.deepEqual(
    PHOTO_ZOOM_STEPS.map((zoom) => photoZoomLabel(zoom)),
    [
      '×1', '×1.2', '×1.4', '×1.6', '×1.8', '×2',
      '×2.4', '×2.8', '×3.2', '×3.6', '×4',
    ],
  )
})

test('확대해도 기준점이 화면에서 같은 자리에 남는다', () => {
  // 1000px 상자 가운데(500)를 기준으로 두 배 확대. 내용이 두 배가 되므로 기준점
  // 왼쪽에 있던 500px도 1000px이 되고, 그만큼 스크롤해야 같은 자리에 남는다.
  assert.equal(anchoredScroll(0, 500, 2), 500)
  // 이미 스크롤한 상태에서도 같은 규칙이다.
  assert.equal(anchoredScroll(500, 500, 2), 1500)
  // 커서가 왼쪽 끝(0)이면 스크롤이 그대로다 — 그 자리가 고정점이다.
  assert.equal(anchoredScroll(0, 0, 2), 0)
})

test('축소하면 스크롤이 줄고 0 아래로는 가지 않는다', () => {
  assert.equal(anchoredScroll(500, 500, 0.5), 0)
  assert.equal(anchoredScroll(1500, 500, 0.5), 500)
  // 배율을 크게 줄이면 음수가 되는데, 브라우저가 자르기 전에 여기서 자른다.
  assert.equal(anchoredScroll(0, 500, 0.5), 0)
})
