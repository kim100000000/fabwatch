import { useCallback } from 'react'
import { fetchLineKpi } from './equipmentApi'
import { useKpiQuery } from './useKpiQuery'
import type { KpiQueryResult } from './useKpiQuery'
import type { KpiPeriod, LineKpi } from '../types'

/** 대시보드 KPI 갱신 주기 (1분) — 대시보드 SSE/폴링과 독립된 별도 쿼리 */
const KPI_REFRESH_MS = 60_000

/** GET /equipments/kpi — 라인 전체(summary) + 설비별 KPI. lineId 가 null 이면 전체 라인 */
export function useLineKpi(period: KpiPeriod, lineId: number | null): KpiQueryResult<LineKpi> {
  const fetcher = useCallback(
    (signal: AbortSignal) => fetchLineKpi({ period, lineId }, signal),
    [period, lineId],
  )
  return useKpiQuery(fetcher, { refreshMs: KPI_REFRESH_MS })
}
