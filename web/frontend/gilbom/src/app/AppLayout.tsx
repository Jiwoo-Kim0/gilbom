import { NavLink, Outlet, useLocation } from 'react-router'
import { NavigationBar } from '../features/navigationbar'

const pageTitles: Record<string, string> = {
  '/robots': 'Robot Status',
  '/defects': 'Defect Status',
  '/reports': 'Reports',
  '/admin/roles': 'Admin Panel',
  '/my': 'My Page',
}

// 인증 API 연결 전까지 사용하는 자리표시자 사용자 정보.
const currentUser = {
  name: '강민준',
  title: '차장',
  role: 'System Admin',
}

export function AppLayout() {
  const location = useLocation()
  const pageTitle = pageTitles[location.pathname] ?? '길봄 관리자'

  return (
    <>
      <a className="skip-link" href="#main-content">
        본문으로 바로가기
      </a>
      <div className="app-shell">
        <NavigationBar user={currentUser} />

        <div className="app-frame">
          <header className="topbar">
            <div>
              <p className="eyebrow">GILBOM OPERATIONS</p>
              <h1>{pageTitle}</h1>
            </div>
            <NavLink className="account-link" to="/my">
              <span className="account-avatar" aria-hidden="true">
                AD
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
