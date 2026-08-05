import { useEffect, useRef, useState } from 'react'
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
  /*
   * 한글 IME가 조합 중인 값을 URL 필터에 바로 반영하면 상위 화면이 다시
   * 렌더링되면서 브라우저의 조합 문자열과 controlled value가 충돌한다. 입력창은
   * 로컬 값을 유지하고, 한 글자의 조합이 끝난 뒤에만 조회 조건을 반영한다.
   */
  const [draft, setDraft] = useState(value)
  const composingRef = useRef(false)
  const committedRef = useRef(value)

  useEffect(() => {
    committedRef.current = value
    if (!composingRef.current) {
      setDraft(value)
    }
  }, [value])

  const commit = (next: string) => {
    setDraft(next)
    if (committedRef.current !== next) {
      committedRef.current = next
      onChange(next)
    }
  }

  return (
    <form
      className="content-card defect-search-bar"
      aria-label="결함 검색"
      onSubmit={(event) => event.preventDefault()}
    >
      <Search size={18} aria-hidden="true" />
      <input
        type="search"
        value={draft}
        placeholder="결함 코드·위치 검색"
        maxLength={100}
        disabled={disabled}
        aria-label="결함 코드·위치 검색"
        onCompositionStart={() => {
          composingRef.current = true
        }}
        onCompositionEnd={(event) => {
          composingRef.current = false
          commit(event.currentTarget.value)
        }}
        onChange={(event) => {
          const next = event.currentTarget.value
          setDraft(next)
          if (!composingRef.current) {
            commit(next)
          }
        }}
      />
    </form>
  )
}
