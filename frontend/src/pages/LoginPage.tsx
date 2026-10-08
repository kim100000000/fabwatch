import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import { LoginForm } from '@/features/auth'
import { LoadingBlock } from '@/shared/ui'
import { usePageTitle } from '@/shared/hooks/usePageTitle'
import '@/app/layouts/layout.css'

/** S-0 로그인 (/login) */
export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { status, sessionNotice, clearSessionNotice } = useAuth()
  usePageTitle('로그인')

  // 세션 복구 등으로 이미 인증된 상태면 원래 가려던 화면(기본 S-1)으로 보낸다
  const redirectTo = (location.state as { from?: string } | null)?.from ?? '/'

  useEffect(() => {
    if (status === 'authenticated') {
      navigate(redirectTo, { replace: true })
    }
  }, [status, navigate, redirectTo])

  // 세션 복구 중이거나 이미 인증된 상태(리다이렉트 직전)에는 로그인 폼을 그리지 않는다 — 깜빡임 방지
  if (status === 'loading' || status === 'authenticated') {
    return (
      <div className="app-booting">
        <LoadingBlock label="세션 확인 중…" />
      </div>
    )
  }

  return (
    <div className="login-shell">
      <div className="login-card">
        <div className="login-brand">
          <h1>
            <span>◉</span> FabWatch
          </h1>
          <p>설비 점검 이력 · 실시간 모니터링 · AI 고장 리포트</p>
        </div>

        {sessionNotice && (
          <p className="login-notice" role="status">
            {sessionNotice === 'expired'
              ? '세션이 만료되어 다시 로그인해 주세요.'
              : '서버에 연결할 수 없어 로그인 상태를 복구하지 못했습니다. 잠시 후 새로고침하거나 다시 로그인해 주세요.'}
          </p>
        )}

        <LoginForm
          onSuccess={() => {
            clearSessionNotice()
            navigate(redirectTo, { replace: true })
          }}
        />
      </div>
    </div>
  )
}
