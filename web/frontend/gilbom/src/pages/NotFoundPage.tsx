import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <section className="content-card empty-state" aria-labelledby="not-found-title">
      <span className="empty-state-icon" aria-hidden="true">
        404
      </span>
      <h2 id="not-found-title">요청한 화면을 찾을 수 없습니다.</h2>
      <p>주소를 확인하거나 기본 관리 화면으로 돌아가 주세요.</p>
      <Link className="primary-button" to="/robots">
        로봇 관리로 이동
      </Link>
    </section>
  )
}
