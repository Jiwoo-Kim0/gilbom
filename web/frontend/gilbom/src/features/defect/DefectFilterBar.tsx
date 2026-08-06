import { useState } from 'react'
import { RotateCcw, SlidersHorizontal } from 'lucide-react'
import { DefectFilterChip } from './DefectFilterChip'
import { DefectConfidenceField, DefectDateField } from './DefectFilterFields'
import { DefectFilterModal } from './DefectFilterModal'
import { DefectFilterOptions } from './DefectFilterOptions'
import {
  summarizeConfidence,
  summarizeDate,
  summarizeSelection,
} from './defectFilterSummary'
import { defectTypeTone } from './defectMapState'
import {
  DEFECT_TYPES,
  DEFECT_TYPE_LABELS,
  EMPTY_DEFECT_FILTERS,
  RISK_LEVELS,
  RISK_LEVEL_LABELS,
  WORKFLOW_STATUSES,
  WORKFLOW_STATUS_LABELS,
} from './defectTypes'
import type { DefectFilters } from './defectTypes'

interface DefectFilterBarProps {
  filters: DefectFilters
  onChange: (filters: DefectFilters) => void
  disabled?: boolean
  /**
   * "필터 초기화"를 눌렀다는 신호. 검색어를 비우는 것은 `onChange`로 전달되지만,
   * 아직 올려보내지 않은(디바운스 대기 중인) 입력까지 버리려면 상위가 알아야 한다.
   */
  onReset?: () => void
}

/**
 * 검색창 오른쪽에 붙는 조건 줄이다. 조건마다 알약 하나이고, 누르면 그 자리에 패널이
 * 열린다. 맨 앞의 아이콘 버튼은 조건을 한 화면에 펼치는 상세 필터를 연다.
 *
 * <p>예전에는 지도 오른쪽 260px 사이드바였다. 조건을 위로 올리면서 지도가 가로 폭을
 * 전부 쓰게 됐다 — 지도가 주인공인 화면인데 조건 다섯 개가 그 폭을 상시로 깎고 있었다.
 *
 * <p>알약에서 고른 것은 **즉시** 반영된다. 한 번에 조건 하나만 바뀌므로 조회가 한 번
 * 나가는 것이 자연스럽다. 여러 조건을 한꺼번에 짜맞추는 상세 필터는
 * {@link DefectFilterModal}에서 "적용하기"로 묶어 보낸다.
 */
export function DefectFilterBar({
  filters,
  onChange,
  disabled = false,
  onReset,
}: DefectFilterBarProps) {
  const [detailOpen, setDetailOpen] = useState(false)

  const update = (partial: Partial<DefectFilters>) => {
    onChange({ ...filters, ...partial })
  }

  return (
    <div className="defect-filter-bar" role="group" aria-label="결함 필터">
      <button
        type="button"
        className={`defect-filter-detail-button${detailOpen ? ' is-open' : ''}`}
        disabled={disabled}
        aria-haspopup="dialog"
        aria-expanded={detailOpen}
        aria-label="상세 필터"
        title="상세 필터"
        onClick={() => setDetailOpen(true)}
      >
        <SlidersHorizontal size={17} aria-hidden="true" />
      </button>

      <DefectFilterChip
        label="처리 상태"
        summary={summarizeSelection(
          filters.workflowStatuses,
          WORKFLOW_STATUS_LABELS,
        )}
        count={filters.workflowStatuses.length}
        active={filters.workflowStatuses.length > 0}
        disabled={disabled}
      >
        <DefectFilterOptions
          label="처리 상태"
          options={WORKFLOW_STATUSES}
          optionLabels={WORKFLOW_STATUS_LABELS}
          selected={filters.workflowStatuses}
          onChange={(workflowStatuses) => update({ workflowStatuses })}
        />
      </DefectFilterChip>

      {/*
        결함 유형 점은 지도 마커와 같은 `--defect-*` 변수를 쓴다. 처리 상태에 점이
        있던 것은 마커가 상태 색이던 시절의 잔재라 걷어냈다.
      */}
      <DefectFilterChip
        label="결함 유형"
        summary={summarizeSelection(filters.defectTypes, DEFECT_TYPE_LABELS)}
        count={filters.defectTypes.length}
        active={filters.defectTypes.length > 0}
        disabled={disabled}
      >
        <DefectFilterOptions
          label="결함 유형"
          options={DEFECT_TYPES}
          optionLabels={DEFECT_TYPE_LABELS}
          selected={filters.defectTypes}
          onChange={(defectTypes) => update({ defectTypes })}
          renderMark={(defectType) => (
            <span
              className={`defect-type-dot defect-type-dot-${defectTypeTone(defectType)}`}
              aria-hidden="true"
            />
          )}
        />
      </DefectFilterChip>

      <DefectFilterChip
        label="심각도"
        summary={summarizeSelection(filters.severities, RISK_LEVEL_LABELS)}
        count={filters.severities.length}
        active={filters.severities.length > 0}
        disabled={disabled}
      >
        <DefectFilterOptions
          label="심각도"
          options={RISK_LEVELS}
          optionLabels={RISK_LEVEL_LABELS}
          selected={filters.severities}
          onChange={(severities) => update({ severities })}
        />
      </DefectFilterChip>

      <DefectFilterChip
        label="기간"
        summary={summarizeDate(filters.from)}
        active={filters.from.length > 0}
        disabled={disabled}
      >
        <DefectDateField
          from={filters.from}
          disabled={disabled}
          onChange={(from) => update({ from })}
        />
      </DefectFilterChip>

      {/* 알약에서는 "AI"를 뗀다. 다섯 개가 한 줄에 들어가야 하고, 열면 패널 안 라벨이
          "최소 AI 신뢰도"로 온전히 보인다. */}
      <DefectFilterChip
        label="신뢰도"
        summary={summarizeConfidence(filters.minConfidence)}
        active={filters.minConfidence !== null}
        disabled={disabled}
      >
        <DefectConfidenceField
          minConfidence={filters.minConfidence}
          disabled={disabled}
          onChange={(minConfidence) => update({ minConfidence })}
        />
      </DefectFilterChip>

      {/*
        검색어까지 함께 지운다. 검색창이 왼쪽에 보이므로 지워진 것도 바로 보인다.
        글자는 "초기화"만 — 알약 다섯과 한 줄에 들어가야 한다. 무엇을 되돌리는지는
        `aria-label`로 온전히 읽힌다.
      */}
      <button
        className="defect-filter-reset"
        type="button"
        aria-label="필터 초기화"
        disabled={disabled}
        onClick={() => {
          onChange(EMPTY_DEFECT_FILTERS)
          onReset?.()
        }}
      >
        <RotateCcw size={15} aria-hidden="true" />
        초기화
      </button>

      {detailOpen && (
        <DefectFilterModal
          filters={filters}
          onClose={() => setDetailOpen(false)}
          onApply={(next) => {
            onChange(next)
            setDetailOpen(false)
          }}
        />
      )}
    </div>
  )
}
