import { useState } from 'react'
import { Outlet } from 'react-router-dom'
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

  return (
    <div className="app-shell">
      <Header lineId={lineId} onLineChange={setLineId} />
      <Sidebar />
      <main className="app-main">
        <Outlet context={{ lineId } satisfies AppOutletContext} />
      </main>
    </div>
  )
}
