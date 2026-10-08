import { useCallback, useMemo, useRef } from 'react'
import type { SseState } from '@/shared/api'
import type { AlarmEventPayload } from '@/features/alarm'
import type { EquipmentStatusEventPayload, SensorEventPayload } from '../types'
import { useSensorStream } from './useSensorStream'

type PollHandler = (signal: AbortSignal) => Promise<void> | void

/**
 * 한 설비의 실시간 스트림을 **하나의 SSE 연결**로 열고, 센서 이벤트·폴백 폴링은 구독자(센서 탭)에게 나눠 주는 피드.
 * 상세 화면이 탭과 무관하게 status/alarm 이벤트를 받아야 하므로 연결은 페이지 수준에서 열고,
 * 센서 탭은 이 피드를 구독만 한다(서버가 사용자당 SSE 연결 수를 제한하므로 연결을 중복해서 열지 않는다).
 */
export interface SensorFeed {
  /** 연결 상태 (SSE / 폴링 폴백) + 마지막 이벤트 수신 시각 */
  state: SseState
  /** 센서 이벤트 구독 — 해제 함수를 돌려준다 */
  subscribeSensor: (handler: (payload: SensorEventPayload) => void) => () => void
  /** 폴백 폴링(SSE 불가 시 3초 주기) 구독 */
  subscribePoll: (handler: PollHandler) => () => void
}

interface SensorFeedHandlers {
  onStatus?: (payload: EquipmentStatusEventPayload) => void
  onAlarm?: (payload: AlarmEventPayload) => void
  /** 폴백 폴링 때 페이지 수준에서 하고 싶은 갱신 (설비 상태 재조회 등) */
  onPoll?: PollHandler
}

export function useSensorFeed(equipmentId: number | null, handlers: SensorFeedHandlers = {}): SensorFeed {
  const sensorSubscribers = useRef(new Set<(payload: SensorEventPayload) => void>())
  const pollSubscribers = useRef(new Set<PollHandler>())
  const handlersRef = useRef(handlers)
  handlersRef.current = handlers

  const state = useSensorStream(equipmentId, {
    onSensor: (payload) => sensorSubscribers.current.forEach((handler) => handler(payload)),
    onStatus: (payload) => handlersRef.current.onStatus?.(payload),
    onAlarm: (payload) => handlersRef.current.onAlarm?.(payload),
    onPoll: async (signal) => {
      await handlersRef.current.onPoll?.(signal)
      await Promise.all([...pollSubscribers.current].map((handler) => handler(signal)))
    },
  })

  const subscribeSensor = useCallback((handler: (payload: SensorEventPayload) => void) => {
    sensorSubscribers.current.add(handler)
    return () => {
      sensorSubscribers.current.delete(handler)
    }
  }, [])

  const subscribePoll = useCallback((handler: PollHandler) => {
    pollSubscribers.current.add(handler)
    return () => {
      pollSubscribers.current.delete(handler)
    }
  }, [])

  return useMemo(() => ({ state, subscribeSensor, subscribePoll }), [state, subscribeSensor, subscribePoll])
}
