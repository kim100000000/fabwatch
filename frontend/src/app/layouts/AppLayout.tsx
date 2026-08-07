import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import { Header } from './Header'
import { Sidebar } from './Sidebar'
import './layout.css'

/**
 * 로그인 후 공통 셸: 헤더 + 사이드바 + 본문(Outlet).
 * 선택된 라인은 여기서 보관한다 — S-1 대시보드/설비 목록 필터 연동은 다음 라운드.
 */
export function AppLayout() {
  const [lineId, setLineId] = useState<number | null>(null)

  return (
    <div className="app-shell">
      <Header lineId={lineId} onLineChange={setLineId} />
      <Sidebar />
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  )
}
