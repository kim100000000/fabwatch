import { StatusBadge } from '@/shared/ui'
import type { EquipmentSummary } from '../types'
import './equipment.css'

interface EquipmentCardGridProps {
  equipments: EquipmentSummary[]
  onSelect: (equipmentId: number) => void
}

/** 설비 카드 그리드 (S-2 카드 뷰) — 상태 좌측 보더 4px 색상 (docs/04 §3) */
export function EquipmentCardGrid({ equipments, onSelect }: EquipmentCardGridProps) {
  return (
    <div className="equipment-grid">
      {equipments.map((equipment) => (
        <button
          key={equipment.id}
          type="button"
          className="equipment-card"
          data-status={equipment.status}
          onClick={() => onSelect(equipment.id)}
        >
          <div className="card-top">
            <span className="card-code">{equipment.code}</span>
            <StatusBadge status={equipment.status} />
          </div>
          <span className="card-name">{equipment.name}</span>
          <div className="card-meta">
            <span>{equipment.processName ?? equipment.lineName ?? '-'}</span>
            <span className="alarm-count" data-zero={!equipment.openAlarmCount}>
              ⚠ {equipment.openAlarmCount ?? 0}
            </span>
          </div>
        </button>
      ))}
    </div>
  )
}
