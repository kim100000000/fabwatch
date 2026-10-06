import { useCallback } from 'react'
import type { ApiError } from '@/shared/api'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import { fetchUnresolvedAlarmCount } from './alarmApi'

/** 헤더 미해결 알람 건수 갱신 주기 (알람 탭의 '조치 후 재조회' 방식과 맞춰 SSE 대신 주기 갱신) */
export const UNRESOLVED_COUNT_REFRESH_MS = 30_000

export interface UnresolvedAlarmCountResult {
  /** 조회 전/실패 시 null — 호출부는 0 으로 폴백하면 안 된다(알람이 있는데 '0건' 으로 보이는 모순 방지) */
  count: number | null
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/**
 * 설비의 미해결(OPEN/ACK) 알람 건수 훅 — 30초 주기 갱신, 탭이 숨겨지면 중지.
 * aireport 의 useUnresolvedAlarms(목록 조회, 리포트 대상 선택용)와 달리 건수만 가볍게 읽는다.
 */
export function useUnresolvedAlarmCount(
  equipmentId: number | null,
  refreshMs: number = UNRESOLVED_COUNT_REFRESH_MS,
): UnresolvedAlarmCountResult {
  const fetcher = useCallback(
    (signal: AbortSignal): Promise<number> => fetchUnresolvedAlarmCount(equipmentId!, signal),
    [equipmentId],
  )
  const { data, loading, error, refetch } = useApiQuery(fetcher, {
    enabled: equipmentId !== null && !Number.isNaN(equipmentId),
    refreshMs,
  })
  return { count: data, loading, error, refetch }
}
