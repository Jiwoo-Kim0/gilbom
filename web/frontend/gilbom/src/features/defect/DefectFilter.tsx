export function DefectFilter() {
  return (
    <form className="content-card filter-bar" onSubmit={(event) => event.preventDefault()}>
      <label>
        <span>처리 상태</span>
        <select defaultValue="all" name="status">
          <option value="all">전체</option>
          <option value="open">확인 필요</option>
          <option value="resolved">처리 완료</option>
        </select>
      </label>
      <label>
        <span>검색</span>
        <input name="query" placeholder="결함 코드 또는 로봇 코드" type="search" />
      </label>
      <button className="secondary-button" type="submit">
        필터 적용
      </button>
    </form>
  )
}
