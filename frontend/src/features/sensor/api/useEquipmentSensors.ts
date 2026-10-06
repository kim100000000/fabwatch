import { useCallback } from 'react'
import type { ApiError } from '@/shared/api'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import { fetchEquipmentSensors } from './sensorApi'
import { compareSensorType } from '../types'
import type { SensorDefinition } from '../types'

export interface EquipmentSensorsResult {
  /** 센서 종류 순서 고정. 조회 전/실패 시 null */
  sensors: SensorDefinition[] | null
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/** GET /equipments/{id}/sensors 훅 — 설비 상세 헤더(센서 종류/개수)와 임계치 편집(정상 기준값)이 쓴다 */
export function useEquipmentSensors(
  equipmentId: number | null,
  options?: { enabled?: boolean },
): EquipmentSensorsResult {
  const fetcher = useCallback(
    async (signal: AbortSignal): Promise<SensorDefinition[]> => {
      const list = await fetchEquipmentSensors(equipmentId!, signal)
      return [...list].sort((a, b) => compareSensorType(a.sensorType, b.sensorType))
    },
    [equipmentId],
  )
  const { data, loading, error, refetch } = useApiQuery(fetcher, {
    enabled: (options?.enabled ?? true) && equipmentId !== null && !Number.isNaN(equipmentId),
  })
  return { sensors: data, loading, error, refetch }
}
