import { StatusBadge } from '@/shared/ui'
import { statusLabel } from '@/shared/lib/equipmentStatus'
import { AlarmCount } from './AlarmCount'
import type { EquipmentSummary, OpenAlarmCounts } from '../types'
import './equipment.css'

interface EquipmentCardGridProps {
  equipments: EquipmentSummary[]
  alarmCounts: OpenAlarmCounts
  onSelect: (equipmentId: number) => void
}

/** 설비 카드 그리드 (S-2 카드 뷰) — 상태 좌측 보더 4px 색상 (docs/04 §3) */
export function EquipmentCardGrid({ equipments, alarmCounts, onSelect }: EquipmentCardGridProps) {
  return (
    <div className="equipment-grid">
      {equipments.map((equipment) => (
        <button
          key={equipment.id}
          type="button"
          className="equipment-card"
          data-status={equipment.status}
          aria-label={`${equipment.code} ${equipment.name}, 상태 ${statusLabel(equipment.status)}, 상세 보기`}
          onClick={() => onSelect(equipment.id)}
        >
          <div className="card-top">
            <span className="card-code">{equipment.code}</span>
            <StatusBadge status={equipment.status} />
          </div>
          <span className="card-name">{equipment.name}</span>
          <div className="card-meta">
            <span>{equipment.processName ?? equipment.lineName ?? '-'}</span>
            <AlarmCount counts={alarmCounts} equipmentId={equipment.id} withIcon />
          </div>
        </button>
      ))}
    </div>
  )
}
