import { useCallback } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError, PageResponse } from '@/shared/api'
import { fetchAiReports } from './aiReportApi'
import type { AiReportListFilter, AiReportSummary } from '../types'

export interface AiReportListResult {
  /** 래핑 객체의 content 를 꺼낸 배열 */
  reports: AiReportSummary[]
  totalElements: number
  totalPages: number
  page: number
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET /ai-reports 목록 훅 */
export function useAiReportList(filter: AiReportListFilter = {}): AiReportListResult {
  const { equipmentId, status, alarmId, inspectionId, page, size } = filter

  const fetcher = useCallback(
    (signal: AbortSignal): Promise<PageResponse<AiReportSummary>> =>
      fetchAiReports({ equipmentId, status, alarmId, inspectionId, page, size }, signal),
    [equipmentId, status, alarmId, inspectionId, page, size],
  )

  const { data, loading, error, refetch } = useApiQuery(fetcher)

  return {
    reports: data?.content ?? [],
    totalElements: data?.totalElements ?? 0,
    totalPages: data?.totalPages ?? 0,
    page: data?.number ?? 0,
    loading,
    error,
    refetch,
  }
}
