import { Search } from 'lucide-react'

interface DefectSearchBarProps {
  value: string
  onChange: (next: string) => void
  disabled?: boolean
}

/**
 * 검색만 떼어내 지도·필터 위에 가로로 깔아둔다. 조건 패널 안에 있으면 폭이 280px로
 * 묶여 결함 코드 하나도 다 안 보였다.
 */
export function DefectSearchBar({
  value,
  onChange,
  disabled = false,
}: DefectSearchBarProps) {
  return (
    <form
      className="content-card defect-search-bar"
      aria-label="결함 검색"
      onSubmit={(event) => event.preventDefault()}
    >
      <Search size={18} aria-hidden="true" />
      <input
        type="search"
        value={value}
        placeholder="결함 코드·위치 검색"
        maxLength={100}
        disabled={disabled}
        aria-label="결함 코드·위치 검색"
        onChange={(event) => onChange(event.target.value)}
      />
    </form>
  )
}
