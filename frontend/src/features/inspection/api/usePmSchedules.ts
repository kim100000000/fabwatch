import { useCallback, useEffect, useMemo } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError } from '@/shared/api'
import { fetchPmSchedules } from './inspectionApi'
import type { PmSchedule } from '../types'

export interface PmSchedulesResult {
  schedules: PmSchedule[]
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET /pm-schedules 훅 (단순 배열 응답) */
export function usePmSchedules(
  query: { overdueOnly?: boolean; equipmentId?: number | null; enabled?: boolean } = {},
): PmSchedulesResult {
  const { overdueOnly, equipmentId, enabled } = query
  const fetcher = useCallback(
    (signal: AbortSignal): Promise<PmSchedule[]> => fetchPmSchedules({ overdueOnly, equipmentId }, signal),
    [overdueOnly, equipmentId],
  )
  const { data, loading, error, refetch } = useApiQuery(fetcher, { enabled })
  return { schedules: data ?? [], loading, error, refetch }
}

/** 대시보드 PM OVERDUE 배지 갱신 주기 (1분) */
const OVERDUE_REFRESH_MS = 60_000

/**
 * PM 지연(OVERDUE) 설비 id 집합 — 대시보드 카드 배지용.
 * 대시보드 SSE/폴링과 별개의 가벼운 쿼리이며 1분마다 갱신한다. 실패해도 배지만 안 뜰 뿐 화면은 유지된다.
 */
export function useOverdueEquipmentIds(): ReadonlySet<number> {
  const { schedules, refetch } = usePmSchedules({ overdueOnly: true })

  useEffect(() => {
    const timer = window.setInterval(refetch, OVERDUE_REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [refetch])

  return useMemo(() => new Set(schedules.filter((s) => s.overdue).map((s) => s.equipmentId)), [schedules])
}
