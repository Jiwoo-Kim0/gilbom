import { Navigate, createBrowserRouter } from 'react-router'
import { AppLayout } from './AppLayout'
import { RouteErrorPage } from './RouteErrorPage'
import { AuthLayout } from '../features/auth/AuthLayout'
import {
  RequireAuthenticated,
  RequireAuthenticationState,
  RequirePermission,
} from '../features/auth/AuthRouteGuard'
import {
  ADMIN_SETTINGS_MANAGE,
  AUDIT_LOG_VIEW,
  MISSION_VIEW,
  REPORT_VIEW,
  ROBOT_STATUS_VIEW,
} from '../features/auth/permissions'
import { AdminWorkspaceLayout } from '../features/admin/AdminWorkspaceLayout'
import { AdminApprovalsPage } from '../features/admin/AdminApprovalsPage'
import { AdminMfaResetPage } from '../features/admin/AdminMfaResetPage'
import { AdminUserRolesPage } from '../features/admin/AdminUserRolesPage'
import { AuditLogPage } from '../features/audit/AuditLogPage'
import { InitialPasswordChangePage } from '../features/auth/InitialPasswordChangePage'
import { LoginPage } from '../features/auth/LoginPage'
import { MfaEnrollmentPage } from '../features/auth/MfaEnrollmentPage'
import { MfaVerificationPage } from '../features/auth/MfaVerificationPage'
import { RegistrationPage } from '../features/auth/RegistrationPage'
import { DefectManagePage } from '../features/defect/DefectManagePage'
import { MyPage } from '../features/platform/MyPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { ReportsPage } from '../features/platform/ReportsPage'
import { RobotManagePage } from '../features/robot/RobotManagePage'
import { RoleManagePage } from '../features/platform/RoleManagePage'
import { MissionManagePage } from '../features/mission/MissionManagePage'

export const router = createBrowserRouter([
  {
    element: <AuthLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      {
        path: '/login',
        element: (
          <RequireAuthenticationState allowed={['ANONYMOUS']}>
            <LoginPage />
          </RequireAuthenticationState>
        ),
      },
      {
        path: '/register',
        element: (
          <RequireAuthenticationState allowed={['ANONYMOUS']}>
            <RegistrationPage />
          </RequireAuthenticationState>
        ),
      },
      {
        path: '/auth/password-change',
        element: (
          <RequireAuthenticationState allowed={['PASSWORD_CHANGE_REQUIRED']}>
            <InitialPasswordChangePage />
          </RequireAuthenticationState>
        ),
      },
      {
        path: '/auth/mfa-enrollment',
        element: (
          <RequireAuthenticationState
            allowed={['MFA_ENROLLMENT_REQUIRED', 'AUTHENTICATED']}
          >
            <MfaEnrollmentPage />
          </RequireAuthenticationState>
        ),
      },
      {
        path: '/auth/mfa',
        element: (
          <RequireAuthenticationState allowed={['MFA_REQUIRED']}>
            <MfaVerificationPage />
          </RequireAuthenticationState>
        ),
      },
    ],
  },
  {
    path: '/',
    element: (
      <RequireAuthenticated>
        <AppLayout />
      </RequireAuthenticated>
    ),
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <Navigate replace to="/defects" /> },
      { path: 'defects', element: <DefectManagePage /> },
      {
        path: 'robots',
        element: (
          <RequirePermission permission={ROBOT_STATUS_VIEW}>
            <RobotManagePage />
          </RequirePermission>
        ),
      },
      {
        path: 'missions',
        element: (
          <RequirePermission permission={MISSION_VIEW}>
            <MissionManagePage />
          </RequirePermission>
        ),
      },
      {
        path: 'reports',
        element: (
          <RequirePermission permission={REPORT_VIEW}>
            <ReportsPage />
          </RequirePermission>
        ),
      },
      {
        path: 'admin',
        element: (
          <RequirePermission permission={ADMIN_SETTINGS_MANAGE}>
            <AdminWorkspaceLayout />
          </RequirePermission>
        ),
        children: [
          { index: true, element: <Navigate replace to="/admin/approvals" /> },
          { path: 'approvals', element: <AdminApprovalsPage /> },
          { path: 'users', element: <AdminUserRolesPage /> },
          { path: 'roles', element: <RoleManagePage /> },
          { path: 'mfa-reset', element: <AdminMfaResetPage /> },
        ],
      },
      {
        path: 'audit-logs',
        element: (
          <RequirePermission permission={AUDIT_LOG_VIEW}>
            <AuditLogPage />
          </RequirePermission>
        ),
      },
      { path: 'my', element: <MyPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
