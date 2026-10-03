import { useState } from 'react'
import { formatKpiRange } from '@/shared/lib/kpiFormat'
import { EmptyState, ErrorState, LoadingBlock } from '@/shared/ui'
import { useEquipmentKpi } from '../api/useEquipmentKpi'
import { KpiPeriodToggle } from './KpiPeriodToggle'
import { KpiTiles } from './KpiTiles'
import { isKpiEmpty } from '../types'
import type { KpiPeriod } from '../types'
import './kpi.css'

interface EquipmentKpiCardProps {
  equipmentId: number
}

/**
 * S-3 설비 KPI 카드 (FR-5.7) — 헤더/정보 패널 아래, 탭 위.
 * GET /equipments/{id}/kpi?period= — 기간 토글 기본 '오늘', 1분마다 갱신(탭 숨김 시 중지).
 */
export function EquipmentKpiCard({ equipmentId }: EquipmentKpiCardProps) {
  const [period, setPeriod] = useState<KpiPeriod>('DAY')
  const { data, loading, error, refetch } = useEquipmentKpi(equipmentId, period)

  return (
    <section className="kpi-card" aria-label="설비 KPI">
      <div className="kpi-card-head">
        <h2 className="kpi-card-title">KPI</h2>
        <KpiPeriodToggle value={period} onChange={setPeriod} />
        {data && (
          <span className="kpi-range mono">
            KST {formatKpiRange(data.periodStart, data.periodEnd)}
          </span>
        )}
      </div>

      {loading && <LoadingBlock label="KPI 를 계산하는 중…" />}
      {!loading && error && <ErrorState error={error} onRetry={refetch} />}
      {!loading && !error && data && (isKpiEmpty(data) ? (
        <EmptyState
          title="집계할 데이터가 없습니다"
          description="이 기간에 집계 대상 상태 이력이 없습니다."
        />
      ) : (
        <KpiTiles values={data} size="compact" />
      ))}
    </section>
  )
}
