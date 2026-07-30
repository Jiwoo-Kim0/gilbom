import { NavLink, Outlet, useLocation } from 'react-router'
import { NavigationBar } from '../features/navigationbar'
import { useAuthentication } from '../features/auth/authenticationContext'

const pageTitles: Record<string, string> = {
  '/robots': '로봇 상태 관리',
  '/missions': '순찰 임무 관리',
  '/defects': '점자 보도블록 결함 관리',
  '/reports': '리포트',
  '/admin/approvals': '가입 요청 승인',
  '/admin/users': '사용자 역할 관리',
  '/admin/roles': '역할·권한 관리',
  '/admin/mfa-reset': 'MFA 관리자 초기화',
  '/audit-logs': '감사 로그',
  '/my': '마이페이지',
}

export function AppLayout() {
  const location = useLocation()
  const { user } = useAuthentication()
  const pageTitle = location.pathname.startsWith('/reports')
    ? '리포트'
    : (pageTitles[location.pathname] ?? '길봄 관리자')
  const primaryRole = user?.roles.at(0)?.roleName ?? '권한 미지정'

  return (
    <>
      <a className="skip-link" href="#main-content">
        본문으로 바로가기
      </a>
      <div className="app-shell">
        <NavigationBar
          user={{
            name: user?.displayName ?? '관리자',
            title: '',
            role: primaryRole,
            permissions: user?.permissions ?? [],
          }}
        />

        <div className="app-frame">
          <header className="topbar">
            <div>
              <p className="eyebrow">GILBOM OPERATIONS</p>
              <h1>{pageTitle}</h1>
            </div>
            <NavLink className="account-link" to="/my">
              <span className="account-avatar" aria-hidden="true">
                {user?.displayName.slice(0, 1) ?? 'A'}
              </span>
              <span>내 계정</span>
            </NavLink>
          </header>

          <main className="main-content" id="main-content" tabIndex={-1}>
            {/* 자식 route가 공통 navigation을 반복하지 않고 이 영역만 교체한다. */}
            <Outlet />
          </main>
        </div>
      </div>
    </>
  )
}
