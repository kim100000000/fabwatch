import { useCallback } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError } from '@/shared/api'
import { fetchInspectionDetail } from './inspectionApi'
import type { InspectionDetail } from '../types'

export interface InspectionDetailResult {
  inspection: InspectionDetail | null
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET /inspections/{id} 상세 훅 (체크리스트 결과 포함) */
export function useInspectionDetail(inspectionId: number | null): InspectionDetailResult {
  const fetcher = useCallback(
    (signal: AbortSignal): Promise<InspectionDetail> => fetchInspectionDetail(inspectionId!, signal),
    [inspectionId],
  )
  const { data, loading, error, refetch } = useApiQuery(fetcher, { enabled: inspectionId !== null })
  return { inspection: data, loading, error, refetch }
}
