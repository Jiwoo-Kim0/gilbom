import { BarChart3, Bot, CircleDot, ShieldCheck, User } from 'lucide-react'
import { NavLink } from 'react-router'
import gilbomLogo from '../assets/gilbom_logo.png'

export interface NavigationBarUser {
  name: string
  title: string
  role: string
  avatarUrl?: string
}

interface NavigationBarProps {
  user: NavigationBarUser
}

const navigationItems = [
  { to: '/defects', label: '점자 보도블록 결함 관리', icon: CircleDot },
  { to: '/robots', label: '로봇 상태 관리', icon: Bot },
  { to: '/reports', label: '리포트', icon: BarChart3 },
  { to: '/my', label: '마이페이지', icon: User },
  { to: '/admin/roles', label: '관리자 패널', icon: ShieldCheck },
] as const

export function NavigationBar({ user }: NavigationBarProps) {
  const initials = user.name.slice(0, 1)

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
        {navigationItems.map(({ to, label, icon: Icon }) => (
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

      <div className="nb-footer">
        <div className="nb-profile">
          {user.avatarUrl ? (
            <img className="nb-avatar" src={user.avatarUrl} alt="" />
          ) : (
            <span className="nb-avatar nb-avatar-fallback" aria-hidden="true">
              {initials}
            </span>
          )}
          <span className="nb-profile-text">
            <strong>
              {user.name}{user.title ? ` ${user.title}` : ''}
            </strong>
            <small>{user.role}</small>
          </span>
        </div>
      </div>
    </aside>
  )
}
