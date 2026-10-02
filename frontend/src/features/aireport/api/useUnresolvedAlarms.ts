import { useCallback } from 'react'
import { fetchAlarms } from '@/features/alarm'
import type { Alarm } from '@/features/alarm'
import type { ApiError } from '@/shared/api'
import { useApiQuery } from '@/shared/hooks/useApiQuery'

/** 상태별 조회 건수 상한 */
export const UNRESOLVED_ALARM_PAGE_SIZE = 50

export interface UnresolvedAlarmsResult {
  /** 미해결(OPEN/ACK) 알람 — 발생 최신순 */
  alarms: Alarm[]
  loading: boolean
  /** 조회 실패 — '알람 없음'과 구분해서 안내해야 한다 */
  error: ApiError | null
  /** 서버에 더 많은 미해결 알람이 있어 일부(최근 50건/상태)만 가져온 경우 */
  truncated: boolean
  refetch: () => void
}

/**
 * 설비의 미해결(OPEN/ACK) 알람 조회 — 'AI 리포트 생성' 버튼 활성 조건과 대상 선택에 쓴다.
 * 알람 목록 API 의 status 는 단일 값이라 OPEN / ACK 를 병렬 조회해 합친다.
 */
export function useUnresolvedAlarms(equipmentId: number, enabled: boolean): UnresolvedAlarmsResult {
  const fetcher = useCallback(
    async (signal: AbortSignal): Promise<{ alarms: Alarm[]; truncated: boolean }> => {
      const [open, ack] = await Promise.all([
        fetchAlarms({ equipmentId, status: 'OPEN', size: UNRESOLVED_ALARM_PAGE_SIZE }, signal),
        fetchAlarms({ equipmentId, status: 'ACK', size: UNRESOLVED_ALARM_PAGE_SIZE }, signal),
      ])
      const merged = [...open.content, ...ack.content].sort(
        (a, b) => new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime(),
      )
      return {
        alarms: merged,
        truncated: open.totalElements > open.content.length || ack.totalElements > ack.content.length,
      }
    },
    [equipmentId],
  )

  const { data, loading, error, refetch } = useApiQuery(fetcher, { enabled })
  return { alarms: data?.alarms ?? [], loading, error, truncated: data?.truncated ?? false, refetch }
}
