import { Navigate, createBrowserRouter } from 'react-router'
import { AppLayout } from './AppLayout'
import { RouteErrorPage } from './RouteErrorPage'
import { DefectManagePage } from '../features/defect/DefectManagePage'
import { MyPage } from '../pages/MyPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { ReportsPage } from '../pages/ReportsPage'
import { RobotManagePage } from '../features/robot/RobotManagePage'
import { RoleManagePage } from '../pages/RoleManagePage'

// route 정의를 한곳에 두어 navigation, 권한 가드, loader를 기능 구현 때 일관되게 확장한다.
export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
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
