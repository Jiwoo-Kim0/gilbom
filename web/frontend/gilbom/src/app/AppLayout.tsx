import { Info } from 'lucide-react'
import { NavLink, Outlet, useLocation } from 'react-router'
import { NavigationBar } from '../features/navigationbar'
import { useAuthentication } from '../features/auth/authenticationContext'

type PageHeader = {
  path: string
  id: string
  eyebrow: string
  title: string
  description: string
}

const pageHeaders: readonly PageHeader[] = [
  {
    path: '/defects',
    id: 'defects',
    eyebrow: 'DEFECTS',
    title: '실시간 결함 상태 모니터링',
    description: '시스템에서 감지된 최신 결함 목록 및 위치 현황입니다.',
  },
  {
    path: '/robots',
    id: 'fleet',
    eyebrow: 'FLEET',
    title: '로봇 상태 모니터링',
    description: '권한 범위 내 로봇의 최신 위치 및 운영 데이터를 추적합니다.',
  },
  {
    path: '/patrol-areas',
    id: 'patrol-area',
    eyebrow: 'PATROL AREA',
    title: '순찰 구역 관리',
    description: '지도에서 관할 경계를 등록하고 임무에 사용할 활성 구역을 관리합니다.',
  },
  {
    path: '/missions',
    id: 'patrol-mission',
    eyebrow: 'PATROL MISSION',
    title: '순찰 임무 관리',
    description: '순찰할 로봇에게 임무를 부여합니다.',
  },
  {
    path: '/reports',
    id: 'reports',
    eyebrow: 'REPORTS',
    title: '보고서 관리',
    description: '승인 가능한 불변 버전으로 운영 현황과 결함 조치를 관리합니다.',
  },
  {
    path: '/my',
    id: 'account',
    eyebrow: 'ACCOUNT',
    title: '마이페이지',
    description: '프로필을 확인하고 비밀번호와 현재 세션을 안전하게 관리합니다.',
  },
  {
    path: '/admin',
    id: 'administration',
    eyebrow: 'ADMINISTRATION',
    title: '관리자 설정',
    description: '계정 승인, 사용자 역할, 역할 권한, MFA 초기화를 서버 정책에 따라 관리합니다.',
  },
  {
    path: '/audit-logs',
    id: 'security-audit',
    eyebrow: 'SECURITY AUDIT',
    title: '감사 로그',
    description: '주요 보안 이벤트와 관리자 변경 이력을 조회합니다.',
  },
] as const

const defaultPageHeader: PageHeader = {
  path: '/',
  id: 'operations',
  eyebrow: 'GILBOM OPERATIONS',
  title: '길봄 관리자',
  description: '길봄 운영 현황을 관리합니다.',
}

function pageHeaderFor(pathname: string): PageHeader {
  return pageHeaders.find(({ path }) => (
    pathname === path || pathname.startsWith(`${path}/`)
  )) ?? defaultPageHeader
}

export function AppLayout() {
  const location = useLocation()
  const { user } = useAuthentication()
  const pageHeader = pageHeaderFor(location.pathname)
  const displayName = user?.displayName ?? '관리자'
  const tooltipId = `topbar-help-${pageHeader.id}`

  return (
    <>
      <a className="skip-link" href="#main-content">
        본문으로 바로가기
      </a>
      <div className="app-shell">
        <NavigationBar permissions={user?.permissions ?? []} />

        <div className="app-frame">
          <header className="topbar">
            <div className="topbar-heading">
              <p className="eyebrow">{pageHeader.eyebrow}</p>
              <div className="topbar-title-row">
                <h1>{pageHeader.title}</h1>
                <span
                  className="topbar-info"
                  tabIndex={0}
                  aria-label={`${pageHeader.title} 안내`}
                  aria-describedby={tooltipId}
                >
                  <Info size={13} strokeWidth={2.4} aria-hidden="true" />
                  <span
                    id={tooltipId}
                    className="topbar-tooltip"
                    role="tooltip"
                  >
                    {pageHeader.description}
                  </span>
                </span>
              </div>
            </div>
            <NavLink className="account-link" to="/my">
              <span className="account-avatar" aria-hidden="true">
                {displayName.slice(0, 1)}
              </span>
              <span>{displayName}</span>
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
