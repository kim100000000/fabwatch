import { KPI_PERIODS, KPI_PERIOD_LABEL } from '../types'
import type { KpiPeriod } from '../types'
import './kpi.css'

interface KpiPeriodToggleProps {
  value: KpiPeriod
  onChange: (period: KpiPeriod) => void
}

/** KPI 집계 기간 토글 (오늘/이번 주/이번 달) — role=group + aria-pressed */
export function KpiPeriodToggle({ value, onChange }: KpiPeriodToggleProps) {
  return (
    <div className="kpi-period-toggle" role="group" aria-label="KPI 집계 기간">
      {KPI_PERIODS.map((period) => (
        <button
          key={period}
          type="button"
          aria-pressed={value === period}
          className={value === period ? 'active' : undefined}
          onClick={() => onChange(period)}
        >
          {KPI_PERIOD_LABEL[period]}
        </button>
      ))}
    </div>
  )
}
