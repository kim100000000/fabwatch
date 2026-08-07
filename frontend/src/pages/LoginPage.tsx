import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import { LoginForm } from '@/features/auth'
import '@/app/layouts/layout.css'

/** S-0 로그인 (/login) */
export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { status } = useAuth()

  // 세션 복구 등으로 이미 인증된 상태면 원래 가려던 화면(기본 S-1)으로 보낸다
  const redirectTo = (location.state as { from?: string } | null)?.from ?? '/'

  useEffect(() => {
    if (status === 'authenticated') {
      navigate(redirectTo, { replace: true })
    }
  }, [status, navigate, redirectTo])

  return (
    <div className="login-shell">
      <div className="login-card">
        <div className="login-brand">
          <h1>
            <span>◉</span> FabWatch
          </h1>
          <p>설비 점검 이력 · 센서 모니터링 · AI 고장 리포트</p>
        </div>

        <LoginForm onSuccess={() => navigate(redirectTo, { replace: true })} />

        <p className="login-hint">
          시드 계정 (docs/05 §4)
          <br />
          <code>admin@fabwatch.dev</code> / <code>engineer@fabwatch.dev</code> /{' '}
          <code>tech@fabwatch.dev</code>
        </p>
      </div>
    </div>
  )
}
