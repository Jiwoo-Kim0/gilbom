import { useState } from 'react'
import { RotateCcw, SlidersHorizontal } from 'lucide-react'
import { DefectFilterModal } from './DefectFilterModal'
import {
  countActiveFilterGroups,
  DEFAULT_DEFECT_FILTERS,
} from './defectTypes'
import type { DefectFilters } from './defectTypes'

interface DefectFilterBarProps {
  filters: DefectFilters
  onChange: (filters: DefectFilters) => void
  disabled?: boolean
  /**
   * "초기화"를 눌렀다는 신호. 검색어를 비우는 것은 `onChange`로 전달되지만,
   * 아직 올려보내지 않은(디바운스 대기 중인) 입력까지 버리려면 상위가 알아야 한다.
   */
  onReset?: () => void
}

/**
 * 툴바 오른쪽 끝. **버튼 둘뿐이다** — 조건을 여는 "필터"와 "초기화".
 *
 * <p>한동안 조건마다 알약을 하나씩 늘어놓았는데, 여러 개를 고르면 요약 글자가 길어져
 * 알약이 줄을 넘나들며 툴바 높이가 들쭉날쭉해졌다. 조건은 전부
 * {@link DefectFilterModal} 안으로 넣고, 화면에는 **몇 개가 걸렸는지**만 배지로 남겼다.
 */
export function DefectFilterBar({
  filters,
  onChange,
  disabled = false,
  onReset,
}: DefectFilterBarProps) {
  const [detailOpen, setDetailOpen] = useState(false)
  const activeCount = countActiveFilterGroups(filters)

  return (
    <div className="defect-filter-bar">
      <button
        type="button"
        className={
          'defect-filter-open-button'
          + (activeCount > 0 ? ' is-active' : '')
          + (detailOpen ? ' is-open' : '')
        }
        disabled={disabled}
        aria-haspopup="dialog"
        aria-expanded={detailOpen}
        // 배지 숫자는 `aria-hidden`이다. 그 뜻을 여기서 말로 풀어준다.
        aria-label={activeCount > 0 ? `필터 · 조건 ${activeCount}개 적용됨` : '필터'}
        onClick={() => setDetailOpen(true)}
      >
        <SlidersHorizontal size={16} aria-hidden="true" />
        필터
        {activeCount > 0 && (
          <span className="defect-filter-open-count" aria-hidden="true">
            {activeCount}
          </span>
        )}
      </button>

      {/*
        검색어까지 함께 지운다. 검색창이 왼쪽에 보이므로 지워진 것도 바로 보인다.
        돌아가는 자리는 빈 조건이 아니라 **첫 진입과 같은 조건**이다 — 처리 상태
        셋(신규·접수·처리 중)이 다시 켜진다.
      */}
      <button
        className="defect-filter-reset"
        type="button"
        disabled={disabled}
        onClick={() => {
          onChange(DEFAULT_DEFECT_FILTERS)
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
