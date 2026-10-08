import { glossaryOf } from '@/shared/lib/glossary'
import { statusLabel } from '@/shared/lib/equipmentStatus'
import type { EquipmentStatus } from '@/shared/lib/equipmentStatus'
import './ui.css'

export type { EquipmentStatus }

interface StatusBadgeProps {
  status: EquipmentStatus
}

/** 설비 상태 4색 배지 — 색은 전역 토큰(--status-*) 고정, 라벨은 shared/lib/equipmentStatus 단일 출처 */
export function StatusBadge({ status }: StatusBadgeProps) {
  return (
    <span className="status-badge" data-status={status} title={glossaryOf(status)?.description}>
      {statusLabel(status)}
    </span>
  )
}
