import { useCallback } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError, PageResponse } from '@/shared/api'
import { fetchEquipments } from './equipmentApi'
import type { EquipmentListFilter, EquipmentSummary } from '../types'

export interface EquipmentListResult {
  /** 래핑 객체의 content 를 꺼낸 배열 — 컴포넌트는 배열만 다룬다 */
  equipments: EquipmentSummary[]
  totalElements: number
  totalPages: number
  page: number
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET /equipments 목록 조회 훅 */
export function useEquipmentList(filter: EquipmentListFilter = {}): EquipmentListResult {
  const { processId, status, page, size } = filter

  const fetcher = useCallback(
    (signal: AbortSignal): Promise<PageResponse<EquipmentSummary>> =>
      fetchEquipments({ processId, status, page, size }, signal),
    [processId, status, page, size],
  )

  const { data, loading, error, refetch } = useApiQuery(fetcher)

  return {
    equipments: data?.content ?? [],
    totalElements: data?.totalElements ?? 0,
    totalPages: data?.totalPages ?? 0,
    page: data?.number ?? 0,
    loading,
    error,
    refetch,
  }
}
