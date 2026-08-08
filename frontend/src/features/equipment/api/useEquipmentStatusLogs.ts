import { useCallback } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError, PageResponse } from '@/shared/api'
import { fetchEquipmentStatusLogs } from './equipmentApi'
import type { EquipmentStatusLog } from '../types'

export interface EquipmentStatusLogsResult {
  /** 래핑 객체의 content 를 꺼낸 배열 — 컴포넌트는 배열만 다룬다 */
  logs: EquipmentStatusLog[]
  totalElements: number
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/**
 * GET /equipments/{id}/status-logs 조회 훅.
 * reloadKey 를 바꾸면 재조회한다 — 상태 변경 직후 이력 탭을 갱신하는 용도.
 */
export function useEquipmentStatusLogs(
  equipmentId: number | null,
  reloadKey = 0,
): EquipmentStatusLogsResult {
  const fetcher = useCallback(
    (signal: AbortSignal): Promise<PageResponse<EquipmentStatusLog>> => {
      void reloadKey
      return fetchEquipmentStatusLogs(equipmentId!, signal)
    },
    [equipmentId, reloadKey],
  )

  const { data, loading, error, refetch } = useApiQuery(fetcher, {
    enabled: equipmentId !== null && !Number.isNaN(equipmentId),
  })

  return {
    logs: data?.content ?? [],
    totalElements: data?.totalElements ?? 0,
    loading,
    error,
    refetch,
  }
}
