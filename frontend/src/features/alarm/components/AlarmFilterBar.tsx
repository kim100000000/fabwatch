import type { EquipmentSummary } from '@/features/equipment'
import {
  ALARM_SEVERITIES,
  ALARM_SEVERITY_LABEL,
  ALARM_STATUSES,
  ALARM_STATUS_LABEL,
  DEFAULT_ALARM_FILTER,
} from '../types'
import type { AlarmFilterValue, AlarmSeverity, AlarmStatusFilter } from '../types'
import './alarm.css'

interface AlarmFilterBarProps {
  value: AlarmFilterValue
  onChange: (value: AlarmFilterValue) => void
  equipments: EquipmentSummary[]
}

/** 알람 센터 필터 (설비 / 상태 / 심각도 / 기간) — docs/06 §6 GET /alarms 필터와 1:1 */
export function AlarmFilterBar({ value, onChange, equipments }: AlarmFilterBarProps) {
  const update = (patch: Partial<AlarmFilterValue>) => onChange({ ...value, ...patch })

  return (
    <div className="alarm-filter">
      <div className="field">
        <label htmlFor="alarm-filter-equipment">설비</label>
        <select
          id="alarm-filter-equipment"
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
        <label htmlFor="alarm-filter-status">상태</label>
        <select
          id="alarm-filter-status"
          value={value.status ?? 'ALL'}
          onChange={(event) =>
            update({ status: event.target.value === 'ALL' ? null : (event.target.value as AlarmStatusFilter) })
          }
        >
          <option value="UNRESOLVED">미해결 (발생+확인)</option>
          <option value="ALL">전체</option>
          {ALARM_STATUSES.map((status) => (
            <option key={status} value={status}>
              {ALARM_STATUS_LABEL[status]} ({status})
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="alarm-filter-severity">심각도</label>
        <select
          id="alarm-filter-severity"
          value={value.severity ?? ''}
          onChange={(event) =>
            update({ severity: event.target.value ? (event.target.value as AlarmSeverity) : null })
          }
        >
          <option value="">전체</option>
          {ALARM_SEVERITIES.map((severity) => (
            <option key={severity} value={severity}>
              {ALARM_SEVERITY_LABEL[severity]} ({severity})
            </option>
          ))}
        </select>
      </div>

      <div className="field">
        <label htmlFor="alarm-filter-from">시작일 (KST)</label>
        <input
          id="alarm-filter-from"
          type="date"
          value={value.fromDate}
          max={value.toDate || undefined}
          onChange={(event) => update({ fromDate: event.target.value })}
        />
      </div>

      <div className="field">
        <label htmlFor="alarm-filter-to">종료일 (KST)</label>
        <input
          id="alarm-filter-to"
          type="date"
          value={value.toDate}
          min={value.fromDate || undefined}
          onChange={(event) => update({ toDate: event.target.value })}
        />
      </div>

      <div className="filter-actions">
        <button type="button" className="btn btn-ghost" onClick={() => onChange(DEFAULT_ALARM_FILTER)}>
          초기화
        </button>
      </div>
    </div>
  )
}
