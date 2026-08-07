import type { ReactNode } from 'react'
import './ui.css'

interface EmptyStateProps {
  title: string
  description?: string
  icon?: string
  action?: ReactNode
}

/** 데이터 없음 — 일러스트 없이 텍스트+아이콘으로 담백하게 (docs/04 §4) */
export function EmptyState({ title, description, icon = '▤', action }: EmptyStateProps) {
  return (
    <div className="empty-state">
      <span className="empty-icon" aria-hidden="true">
        {icon}
      </span>
      <span className="empty-title">{title}</span>
      {description && <span>{description}</span>}
      {action}
    </div>
  )
}
