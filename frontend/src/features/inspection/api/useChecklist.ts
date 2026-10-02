import { useCallback, useMemo } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError } from '@/shared/api'
import { fetchChecklist } from './inspectionApi'
import type { ChecklistItem } from '../types'

export interface ChecklistResult {
  /** 활성 항목만, seq 순 */
  items: ChecklistItem[]
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET /equipments/{id}/checklist — equipmentId 가 null 이면 호출하지 않는다 */
export function useChecklist(equipmentId: number | null): ChecklistResult {
  const fetcher = useCallback(
    (signal: AbortSignal): Promise<ChecklistItem[]> => fetchChecklist(equipmentId!, signal),
    [equipmentId],
  )
  const { data, loading, error, refetch } = useApiQuery(fetcher, { enabled: equipmentId !== null })

  const items = useMemo(
    () => (data ?? []).filter((item) => item.active).sort((a, b) => a.seq - b.seq),
    [data],
  )
  return { items, loading, error, refetch }
}
