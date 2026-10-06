import { useCallback } from 'react'
import type { ApiError } from '@/shared/api'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import { fetchThresholdLogs } from './sensorApi'
import type { ThresholdLog } from '../types'

/** 한 번에 보여 줄 최근 변경 이력 건수 */
export const THRESHOLD_LOG_SIZE = 20

export interface ThresholdLogsResult {
  logs: ThresholdLog[]
  totalElements: number
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET .../thresholds/logs 훅 — enabled=false 면 조회하지 않는다(접힌 이력 패널) */
export function useThresholdLogs(
  equipmentId: number,
  sensorId: number,
  enabled: boolean,
): ThresholdLogsResult {
  const fetcher = useCallback(
    (signal: AbortSignal) => fetchThresholdLogs(equipmentId, sensorId, THRESHOLD_LOG_SIZE, signal),
    [equipmentId, sensorId],
  )
  const { data, loading, error, refetch } = useApiQuery(fetcher, { enabled })
  return {
    logs: data?.content ?? [],
    totalElements: data?.totalElements ?? 0,
    loading,
    error,
    refetch,
  }
}
