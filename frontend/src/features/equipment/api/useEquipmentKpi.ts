import { useCallback } from 'react'
import { fetchEquipmentKpi } from './equipmentApi'
import { useKpiQuery } from './useKpiQuery'
import type { KpiQueryResult } from './useKpiQuery'
import type { EquipmentKpi, KpiPeriod } from '../types'

/** 설비 상세 KPI 갱신 주기 (1분) */
const KPI_REFRESH_MS = 60_000

/** GET /equipments/{id}/kpi — 기간 변경 시 이전 값을 비우고 다시 불러온다 */
export function useEquipmentKpi(equipmentId: number, period: KpiPeriod): KpiQueryResult<EquipmentKpi> {
  const fetcher = useCallback(
    (signal: AbortSignal) => fetchEquipmentKpi(equipmentId, period, signal),
    [equipmentId, period],
  )
  return useKpiQuery(fetcher, { refreshMs: KPI_REFRESH_MS })
}
