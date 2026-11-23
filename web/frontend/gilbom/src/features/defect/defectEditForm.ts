import type {
  DefectListItem,
  DefectType,
  DefectUpdateRequest,
  RiskLevel,
} from './defectTypes'

/**
 * 결함 수정 창의 순수 계산. 컴포넌트 파일에서 떼어냈다 — 한 파일이 컴포넌트와 순수
 * 함수를 같이 내보내면 Fast Refresh가 상태를 보존하지 못하고
 * (`react-refresh/only-export-components`), 단위 시험에서 부를 수도 없다.
 */

export type DefectEditForm = {
  defectType: DefectType
  riskLevel: RiskLevel
  zoneId: string
  locationLabel: string
  reviewRequired: boolean
  reason: string
}

export const EDIT_REASON_MAX_LENGTH = 1000

/**
 * 서버는 PATCH 본문에서 null인 항목을 "변경 없음"으로 읽고, 바뀐 것이 하나도 없으면
 * COMMON_INVALID_REQUEST를 돌려준다. 그래서 화면도 **바뀐 축만** 담아 보내고, 아무것도
 * 바뀌지 않았으면 `null`을 돌려 아예 보내지 않는다.
 */
export function buildDefectUpdateRequest(
  defect: DefectListItem,
  form: DefectEditForm,
): DefectUpdateRequest | null {
  const request: DefectUpdateRequest = { reason: form.reason.trim() }
  let changed = false

  if (form.defectType !== defect.defectType) {
    request.defectType = form.defectType
    changed = true
  }
  if (form.riskLevel !== defect.riskLevel) {
    request.riskLevel = form.riskLevel
    changed = true
  }
  // 빈 입력은 "지운다"가 아니라 "건드리지 않는다"로 다룬다. 서버에 null 지우기
  // 계약이 없어서, 화면에서 지운 것처럼 보이게 하면 거짓말이 된다.
  const zoneId = form.zoneId.trim()
  if (zoneId.length > 0 && zoneId !== (defect.zoneId ?? '')) {
    request.zoneId = zoneId
    changed = true
  }
  const locationLabel = form.locationLabel.trim()
  if (
    locationLabel.length > 0
    && locationLabel !== (defect.locationLabel ?? '')
  ) {
    request.locationLabel = locationLabel
    changed = true
  }
  if (form.reviewRequired !== defect.reviewRequired) {
    request.reviewRequired = form.reviewRequired
    changed = true
  }

  return changed ? request : null
}

/**
 * 저장을 막고 있는 이유. 버튼만 흐려두면 무엇이 모자란지 알 수 없어, 이 말을 버튼 옆에
 * 그대로 띄운다. 막을 것이 없으면 `null`이다.
 */
export function defectEditBlockingHint(
  request: DefectUpdateRequest | null,
  reason: string,
): string | null {
  if (request === null) {
    return '아직 바꾼 항목이 없습니다.'
  }
  if (reason.trim().length === 0) {
    return '수정 사유를 적어야 저장할 수 있습니다.'
  }
  return null
}
