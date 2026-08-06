import { useEffect, useRef, useState } from 'react'
import { Search } from 'lucide-react'

interface DefectSearchBarProps {
  value: string
  onChange: (next: string) => void
  disabled?: boolean
}

/**
 * 마지막 입력 뒤 이만큼 조용해지면 조회한다. 보통 타자 간격이 200~300ms라, 치다가
 * 잠깐 멈출 때마다 한 번씩 나가고 다 치고 나면 곧바로 나간다.
 */
const SEARCH_DEBOUNCE_MS = 300

/**
 * 화면 맨 위 툴바의 왼쪽 칸이다. 오른쪽에는 조건 알약들이 붙는다.
 *
 * <p>폭은 결함 코드 한 줄(`INC-20260805-000001`)이 들어갈 만큼만 준다. 예전에는 툴바
 * 전체를 혼자 썼는데, 검색어는 대개 코드 하나라 남는 폭이 전부 놀았다.
 *
 * <p>검색어는 상위에서 URL로 가고 **URL 변경이 곧 조회 요청**이다. 그래서 타자마다
 * 올려보내지 않고 {@link SEARCH_DEBOUNCE_MS}만큼 묶는다. 급할 때는 Enter로 즉시 보낸다.
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
  const timerRef = useRef<number | null>(null)
  /*
   * 기다리는 사이에 다른 조건(상태·심각도…)이 바뀌면 상위가 새 `onChange`를 넘긴다.
   * 타이머가 렌더 당시의 함수를 물고 있으면 그때의 낡은 조건으로 URL을 덮어써서
   * 방금 바꾼 조건이 사라진다. 항상 최신 것으로 보낸다.
   */
  const onChangeRef = useRef(onChange)

  useEffect(() => {
    onChangeRef.current = onChange
  }, [onChange])

  const clearTimer = () => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }

  /** 지금 바로 올려보낸다. 대기 중인 타이머가 있으면 버린다. */
  const flush = (next: string) => {
    clearTimer()
    if (committedRef.current !== next) {
      committedRef.current = next
      onChangeRef.current(next)
    }
  }

  /**
   * 타이머를 effect가 아니라 여기서 직접 다룬다. effect로 하면 상위가 매 렌더마다
   * 새 `onChange`를 넘기는 탓에 의존성이 흔들려 타이머가 계속 다시 시작된다.
   */
  const schedule = (next: string) => {
    clearTimer()
    timerRef.current = window.setTimeout(
      () => flush(next),
      SEARCH_DEBOUNCE_MS,
    )
  }

  useEffect(() => {
    /*
     * 바깥에서 검색어가 바뀌면(필터 초기화 등) 대기 중인 타이머를 버린다. 남겨두면
     * 지워진 검색어가 300ms 뒤에 되살아난다.
     */
    clearTimer()
    committedRef.current = value
    if (!composingRef.current) {
      setDraft(value)
    }
  }, [value])

  // 입력 중에 화면을 떠나면 타이머만 남는다.
  useEffect(() => clearTimer, [])

  return (
    <form
      className="defect-search-bar"
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
          const next = event.currentTarget.value
          setDraft(next)
          schedule(next)
        }}
        onKeyDown={(event) => {
          // 조합 중 Enter는 IME가 글자를 확정하는 키다. 가로채지 않는다.
          if (event.key !== 'Enter' || event.nativeEvent.isComposing) {
            return
          }
          event.preventDefault()
          flush(event.currentTarget.value)
        }}
        onChange={(event) => {
          const next = event.currentTarget.value
          setDraft(next)
          if (!composingRef.current) {
            schedule(next)
          }
        }}
      />
    </form>
  )
}
