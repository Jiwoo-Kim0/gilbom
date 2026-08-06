import {
  BarChart3,
  Bot,
  CircleDot,
  ClipboardList,
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

export function NavigationBar({ permissions }: NavigationBarProps) {
  const visibleNavigationItems = navigationItems.filter((item) => (
    item.requiredPermission === null
    || hasPermission(permissions, item.requiredPermission)
  ))

  return (
    <aside className="nb-sidebar">
      <NavLink className="nb-brand" to="/defects" aria-label="길봄 홈">
        <span className="nb-brand-badge" aria-hidden="true">
          <img className="nb-brand-logo" src={gilbomLogo} alt="" />
        </span>
        <span className="nb-brand-text">
          <strong>길봄</strong>
          <small>점자 보도블록 모니터링</small>
        </span>
      </NavLink>

      <nav className="nb-nav" aria-label="주요 메뉴">
        {visibleNavigationItems.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) => `nb-nav-item${isActive ? ' nb-nav-item-active' : ''}`}
          >
            <span className="nb-nav-icon" aria-hidden="true">
              <Icon size={16} />
            </span>
            <span>{label}</span>
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
