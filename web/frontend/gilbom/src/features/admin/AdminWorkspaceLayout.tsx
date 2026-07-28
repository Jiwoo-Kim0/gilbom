import { NavLink, Outlet } from 'react-router'

const adminTabs = [
  { to: '/admin/approvals', label: '가입 승인' },
  { to: '/admin/roles', label: '역할·권한' },
  { to: '/admin/mfa-reset', label: 'MFA 초기화' },
] as const

export function AdminWorkspaceLayout() {
  return (
    <section className="page-stack" aria-labelledby="admin-page-title">
      <div className="page-heading">
        <div>
          <p className="eyebrow">ADMINISTRATION</p>
          <h2 id="admin-page-title">관리자 설정</h2>
          <p>계정 승인, 역할 권한, MFA 긴급 초기화를 서버 정책에 따라 관리합니다.</p>
        </div>
      </div>

      <nav className="admin-tabs" aria-label="관리자 설정 메뉴">
        {adminTabs.map((tab) => (
          <NavLink
            className={({ isActive }) => (
              `admin-tab${isActive ? ' admin-tab-active' : ''}`
            )}
            key={tab.to}
            to={tab.to}
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>

      <Outlet />
    </section>
  )
}
