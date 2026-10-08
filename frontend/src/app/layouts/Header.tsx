import { Link } from 'react-router-dom'
import { useAuth } from '@/app/providers/useAuth'
import { ROLE_LABEL } from '@/features/auth'
import { useLineTree } from '@/features/equipment'
import { ShiftIndicator } from '@/shared/ui'
import './layout.css'

interface HeaderProps {
  /** 선택된 라인 ID (전체는 null) */
  lineId: number | null
  onLineChange: (lineId: number | null) => void
}

/** 헤더: 로고 | 라인선택 | 교대(D/N) | 사용자 (docs/04 §3 S-1) */
export function Header({ lineId, onLineChange }: HeaderProps) {
  const { user, logout } = useAuth()
  const { lines } = useLineTree()

  return (
    <header className="app-header">
      <Link to="/" className="app-logo">
        <span className="logo-mark">◉</span>
        FabWatch
        <span className="logo-sub">설비관리</span>
      </Link>

      <select
        className="line-select"
        aria-label="라인 선택"
        value={lineId ?? ''}
        onChange={(event) => onLineChange(event.target.value ? Number(event.target.value) : null)}
      >
        <option value="">전체 라인</option>
        {lines.map((line) => (
          <option key={line.id} value={line.id}>
            {line.name}
          </option>
        ))}
      </select>

      <div className="header-spacer" />

      <ShiftIndicator />

      {user && (
        <div className="header-user" title={`${user.name} (${ROLE_LABEL[user.role] ?? user.role})`}>
          <span className="user-name">{user.name}</span>
          <span className="user-role">{ROLE_LABEL[user.role] ?? user.role}</span>
        </div>
      )}

      <button type="button" className="logout-btn" onClick={() => void logout()} aria-label="로그아웃" title="로그아웃">
        <span className="logout-text">로그아웃</span>
        <span className="logout-icon" aria-hidden="true">
          나가기
        </span>
      </button>
    </header>
  )
}
