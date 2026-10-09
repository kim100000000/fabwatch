import { useCallback, useEffect, useRef, useState } from 'react'
import axios from 'axios'
import { toApiError } from '@/shared/api'
import type { ApiError } from '@/shared/api'
import { fetchLatestSensorData } from './sensorApi'
import { noteReading } from '../receipt'
import type { ReceiptBook } from '../receipt'
import { compareSensorType } from '../types'
import type { SensorEventPayload, SensorLatest, SensorThresholds } from '../types'

export interface LatestSensorsResult {
  /** equipmentId → 센서별 최신값 (센서 종류 순서 고정) */
  sensorsByEquipment: Record<number, SensorLatest[]>
  /**
   * equipmentId → 브라우저가 그 설비의 새 센서값을 마지막으로 받은 시각(epoch ms).
   * 끊김(stale) 판정용 — 서버 measuredAt 과 브라우저 시계를 비교하지 않으므로 시계 오차에 영향받지 않는다.
   */
  lastReceivedAt: Record<number, number>
  loading: boolean
  error: ApiError | null
  /**
   * 전체 재조회 — SSE 폴백 폴링에서 그대로 호출한다.
   * 방금 읽은 값을 그대로 돌려주므로, 폴백 중에도 호출부가 차트 포인트를 이어붙일 수 있다.
   */
  reload: (signal?: AbortSignal) => Promise<Record<number, SensorLatest[]>>
  /** SSE `sensor` 이벤트를 카드/차트 값에 반영 */
  applySensorEvent: (payload: SensorEventPayload) => void
  /**
   * 임계치 수정 직후 해당 센서의 4값을 서버 응답 그대로 교체 (null 도 그대로 — '미설정'으로 바뀐 경계가
   * 이전 값으로 되돌아가지 않게 한다). 다음 SSE 이벤트는 임계치를 건드리지 않고 값만 갱신한다.
   */
  applyThresholds: (equipmentId: number, sensorId: number, thresholds: SensorThresholds) => void
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
  // 센서별로 마지막에 본 measuredAt — 새 값을 받았는지 판정해 수신 시각을 기록한다
  const receiptRef = useRef<ReceiptBook>(new Map())
  const [lastReceivedAt, setLastReceivedAt] = useState<Record<number, number>>({})

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
      const receivedNow = Date.now()
      const touched: Record<number, number> = {}
      results.forEach(([equipmentId, sensors]) => {
        next[equipmentId] = sensors
        sensors.forEach((sensor) => {
          owner.set(sensor.sensorId, equipmentId)
          if (noteReading(receiptRef.current, sensor.sensorId, sensor.measuredAt)) touched[equipmentId] = receivedNow
        })
      })
      ownerRef.current = owner
      setSensorsByEquipment(next)
      if (Object.keys(touched).length > 0) setLastReceivedAt((previous) => ({ ...previous, ...touched }))
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

    if (noteReading(receiptRef.current, payload.sensorId, payload.measuredAt)) {
      const receivedNow = Date.now()
      setLastReceivedAt((previous) => ({ ...previous, [equipmentId]: receivedNow }))
    }

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

  const applyThresholds = useCallback(
    (equipmentId: number, sensorId: number, thresholds: SensorThresholds) => {
      setSensorsByEquipment((previous) => {
        const sensors = previous[equipmentId]
        if (!sensors) return previous
        return {
          ...previous,
          [equipmentId]: sensors.map((sensor) =>
            sensor.sensorId === sensorId
              ? {
                  ...sensor,
                  warnLow: thresholds.warnLow ?? null,
                  warnHigh: thresholds.warnHigh ?? null,
                  critLow: thresholds.critLow ?? null,
                  critHigh: thresholds.critHigh ?? null,
                }
              : sensor,
          ),
        }
      })
    },
    [],
  )

  return { sensorsByEquipment, lastReceivedAt, loading, error, reload, applySensorEvent, applyThresholds }
}
