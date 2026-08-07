import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import { LoadingBlock } from '@/shared/ui'
import './layouts/layout.css'

/** 로그인이 필요한 라우트 가드 — 세션 복구 중에는 대기, 미인증이면 S-0 으로 */
export function ProtectedRoute() {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'loading') {
    return (
      <div className="app-booting">
        <LoadingBlock label="세션 확인 중…" />
      </div>
    )
  }

  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }

  return <Outlet />
}
