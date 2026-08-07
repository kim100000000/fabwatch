import { NavLink } from 'react-router-dom'
import './layout.css'

interface NavItem {
  to: string
  label: string
  icon: string
  screen: string
  /** 하위 경로까지 활성 처리할지 (예: /equipment/:id) */
  end?: boolean
}

/**
 * 사이드바 네비게이션 (docs/04 §2 화면 목록).
 * 메뉴 라벨에서만 업무 용어(Maintenance/설비보전) 사용 — 역할·API 필드명은 그대로 유지.
 */
const NAV_ITEMS: NavItem[] = [
  { to: '/', label: '라인 현황', icon: '▤', screen: 'S-1', end: true },
  { to: '/equipment', label: '설비', icon: '⚙', screen: 'S-2' },
  { to: '/inspections', label: '점검 이력', icon: '✓', screen: 'S-4' },
  { to: '/alarms', label: '알람 센터', icon: '⚠', screen: 'S-6' },
  { to: '/reports', label: 'AI 리포트', icon: '✎', screen: 'S-7' },
  { to: '/admin', label: '관리', icon: '⚒', screen: 'S-8' },
]

export function Sidebar() {
  return (
    <nav className="app-sidebar" aria-label="주요 메뉴">
      {NAV_ITEMS.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.end}
          className={({ isActive }) => (isActive ? 'nav-item active' : 'nav-item')}
          title={item.label}
        >
          <span className="nav-icon" aria-hidden="true">
            {item.icon}
          </span>
          <span className="nav-label">{item.label}</span>
          <span className="nav-screen">{item.screen}</span>
        </NavLink>
      ))}
    </nav>
  )
}
