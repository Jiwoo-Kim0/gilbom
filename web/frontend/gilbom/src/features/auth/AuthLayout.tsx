import { ShieldCheck } from 'lucide-react'
import { Link, Outlet } from 'react-router'
import gilbomLogo from '../../assets/gilbom_logo.png'

export function AuthLayout() {
  return (
    <div className="auth-shell">
      <a className="skip-link" href="#auth-content">
        인증 양식으로 바로가기
      </a>

      <aside className="auth-intro" aria-label="길봄 서비스 안내">
        <Link className="auth-brand" to="/login" aria-label="길봄 로그인">
          <img src={gilbomLogo} alt="" />
          <span>
            <strong>길봄</strong>
            <small>GILBOM OPERATIONS</small>
          </span>
        </Link>

        <div className="auth-intro-copy">
          <span className="auth-intro-icon" aria-hidden="true">
            <ShieldCheck size={24} />
          </span>
          <p className="eyebrow">SECURE ADMIN ACCESS</p>
          <h1>안전한 운영을 위한<br />관리자 인증</h1>
          <p>
            비밀번호와 MFA를 모두 통과한 세션만 길봄 운영 기능에 접근할 수 있습니다.
          </p>
        </div>

        <p className="auth-storage-note">
          인증정보와 CSRF 토큰은 브라우저 저장소에 보관하지 않습니다.
        </p>
      </aside>

      <main className="auth-main" id="auth-content" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}

