import { useCallback } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError, PageResponse } from '@/shared/api'
import { fetchAlarms } from './alarmApi'
import type { Alarm, AlarmListFilter } from '../types'

export interface AlarmListResult {
  /** 래핑 객체의 content 를 꺼낸 배열 — 컴포넌트는 배열만 다룬다 */
  alarms: Alarm[]
  totalElements: number
  totalPages: number
  page: number
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/**
 * GET /alarms 목록 조회 훅.
 * reloadKey 를 바꾸면 재조회한다 (ACK/RESOLVE 직후 또는 SSE 폴백 폴링 갱신용).
 * 재조회 중에도 직전 데이터는 유지되므로(useApiQuery), 화면은 `loading && 비어있음` 일 때만 스피너를 띄운다.
 */
export function useAlarmList(filter: AlarmListFilter = {}, reloadKey = 0): AlarmListResult {
  const { equipmentId, status, severity, from, to, page, size } = filter

  const fetcher = useCallback(
    (signal: AbortSignal): Promise<PageResponse<Alarm>> => {
      void reloadKey
      return fetchAlarms({ equipmentId, status, severity, from, to, page, size }, signal)
    },
    [equipmentId, status, severity, from, to, page, size, reloadKey],
  )

  const { data, loading, error, refetch } = useApiQuery(fetcher)

  return {
    alarms: data?.content ?? [],
    totalElements: data?.totalElements ?? 0,
    totalPages: data?.totalPages ?? 0,
    page: data?.number ?? 0,
    loading,
    error,
    refetch,
  }
}
