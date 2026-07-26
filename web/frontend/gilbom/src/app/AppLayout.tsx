import { NavLink, Outlet, useLocation } from 'react-router'

const navigationItems = [
  { to: '/robots', label: '로봇 관리', shortLabel: 'RO' },
  { to: '/defects', label: '결함 관리', shortLabel: 'DE' },
  { to: '/admin/roles', label: '역할·권한', shortLabel: 'AC' },
  { to: '/my', label: '내 계정', shortLabel: 'MY' },
] as const

const pageTitles: Record<string, string> = {
  '/robots': '로봇 관리',
  '/defects': '결함 관리',
  '/admin/roles': '역할·권한',
  '/my': '내 계정',
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
        <aside className="sidebar">
          <NavLink className="brand" to="/robots" aria-label="길봄 관리자 홈">
            <span className="brand-mark" aria-hidden="true">
              길
            </span>
            <span>
              <strong>길봄</strong>
              <small>관리자 콘솔</small>
            </span>
          </NavLink>

          <nav className="primary-nav" aria-label="주요 메뉴">
            {navigationItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  `nav-item${isActive ? ' nav-item-active' : ''}`
                }
              >
                <span className="nav-icon" aria-hidden="true">
                  {item.shortLabel}
                </span>
                <span>{item.label}</span>
              </NavLink>
            ))}
          </nav>

          <p className="sidebar-note">
            메뉴 노출은 편의 기능이며 실제 권한은 서버에서 검증합니다.
          </p>
        </aside>

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
