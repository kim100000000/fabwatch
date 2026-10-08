import { apiClient } from '@/shared/api'
import type { PageResponse } from '@/shared/api'
import { compareAlarms } from '../types'
import type {
  Alarm,
  AlarmListFilter,
  AlarmResolveRequest,
  AlarmSeverity,
  AlarmStatus,
  ManualAlarmRequest,
  OpenAlarmSummary,
} from '../types'

/**
 * GET /alarms — 알람 목록 (docs/06 §6)
 * 기본 OPEN 우선 정렬은 서버가 한다. 응답은 목록 공통 래핑.
 */
export async function fetchAlarms(
  filter: Omit<AlarmListFilter, 'status'> & { status?: AlarmStatus | null },
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

/**
 * 미해결(OPEN+ACK) 알람 목록 — 서버 status 가 단일 값이라 두 번 조회해 합친다.
 * 서버 정렬이 OPEN → ACK 순이므로 각각 상위 size 건만 가져와 합치면 상위 size 건이 정확히 나온다.
 * totalElements 는 두 합계, 페이지는 항상 0(더 보기는 size 확장으로 처리).
 */
export async function fetchUnresolvedAlarms(
  filter: Omit<AlarmListFilter, 'status'>,
  signal?: AbortSignal,
): Promise<PageResponse<Alarm>> {
  const size = Math.min(filter.size ?? 20, 100)
  const [open, ack] = await Promise.all([
    fetchAlarms({ ...filter, status: 'OPEN', page: 0, size }, signal),
    fetchAlarms({ ...filter, status: 'ACK', page: 0, size }, signal),
  ])
  const content = [...open.content, ...ack.content].sort(compareAlarms).slice(0, size)
  const totalElements = open.totalElements + ack.totalElements
  return { content, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / size)), number: 0 }
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

/**
 * 설비의 미해결(OPEN + ACK) 알람 건수 — totalElements 만 쓰므로 size=1 로 두 번 조회해 합친다.
 * 알람 목록 API 의 status 는 단일 값이라 상태별로 나눠 병렬 조회한다 (설비 상세 헤더용).
 */
export async function fetchUnresolvedAlarmCount(
  equipmentId: number,
  signal?: AbortSignal,
): Promise<number> {
  const [open, ack] = await Promise.all([
    fetchAlarms({ equipmentId, status: 'OPEN', size: 1 }, signal),
    fetchAlarms({ equipmentId, status: 'ACK', size: 1 }, signal),
  ])
  return open.totalElements + ack.totalElements
}

/** 서버 최대 페이지 크기 (application.yml max-page-size) */
const MAX_PAGE_SIZE = 100
/** 집계용 최대 조회 페이지 수 — 상태당 최대 2,000건. 넘으면 화면에서 집계가 잘렸음을 알 수 있도록 truncated 로 표시 */
const MAX_SUMMARY_PAGES = 20

const SEVERITY_RANK: Record<AlarmSeverity, number> = { WARNING: 0, MAJOR: 1, CRITICAL: 2 }

/**
 * 전체 미해결(OPEN + ACK) 알람을 설비별로 집계한다.
 * 서버가 설비 목록 응답에 openAlarmCount 를 주지 않으므로(EquipmentSummaryResponse) 알람 목록 API 를
 * 상태별(OPEN, ACK)로 size=100 페이징 조회해 합친다. 대시보드 정렬용으로 설비별 최고 심각도도 함께 구한다.
 */
export async function fetchOpenAlarmSummary(signal?: AbortSignal): Promise<OpenAlarmSummary> {
  const byEquipment: OpenAlarmSummary['byEquipment'] = {}
  let total = 0
  let truncated = false

  for (const status of ['OPEN', 'ACK'] as const) {
    let page = 0
    let totalPages = 1
    while (page < totalPages) {
      if (page >= MAX_SUMMARY_PAGES) {
        truncated = true
        break
      }
      const response = await fetchAlarms({ status, page, size: MAX_PAGE_SIZE }, signal)
      totalPages = response.totalPages
      response.content.forEach((alarm) => {
        total += 1
        const current = byEquipment[alarm.equipmentId]
        if (!current) {
          byEquipment[alarm.equipmentId] = { count: 1, maxSeverity: alarm.severity }
        } else {
          current.count += 1
          if (SEVERITY_RANK[alarm.severity] > SEVERITY_RANK[current.maxSeverity]) {
            current.maxSeverity = alarm.severity
          }
        }
      })
      page += 1
    }
  }

  return { total, byEquipment, truncated }
}
