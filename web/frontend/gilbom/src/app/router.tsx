import { Navigate, createBrowserRouter } from 'react-router'
import { AppLayout } from './AppLayout'
import { RouteErrorPage } from './RouteErrorPage'
import { AuthLayout } from '../features/auth/AuthLayout'
import {
  RequireAuthenticated,
  RequireAuthenticationState,
} from '../features/auth/AuthRouteGuard'
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
      { path: 'robots', element: <RobotManagePage /> },
      { path: 'reports', element: <ReportsPage /> },
      { path: 'admin/roles', element: <RoleManagePage /> },
      { path: 'my', element: <MyPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
