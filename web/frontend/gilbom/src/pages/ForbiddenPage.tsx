import { Link } from 'react-router'

export function ForbiddenPage() {
  return (
    <section className="content-card empty-state" aria-labelledby="forbidden-title">
      <span className="empty-state-icon" aria-hidden="true">
        403
      </span>
      <h2 id="forbidden-title">이 화면에 접근할 권한이 없습니다.</h2>
      <p>
        메뉴를 숨기는 것과 별개로 직접 URL 접근도 차단했습니다.
        권한이 필요하다면 시스템 관리자에게 요청해주세요.
      </p>
      <Link className="primary-button" to="/defects">
        결함 관리로 이동
      </Link>
    </section>
  )
}
