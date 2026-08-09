import { useCallback, useEffect, useRef, useState } from 'react'
import axios from 'axios'
import { toApiError } from '@/shared/api'
import type { ApiError } from '@/shared/api'
import { fetchLatestSensorData } from './sensorApi'
import { compareSensorType } from '../types'
import type { SensorEventPayload, SensorLatest } from '../types'

export interface LatestSensorsResult {
  /** equipmentId → 센서별 최신값 (센서 종류 순서 고정) */
  sensorsByEquipment: Record<number, SensorLatest[]>
  loading: boolean
  error: ApiError | null
  /**
   * 전체 재조회 — SSE 폴백 폴링에서 그대로 호출한다.
   * 방금 읽은 값을 그대로 돌려주므로, 폴백 중에도 호출부가 차트 포인트를 이어붙일 수 있다.
   */
  reload: (signal?: AbortSignal) => Promise<Record<number, SensorLatest[]>>
  /** SSE `sensor` 이벤트를 카드/차트 값에 반영 */
  applySensorEvent: (payload: SensorEventPayload) => void
}

/**
 * 여러 설비의 센서 최신값을 한 번에 들고 있는 훅.
 * S-1 대시보드(설비 N대)와 S-3 센서 탭(설비 1대)이 같은 훅을 쓴다.
 *
 * SSE `sensor` 이벤트는 equipmentId 를 싣고 오지만, 만약 빠져도 동작하도록
 * 조회 결과로 sensorId → equipmentId 색인을 만들어 이벤트를 라우팅한다.
 */
export function useLatestSensors(equipmentIds: number[]): LatestSensorsResult {
  const idsKey = equipmentIds.join(',')
  const [sensorsByEquipment, setSensorsByEquipment] = useState<Record<number, SensorLatest[]>>({})
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  // sensorId → equipmentId 색인 (SSE 이벤트 라우팅용)
  const ownerRef = useRef<Map<number, number>>(new Map())

  const reload = useCallback(
    async (signal?: AbortSignal): Promise<Record<number, SensorLatest[]>> => {
      const ids = idsKey ? idsKey.split(',').map(Number) : []
      if (ids.length === 0) {
        setSensorsByEquipment({})
        ownerRef.current = new Map()
        return {}
      }

      const results = await Promise.all(
        ids.map(async (equipmentId) => {
          const sensors = await fetchLatestSensorData(equipmentId, signal)
          return [equipmentId, [...sensors].sort((a, b) => compareSensorType(a.sensorType, b.sensorType))] as const
        }),
      )

      const next: Record<number, SensorLatest[]> = {}
      const owner = new Map<number, number>()
      results.forEach(([equipmentId, sensors]) => {
        next[equipmentId] = sensors
        sensors.forEach((sensor) => owner.set(sensor.sensorId, equipmentId))
      })
      ownerRef.current = owner
      setSensorsByEquipment(next)
      return next
    },
    [idsKey],
  )

  useEffect(() => {
    if (!idsKey) {
      setSensorsByEquipment({})
      setLoading(false)
      return
    }

    const controller = new AbortController()
    let alive = true
    setLoading(true)
    setError(null)

    reload(controller.signal)
      .then(() => {
        if (alive) setLoading(false)
      })
      .catch((cause: unknown) => {
        if (!alive || axios.isCancel(cause)) return
        setError(toApiError(cause))
        setLoading(false)
      })

    return () => {
      alive = false
      controller.abort()
    }
  }, [idsKey, reload])

  const applySensorEvent = useCallback((payload: SensorEventPayload) => {
    const equipmentId = payload.equipmentId ?? ownerRef.current.get(payload.sensorId)
    if (equipmentId === undefined) return // 아직 목록에 없는 설비의 센서 — 무시

    setSensorsByEquipment((previous) => {
      const sensors = previous[equipmentId]
      if (!sensors) return previous
      const index = sensors.findIndex((sensor) => sensor.sensorId === payload.sensorId)
      if (index < 0) return previous

      const updated = [...sensors]
      updated[index] = {
        ...sensors[index],
        value: payload.value,
        measuredAt: payload.measuredAt,
        level: payload.level,
      }
      return { ...previous, [equipmentId]: updated }
    })
  }, [])

  return { sensorsByEquipment, loading, error, reload, applySensorEvent }
}
