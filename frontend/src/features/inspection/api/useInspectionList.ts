import { useCallback } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError, PageResponse } from '@/shared/api'
import { fetchInspections } from './inspectionApi'
import type { Inspection, InspectionListFilter } from '../types'

export interface InspectionListResult {
  /** 래핑 객체의 content 를 꺼낸 배열 */
  inspections: Inspection[]
  totalElements: number
  totalPages: number
  page: number
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET /inspections 목록 훅. reloadKey 를 바꾸면 재조회한다 (승인·수정 직후). */
export function useInspectionList(
  filter: InspectionListFilter = {},
  reloadKey = 0,
): InspectionListResult {
  const { equipmentId, type, shift, workerId, hasNg, from, to, page, size } = filter

  const fetcher = useCallback(
    (signal: AbortSignal): Promise<PageResponse<Inspection>> => {
      void reloadKey
      return fetchInspections({ equipmentId, type, shift, workerId, hasNg, from, to, page, size }, signal)
    },
    [equipmentId, type, shift, workerId, hasNg, from, to, page, size, reloadKey],
  )

  const { data, loading, error, refetch } = useApiQuery(fetcher)

  return {
    inspections: data?.content ?? [],
    totalElements: data?.totalElements ?? 0,
    totalPages: data?.totalPages ?? 0,
    page: data?.number ?? 0,
    loading,
    error,
    refetch,
  }
}
