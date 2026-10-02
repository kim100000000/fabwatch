import type { EquipmentSummary } from '@/features/equipment'
import {
  EMPTY_INSPECTION_FILTER,
  INSPECTION_TYPES,
  SHIFT_LABEL,
} from '../types'
import type { InspectionFilterValue, InspectionType, Shift } from '../types'
import './inspection.css'

interface InspectionFilterBarProps {
  value: InspectionFilterValue
  onChange: (value: InspectionFilterValue) => void
  equipments: EquipmentSummary[]
}

/** S-4 필터 — 설비 / 기간 / 유형 / 교대조 / 작업자(내 점검) / NG 여부 (docs/03 3.4) */
export function InspectionFilterBar({ value, onChange, equipments }: InspectionFilterBarProps) {
  const update = (patch: Partial<InspectionFilterValue>) => onChange({ ...value, ...patch })

  return (
    <div className="inspection-filter">
      <div className="field">
        <label htmlFor="insp-filter-equipment">설비</label>
        <select
          id="insp-filter-equipment"
          value={value.equipmentId ?? ''}
          onChange={(event) =>
            update({ equipmentId: event.target.value ? Number(event.target.value) : null })
          }
        >
          <option value="">전체</option>
          {equipments.map((equipment) => (
            <option key={equipment.id} value={equipment.id}>
              {equipment.code} · {equipment.name}
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="insp-filter-from">시작일 (KST)</label>
        <input
          id="insp-filter-from"
          type="date"
          value={value.fromDate}
          max={value.toDate || undefined}
          onChange={(event) => update({ fromDate: event.target.value })}
        />
      </div>

      <div className="field">
        <label htmlFor="insp-filter-to">종료일 (KST)</label>
        <input
          id="insp-filter-to"
          type="date"
          value={value.toDate}
          min={value.fromDate || undefined}
          onChange={(event) => update({ toDate: event.target.value })}
        />
      </div>

      <div className="field">
        <label htmlFor="insp-filter-type">유형</label>
        <select
          id="insp-filter-type"
          value={value.type ?? ''}
          onChange={(event) =>
            update({ type: event.target.value ? (event.target.value as InspectionType) : null })
          }
        >
          <option value="">전체</option>
          {INSPECTION_TYPES.map((type) => (
            <option key={type} value={type}>
              {type}
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="insp-filter-shift">교대조</label>
        <select
          id="insp-filter-shift"
          value={value.shift ?? ''}
          onChange={(event) =>
            update({ shift: event.target.value ? (event.target.value as Shift) : null })
          }
        >
          <option value="">전체</option>
          {(Object.keys(SHIFT_LABEL) as Shift[]).map((shift) => (
            <option key={shift} value={shift}>
              {SHIFT_LABEL[shift]}
            </option>
          ))}
        </select>
      </div>

      <label className="check-inline">
        <input
          type="checkbox"
          checked={value.mineOnly}
          onChange={(event) => update({ mineOnly: event.target.checked })}
        />
        내 점검만
      </label>

      <label className="check-inline">
        <input
          type="checkbox"
          checked={value.hasNg}
          onChange={(event) => update({ hasNg: event.target.checked })}
        />
        NG 포함만
      </label>

      <div className="filter-actions">
        <button type="button" className="btn btn-ghost" onClick={() => onChange(EMPTY_INSPECTION_FILTER)}>
          초기화
        </button>
      </div>
    </div>
  )
}
