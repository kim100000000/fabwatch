import { useCallback } from 'react'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import type { ApiError } from '@/shared/api'
import type { SensorType } from '@/features/equipment'
import { minutesAgoIso } from '@/shared/lib/datetime'
import { fetchSensorSeries } from './sensorApi'
import { HISTORY_RANGES } from '../types'
import type { HistoryRange, SensorGranularity, SensorSeriesPoint } from '../types'

export interface SensorHistoryResult {
  points: SensorSeriesPoint[]
  /** 서버가 실제로 사용한 데이터 소스 — RAW/1M 전환은 서버 판단을 그대로 표시한다 */
  granularity: SensorGranularity | null
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/**
 * GET /equipments/{id}/sensor-data 이력 조회 훅 (docs/03 F-5.2).
 * 기간(1시간/24시간/7일)만 고르면 RAW/1M 자동 전환은 백엔드가 하고,
 * 프론트는 응답의 granularity 를 그대로 배지에 노출한다(프론트가 추측하지 않는다).
 */
export function useSensorHistory(
  equipmentId: number | null,
  sensorType: SensorType | null,
  range: HistoryRange,
): SensorHistoryResult {
  const enabled = equipmentId !== null && sensorType !== null

  // 센서/기간이 바뀌면 이전 조건의 응답은 쓰지 않는다 — 응답에 요청 키를 실어 현재 키와 같을 때만 사용한다
  const requestKey = `${equipmentId}:${sensorType}:${range}`

  const fetcher = useCallback(
    async (signal: AbortSignal) => {
      const minutes = HISTORY_RANGES.find((item) => item.key === range)?.minutes ?? 60
      const response = await fetchSensorSeries(
        equipmentId!,
        { sensorType: sensorType!, from: minutesAgoIso(minutes), to: new Date().toISOString() },
        signal,
      )
      return { key: requestKey, response }
    },
    [equipmentId, sensorType, range, requestKey],
  )

  const { data: keyed, loading, error, refetch } = useApiQuery(fetcher, { enabled })
  const data = keyed && keyed.key === requestKey ? keyed.response : null

  // sensorType 을 지정해 조회하므로 series 는 보통 1개지만, 방어적으로 타입이 맞는 것을 고른다.
  const series =
    data?.series.find((item) => item.sensorType === sensorType) ?? data?.series[0] ?? null

  return {
    points: series?.points ?? [],
    granularity: data?.granularity ?? null,
    // 현재 조건의 응답이 아직 없으면(전환 직후 포함) 로딩으로 취급한다
    loading: enabled && !error && (loading || data === null),
    error,
    refetch,
  }
}
