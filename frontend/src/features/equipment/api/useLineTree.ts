import { useCallback } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError } from '@/shared/api'
import { fetchLines } from './equipmentApi'
import type { LineTree } from '../types'

export interface LineTreeResult {
  lines: LineTree[]
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET /lines — 라인+공정+설비 트리 (헤더 라인 선택 등) */
export function useLineTree(): LineTreeResult {
  const fetcher = useCallback((signal: AbortSignal): Promise<LineTree[]> => fetchLines(signal), [])
  const { data, loading, error, refetch } = useApiQuery(fetcher)

  return { lines: data ?? [], loading, error, refetch }
}
