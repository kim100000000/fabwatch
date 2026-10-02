import { statusLabel } from '@/shared/lib/equipmentStatus'
import { EQUIPMENT_STATUSES } from '../types'
import type { EquipmentStatus } from '../types'
import './equipment.css'

interface EquipmentStatusFilterProps {
  value: EquipmentStatus | null
  onChange: (status: EquipmentStatus | null) => void
}

/** 설비 상태 필터 칩 — GET /equipments?status= 에 그대로 전달 */
export function EquipmentStatusFilter({ value, onChange }: EquipmentStatusFilterProps) {
  return (
    <div className="status-filter" role="group" aria-label="상태 필터">
      <button
        type="button"
        className={value === null ? 'filter-chip active' : 'filter-chip'}
        onClick={() => onChange(null)}
      >
        전체
      </button>
      {EQUIPMENT_STATUSES.map((status) => (
        <button
          key={status}
          type="button"
          data-status={status}
          className={value === status ? 'filter-chip active' : 'filter-chip'}
          onClick={() => onChange(value === status ? null : status)}
        >
          {statusLabel(status)}
        </button>
      ))}
    </div>
  )
}
