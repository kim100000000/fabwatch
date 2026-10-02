import type { EquipmentSummary } from '@/features/equipment'
import { AI_REPORT_STATUSES, AI_REPORT_STATUS_LABEL, EMPTY_REPORT_FILTER } from '../types'
import type { AiReportStatus, ReportFilterValue } from '../types'
import './report.css'

interface ReportFilterBarProps {
  value: ReportFilterValue
  onChange: (value: ReportFilterValue) => void
  equipments: EquipmentSummary[]
}

/** S-7 목록 필터 — 설비 / 상태 */
export function ReportFilterBar({ value, onChange, equipments }: ReportFilterBarProps) {
  return (
    <div className="report-filter">
      <div className="field">
        <label htmlFor="report-filter-equipment">설비</label>
        <select
          id="report-filter-equipment"
          value={value.equipmentId ?? ''}
          onChange={(event) =>
            onChange({ ...value, equipmentId: event.target.value ? Number(event.target.value) : null })
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
        <label htmlFor="report-filter-status">상태</label>
        <select
          id="report-filter-status"
          value={value.status ?? ''}
          onChange={(event) =>
            onChange({ ...value, status: event.target.value ? (event.target.value as AiReportStatus) : null })
          }
        >
          <option value="">전체</option>
          {AI_REPORT_STATUSES.map((status) => (
            <option key={status} value={status}>
              {AI_REPORT_STATUS_LABEL[status]}
            </option>
          ))}
        </select>
      </div>

      <div className="report-filter-actions">
        <button type="button" className="btn btn-ghost" onClick={() => onChange(EMPTY_REPORT_FILTER)}>
          초기화
        </button>
      </div>
    </div>
  )
}
