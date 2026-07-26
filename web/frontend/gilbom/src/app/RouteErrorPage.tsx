import { Link } from 'react-router'

export function RouteErrorPage() {
  // router 예외 객체는 내부 경로나 서버 메시지를 포함할 수 있어 화면에 직접 출력하지 않는다.
  return (
    <main className="route-error" id="main-content">
      <p className="eyebrow">ROUTE ERROR</p>
      <h1>화면을 불러오지 못했습니다.</h1>
      <p>잠시 후 다시 시도하거나 결함 관리 화면으로 돌아가 주세요.</p>
      <Link className="primary-button" to="/defects">
        결함 관리로 이동
      </Link>
    </main>
  )
}
