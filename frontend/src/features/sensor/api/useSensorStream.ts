import { useMemo, useRef } from 'react'
import { useSse } from '@/shared/api'
import type { SseState } from '@/shared/api'
import type { AlarmEventPayload } from '@/features/alarm'
import type { EquipmentStatusEventPayload, SensorEventPayload } from '../types'

/**
 * FabWatch 실시간 스트림 구독 (docs/06 §3 `GET /stream/sensors`).
 *
 * 하나의 SSE 연결로 3종 이벤트를 받는다:
 *  - `sensor` : 센서 측정값 (2초 주기)
 *  - `alarm`  : 신규 알람
 *  - `status` : 설비 상태 변경
 *
 * equipmentId 를 넘기면 해당 설비만, 생략(null)하면 전체 라인을 구독한다(메인 대시보드).
 * 알람 페이로드 타입은 features/alarm 의 계약을 그대로 쓴다(타입 전용 import — 런타임 결합 없음).
 * ※ alarm 이벤트는 REST 응답과 shape 이 달라 호출부가 alarmFromEvent() 로 변환해서 쓴다.
 */
export interface SensorStreamHandlers {
  onSensor?: (payload: SensorEventPayload) => void
  onAlarm?: (payload: AlarmEventPayload) => void
  onStatus?: (payload: EquipmentStatusEventPayload) => void
  /** SSE 실패 시 3초마다 호출되는 폴백 로더 (전체 화면 데이터를 다시 읽는다) */
  onPoll?: (signal: AbortSignal) => Promise<void> | void
}

export function useSensorStream(
  equipmentId: number | null,
  handlers: SensorStreamHandlers,
  enabled = true,
): SseState {
  // 핸들러는 매 렌더 새 함수로 넘어와도 재연결이 나지 않도록 ref 로 고정한다.
  const handlersRef = useRef(handlers)
  handlersRef.current = handlers

  const events = useMemo(
    () => ({
      sensor: (data: unknown) => handlersRef.current.onSensor?.(data as SensorEventPayload),
      alarm: (data: unknown) => handlersRef.current.onAlarm?.(data as AlarmEventPayload),
      status: (data: unknown) =>
        handlersRef.current.onStatus?.(data as EquipmentStatusEventPayload),
    }),
    [],
  )

  return useSse({
    path: '/stream/sensors',
    params: { equipmentId },
    events,
    enabled,
    onPoll: (signal) => handlersRef.current.onPoll?.(signal),
  })
}
