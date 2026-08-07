import { useCallback } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError } from '@/shared/api'
import { fetchEquipmentDetail } from './equipmentApi'
import type { EquipmentDetail } from '../types'

export interface EquipmentDetailResult {
  equipment: EquipmentDetail | null
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET /equipments/{id} 상세 조회 훅 */
export function useEquipmentDetail(equipmentId: number | null): EquipmentDetailResult {
  const fetcher = useCallback(
    (signal: AbortSignal): Promise<EquipmentDetail> => fetchEquipmentDetail(equipmentId!, signal),
    [equipmentId],
  )

  const { data, loading, error, refetch } = useApiQuery(fetcher, {
    enabled: equipmentId !== null && !Number.isNaN(equipmentId),
  })

  return { equipment: data, loading, error, refetch }
}
