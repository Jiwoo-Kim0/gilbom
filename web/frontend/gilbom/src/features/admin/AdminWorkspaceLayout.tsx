import { NavLink, Outlet } from 'react-router'

const adminTabs = [
  { to: '/admin/approvals', label: '가입 승인' },
  { to: '/admin/users', label: '사용자 역할' },
  { to: '/admin/roles', label: '역할·권한' },
  { to: '/admin/mfa-reset', label: 'MFA 초기화' },
] as const

export function AdminWorkspaceLayout() {
  return (
    <section className="page-stack" aria-label="관리자 설정">
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
