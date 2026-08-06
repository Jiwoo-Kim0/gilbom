import {
  BarChart3,
  Bot,
  CircleDot,
  ClipboardList,
  ChevronsLeft,
  ChevronsRight,
  FileClock,
  MapPinned,
  ShieldCheck,
  User,
} from 'lucide-react'
import { NavLink } from 'react-router'
import gilbomLogo from '../assets/gilbom_logo.png'
import {
  ADMIN_SETTINGS_MANAGE,
  AUDIT_LOG_VIEW,
  MISSION_VIEW,
  REPORT_VIEW,
  ROBOT_STATUS_VIEW,
  hasPermission,
  type FrontendPermission,
} from './auth/permissions'

interface NavigationBarProps {
  collapsed: boolean
  onToggle: () => void
  permissions: readonly string[]
}

type NavigationItem = {
  to: string
  label: string
  icon: typeof CircleDot
  requiredPermission: FrontendPermission | null
}

const navigationItems: readonly NavigationItem[] = [
  {
    to: '/defects',
    label: '점자 보도블록 결함 관리',
    icon: CircleDot,
    requiredPermission: null,
  },
  {
    to: '/robots',
    label: '로봇 상태 관리',
    icon: Bot,
    requiredPermission: ROBOT_STATUS_VIEW,
  },
  {
    to: '/patrol-areas',
    label: '순찰 구역 관리',
    icon: MapPinned,
    requiredPermission: MISSION_VIEW,
  },
  {
    to: '/missions',
    label: '순찰 임무 관리',
    icon: ClipboardList,
    requiredPermission: MISSION_VIEW,
  },
  {
    to: '/reports',
    label: '보고서 관리',
    icon: BarChart3,
    requiredPermission: REPORT_VIEW,
  },
  {
    to: '/my',
    label: '마이페이지',
    icon: User,
    requiredPermission: null,
  },
  {
    to: '/admin',
    label: '관리자 패널',
    icon: ShieldCheck,
    requiredPermission: ADMIN_SETTINGS_MANAGE,
  },
  {
    to: '/audit-logs',
    label: '감사 로그',
    icon: FileClock,
    requiredPermission: AUDIT_LOG_VIEW,
  },
] as const

export function NavigationBar({
  collapsed,
  onToggle,
  permissions,
}: NavigationBarProps) {
  const visibleNavigationItems = navigationItems.filter((item) => (
    item.requiredPermission === null
    || hasPermission(permissions, item.requiredPermission)
  ))

  return (
    <div className="nb-sidebar-layer">
      <aside className={`nb-sidebar${collapsed ? ' is-collapsed' : ''}`}>
        <NavLink className="nb-brand" to="/defects" aria-label="길봄 홈">
          <span className="nb-brand-badge" aria-hidden="true">
            <img className="nb-brand-logo" src={gilbomLogo} alt="" />
          </span>
          <span className="nb-brand-text">
            <strong>길봄</strong>
            <small>점자 보도블록 모니터링</small>
          </span>
        </NavLink>

        <nav className="nb-nav" id="primary-navigation" aria-label="주요 메뉴">
          {visibleNavigationItems.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              aria-label={label}
              title={collapsed ? label : undefined}
              className={({ isActive }) => `nb-nav-item${isActive ? ' nb-nav-item-active' : ''}`}
            >
              <span className="nb-nav-icon" aria-hidden="true">
                <Icon size={16} />
              </span>
              <span className="nb-nav-label">{label}</span>
            </NavLink>
          ))}
        </nav>
      </aside>

      <button
        className="nb-sidebar-toggle"
        type="button"
        aria-controls="primary-navigation"
        aria-expanded={!collapsed}
        aria-label={collapsed ? '네비게이션 펼치기' : '네비게이션 접기'}
        title={collapsed ? '네비게이션 펼치기' : '네비게이션 접기'}
        onClick={onToggle}
      >
        {collapsed ? (
          <ChevronsRight size={18} aria-hidden="true" />
        ) : (
          <ChevronsLeft size={18} aria-hidden="true" />
        )}
      </button>
    </div>
  )
}
