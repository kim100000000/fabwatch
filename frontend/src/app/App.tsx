import { RouterProvider, createBrowserRouter } from 'react-router-dom'
import { AuthProvider } from './providers'
import { AppRoutes } from './router'

/**
 * 데이터 라우터 — 저장 안 한 변경이 있을 때 앱 내 이동을 막는 useBlocker 가 데이터 라우터에서만 동작한다.
 * 기존 <Routes> 트리는 그대로 두고 splat 라우트 하나로 감싼다 (하위 <Routes> 는 정상 동작).
 */
const router = createBrowserRouter([
  {
    path: '*',
    element: (
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    ),
  },
])

/** 전역 프로바이더 + 라우터 조립 */
export function App() {
  return <RouterProvider router={router} />
}
