import './ui.css'

/** 설비 상태 (docs/05 equipments.status) */
export type EquipmentStatus = 'RUN' | 'IDLE' | 'DOWN' | 'PM'

const STATUS_LABEL: Record<EquipmentStatus, string> = {
  RUN: 'RUN',
  IDLE: 'IDLE',
  DOWN: 'DOWN',
  PM: 'PM',
}

interface StatusBadgeProps {
  status: EquipmentStatus
}

/** 설비 상태 4색 배지 — 색은 전역 토큰(--status-*) 고정 */
export function StatusBadge({ status }: StatusBadgeProps) {
  return (
    <span className="status-badge" data-status={status}>
      {STATUS_LABEL[status] ?? status}
    </span>
  )
}
