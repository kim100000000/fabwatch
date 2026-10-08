import { useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { ErrorBoundary } from '@/shared/ui'
import { Header } from './Header'
import type { AppOutletContext } from './outletContext'
import { Sidebar } from './Sidebar'
import './layout.css'

/**
 * 로그인 후 공통 셸: 헤더 + 사이드바 + 본문(Outlet).
 * 선택된 라인은 여기서 보관하고 Outlet context 로 페이지에 내려준다 (현재 S-1 라인 KPI 가 사용).
 */
export function AppLayout() {
  const [lineId, setLineId] = useState<number | null>(null)
  const location = useLocation()

  return (
    <div className="app-shell">
      <a href="#main-content" className="skip-link">
        본문으로 건너뛰기
      </a>
      <Header lineId={lineId} onLineChange={setLineId} />
      <Sidebar />
      <main className="app-main" id="main-content" tabIndex={-1}>
        {/* 본문에서 난 렌더 예외가 사이드바·헤더까지 날리지 않게 격리 — 라우트가 바뀌면 자동 해제 */}
        <ErrorBoundary resetKey={location.pathname}>
          <Outlet context={{ lineId } satisfies AppOutletContext} />
        </ErrorBoundary>
      </main>
    </div>
  )
}
