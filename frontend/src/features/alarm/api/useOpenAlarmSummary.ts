import { useCallback, useState } from 'react'
import type { ApiError } from '@/shared/api'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import { fetchOpenAlarmSummary } from './alarmApi'
import type { OpenAlarmSummary } from '../types'

/** 미해결 알람 집계 갱신 주기 (SSE alarm 이벤트가 오면 refetch 로 즉시 갱신되므로 안전망 용도) */
export const OPEN_ALARM_SUMMARY_REFRESH_MS = 30_000

export interface OpenAlarmSummaryResult {
  /** 조회 전/실패 시 null — 호출부는 0 으로 폴백하면 안 된다 */
  summary: OpenAlarmSummary | null
  loading: boolean
  error: ApiError | null
  /** 알람 이벤트·처리 직후 즉시 재집계 */
  reload: () => void
}

/**
 * 전체 설비의 미해결(OPEN+ACK) 알람 집계 훅 — 30초 주기 갱신, 탭이 숨겨지면 중지.
 * 설비 목록(표/카드)의 '미해결 알람' 열, 대시보드 요약·정렬이 함께 쓴다.
 */
export function useOpenAlarmSummary(refreshMs: number = OPEN_ALARM_SUMMARY_REFRESH_MS): OpenAlarmSummaryResult {
  // reloadKey 를 fetcher 의존성에 넣어 즉시 재조회를 트리거한다 (useApiQuery 는 fetcher 변경 시 재조회)
  const [reloadKey, setReloadKey] = useState(0)
  const fetcher = useCallback(
    (signal: AbortSignal): Promise<OpenAlarmSummary> => {
      void reloadKey
      return fetchOpenAlarmSummary(signal)
    },
    [reloadKey],
  )
  const { data, loading, error } = useApiQuery(fetcher, { refreshMs })
  const reload = useCallback(() => setReloadKey((key) => key + 1), [])
  return { summary: data, loading, error, reload }
}
