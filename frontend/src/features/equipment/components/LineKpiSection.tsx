import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { formatAvailability, formatKpiRange, formatMtbf, formatMttr } from '@/shared/lib/kpiFormat'
import { statusLabel } from '@/shared/lib/equipmentStatus'
import { EmptyState, ErrorState, LoadingBlock } from '@/shared/ui'
import { useLineKpi } from '../api/useLineKpi'
import { KpiPeriodToggle } from './KpiPeriodToggle'
import { KpiTiles } from './KpiTiles'
import { isKpiEmpty } from '../types'
import type { EquipmentKpiRow, KpiPeriod } from '../types'
import './kpi.css'

interface LineKpiSectionProps {
  /** 헤더에서 선택한 라인 (null = 전체 라인) */
  lineId: number | null
}

/**
 * S-1 대시보드 라인 KPI 영역 (FR-5.7).
 * GET /equipments/kpi?period=&lineId= — 기간 토글은 위쪽 4타일과 하단 '설비별 KPI' 표가 공유한다.
 * 대시보드의 SSE/폴링과 무관한 별도 쿼리(1분 갱신, 탭 숨김 시 중지).
 */
export function LineKpiSection({ lineId }: LineKpiSectionProps) {
  const [period, setPeriod] = useState<KpiPeriod>('DAY')
  const { data, loading, error, refetch } = useLineKpi(period, lineId)

  return (
    <section className="kpi-card" aria-label="라인 KPI">
      <div className="kpi-card-head">
        <h2 className="kpi-card-title">라인 KPI</h2>
        <KpiPeriodToggle value={period} onChange={setPeriod} />
        {data && (
          <span className="kpi-range mono">
            KST {formatKpiRange(data.periodStart, data.periodEnd)}
          </span>
        )}
      </div>

      {loading && <LoadingBlock label="KPI 를 계산하는 중…" />}
      {!loading && error && <ErrorState error={error} onRetry={refetch} />}
      {!loading && !error && data && (
        <>
          {isKpiEmpty(data.summary) ? (
            <EmptyState
              title="집계할 데이터가 없습니다"
              description="이 기간에 집계 대상 상태 이력이 없습니다."
            />
          ) : (
            <KpiTiles values={data.summary} size="large" />
          )}
          <EquipmentKpiTable rows={data.equipments} />
        </>
      )}
    </section>
  )
}

/** 설비별 KPI 접이식 표 — 가동률 낮은 순 정렬 선택 가능 (가동률 null 은 맨 아래) */
function EquipmentKpiTable({ rows }: { rows: EquipmentKpiRow[] }) {
  const [open, setOpen] = useState(false)
  const [lowFirst, setLowFirst] = useState(false)

  const sorted = useMemo(() => {
    if (!lowFirst) return rows
    return [...rows].sort((a, b) => {
      if (a.availability === null && b.availability === null) return 0
      if (a.availability === null) return 1
      if (b.availability === null) return -1
      return a.availability - b.availability
    })
  }, [rows, lowFirst])

  return (
    <div className="kpi-equipment">
      <div className="kpi-equipment-head">
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          aria-expanded={open}
          aria-controls="kpi-equipment-table"
          onClick={() => setOpen((value) => !value)}
        >
          {open ? '▾' : '▸'} 설비별 KPI ({rows.length})
        </button>
        {open && (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            aria-pressed={lowFirst}
            onClick={() => setLowFirst((value) => !value)}
          >
            가동률 낮은 순
          </button>
        )}
      </div>

      {open && (
        <div id="kpi-equipment-table" className="table-scroll">
          {rows.length === 0 ? (
            <EmptyState title="설비가 없습니다" />
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>설비</th>
                  <th>가동률</th>
                  <th>MTBF</th>
                  <th>MTTR</th>
                  <th>{statusLabel('DOWN')} 횟수</th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((row) => (
                  <tr key={row.equipmentId}>
                    <td>
                      <Link to={`/equipment/${row.equipmentId}`} className="kpi-equipment-link">
                        <span className="mono">{row.equipmentCode}</span> {row.equipmentName}
                      </Link>
                    </td>
                    <td className="mono">{formatAvailability(row.availability)}</td>
                    <td className="mono">{formatMtbf(row.mtbfHours)}</td>
                    <td className="mono">{formatMttr(row.mttrMin)}</td>
                    <td className="mono">{row.downCount}회</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  )
}
