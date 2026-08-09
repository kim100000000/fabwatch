import { apiClient } from '@/shared/api'
import type { PageResponse } from '@/shared/api'
import type { Alarm, AlarmListFilter, AlarmResolveRequest, ManualAlarmRequest } from '../types'

/**
 * GET /alarms — 알람 목록 (docs/06 §6)
 * 기본 OPEN 우선 정렬은 서버가 한다. 응답은 목록 공통 래핑.
 */
export async function fetchAlarms(
  filter: AlarmListFilter,
  signal?: AbortSignal,
): Promise<PageResponse<Alarm>> {
  const { data } = await apiClient.get<PageResponse<Alarm>>('/alarms', {
    params: {
      equipmentId: filter.equipmentId ?? undefined,
      status: filter.status ?? undefined,
      severity: filter.severity ?? undefined,
      from: filter.from ?? undefined,
      to: filter.to ?? undefined,
      page: filter.page ?? 0,
      size: filter.size ?? 20,
    },
    signal,
  })
  return data
}

/** PATCH /alarms/{id}/ack — 확인 처리 (본인 기록). 갱신된 알람을 돌려받는다. */
export async function ackAlarm(alarmId: number): Promise<Alarm> {
  const { data } = await apiClient.patch<Alarm>(`/alarms/${alarmId}/ack`)
  return data
}

/**
 * PATCH /alarms/{id}/resolve — 해제 (ACK 상태에서만 가능).
 * OPEN 상태에서 호출하면 400 `ACK_REQUIRED_FIRST`. resolveNote 는 필수.
 */
export async function resolveAlarm(alarmId: number, request: AlarmResolveRequest): Promise<Alarm> {
  const { data } = await apiClient.patch<Alarm>(`/alarms/${alarmId}/resolve`, request)
  return data
}

/** POST /alarms/manual — 수동 고장 보고 (alarmType=MANUAL 로 생성됨) */
export async function createManualAlarm(request: ManualAlarmRequest): Promise<Alarm> {
  const { data } = await apiClient.post<Alarm>('/alarms/manual', request)
  return data
}
