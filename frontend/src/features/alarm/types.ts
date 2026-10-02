/**
 * 알람 도메인 타입.
 * 백엔드 실제 DTO(`alarm/dto/AlarmResponse.java`, `common/event/AlarmRaisedEvent.java`)에 맞춘다.
 *
 * ★ 주의: REST 응답(AlarmResponse)과 SSE `alarm` 이벤트(AlarmRaisedEvent)는 **shape 이 다르다**.
 *   - REST : id, ack/resolve 정보 포함, sensorType 없음
 *   - SSE  : alarmId(!), sensorType 포함, ack/resolve 정보 없음
 *   그래서 SSE 페이로드는 alarmFromEvent() 로 Alarm 으로 변환해서 쓴다.
 */

import type { SensorType } from '@/features/equipment'

/** alarms.severity — MAJOR 는 PM 지연 등 시스템 규칙 알람용 (docs/03 F-4.3) */
export type AlarmSeverity = 'WARNING' | 'MAJOR' | 'CRITICAL'

/** alarms.status — OPEN → ACK → RESOLVED (ACK 없이 RESOLVED 불가, docs/03 F-5.3) */
export type AlarmStatus = 'OPEN' | 'ACK' | 'RESOLVED'

/** alarms.alarm_type */
export type AlarmType = 'SENSOR_THRESHOLD' | 'PM_OVERDUE' | 'MANUAL'

/** GET /alarms — 목록 항목 (AlarmResponse) */
export interface Alarm {
  id: number
  equipmentId: number
  equipmentCode?: string | null
  /** NULL = 시스템 알람(PM 지연 등) */
  sensorId?: number | null
  /** REST 응답에는 없고 SSE 이벤트에만 실린다 (표시용) */
  sensorType?: SensorType | null
  alarmType: AlarmType
  severity: AlarmSeverity
  status: AlarmStatus
  /** 발생 당시 값 / 기준값 */
  triggerValue?: number | null
  thresholdValue?: number | null
  message: string
  /** UTC ISO — 화면 표시는 KST 변환 */
  occurredAt: string
  ackBy?: number | null
  ackByName?: string | null
  ackAt?: string | null
  resolvedBy?: number | null
  resolvedByName?: string | null
  resolvedAt?: string | null
  resolveNote?: string | null
}

/**
 * SSE `event: alarm` 페이로드 (AlarmRaisedEvent).
 * id 가 아니라 **alarmId** 이고 ack/resolve 정보가 없다 — 신규 발생 알림이라 항상 status=OPEN.
 */
export interface AlarmEventPayload {
  alarmId: number
  equipmentId: number
  equipmentCode?: string | null
  sensorId?: number | null
  sensorType?: SensorType | null
  alarmType: AlarmType
  severity: AlarmSeverity
  status: AlarmStatus
  message: string
  triggerValue?: number | null
  thresholdValue?: number | null
  occurredAt: string
}

/** SSE 페이로드를 목록에서 그대로 쓸 수 있는 Alarm 으로 변환 (alarmId → id) */
export function alarmFromEvent(payload: AlarmEventPayload): Alarm {
  return {
    id: payload.alarmId,
    equipmentId: payload.equipmentId,
    equipmentCode: payload.equipmentCode,
    sensorId: payload.sensorId,
    sensorType: payload.sensorType,
    alarmType: payload.alarmType,
    severity: payload.severity,
    status: payload.status,
    triggerValue: payload.triggerValue,
    thresholdValue: payload.thresholdValue,
    message: payload.message,
    occurredAt: payload.occurredAt,
  }
}

/** GET /alarms 쿼리 필터 (docs/06 §6) */
export interface AlarmListFilter {
  equipmentId?: number | null
  status?: AlarmStatus | null
  severity?: AlarmSeverity | null
  /** UTC ISO */
  from?: string | null
  to?: string | null
  page?: number
  size?: number
}

/** PATCH /alarms/{id}/resolve 요청 — resolveNote 필수 (400 VALIDATION_ERROR) */
export interface AlarmResolveRequest {
  resolveNote: string
}

/** POST /alarms/manual 요청 — 수동 고장 보고 */
export interface ManualAlarmRequest {
  equipmentId: number
  severity: AlarmSeverity
  message: string
}

/**
 * 알람 센터 화면 필터 상태.
 * 기간은 date input 값('YYYY-MM-DD', KST 기준)으로 들고 있다가 전송 직전 UTC ISO 로 바꾼다.
 */
export interface AlarmFilterValue {
  equipmentId: number | null
  status: AlarmStatus | null
  severity: AlarmSeverity | null
  /** 'YYYY-MM-DD' (KST) */
  fromDate: string
  toDate: string
}

export const EMPTY_ALARM_FILTER: AlarmFilterValue = {
  equipmentId: null,
  status: null,
  severity: null,
  fromDate: '',
  toDate: '',
}

export const ALARM_SEVERITIES: AlarmSeverity[] = ['WARNING', 'MAJOR', 'CRITICAL']
export const ALARM_STATUSES: AlarmStatus[] = ['OPEN', 'ACK', 'RESOLVED']

/** 화면 표기용 라벨 (API 값은 그대로 유지) */
export const ALARM_STATUS_LABEL: Record<AlarmStatus, string> = {
  OPEN: '발생',
  ACK: '확인',
  RESOLVED: '해제',
}

export const ALARM_TYPE_LABEL: Record<AlarmType, string> = {
  SENSOR_THRESHOLD: '센서 임계치',
  PM_OVERDUE: 'PM 지연',
  MANUAL: '수동 보고',
}

/** 미조치(=OPEN/ACK) 알람인지 — 카드 배지·필터 공통 판정 */
export function isUnresolved(alarm: Alarm): boolean {
  return alarm.status !== 'RESOLVED'
}

/** OPEN 우선 정렬 (동일 상태면 발생 시각 최신순) — 서버 기본 정렬과 동일한 규칙을 화면에서도 유지 */
const STATUS_ORDER: Record<AlarmStatus, number> = { OPEN: 0, ACK: 1, RESOLVED: 2 }

export function compareAlarms(a: Alarm, b: Alarm): number {
  const byStatus = STATUS_ORDER[a.status] - STATUS_ORDER[b.status]
  if (byStatus !== 0) return byStatus
  return new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime()
}

/**
 * 해제 사유가 'BM 점검 이력 #123 …' 으로 시작하면 연계된 점검 이력 id 를 돌려준다.
 * BM 점검 이력 저장 시 서버가 연계 알람을 이 형식의 사유로 RESOLVED 처리한다 (F-3).
 */
export function linkedInspectionId(resolveNote: string | null | undefined): number | null {
  const match = resolveNote?.match(/^BM 점검 이력 #(\d+)/)
  return match ? Number(match[1]) : null
}
