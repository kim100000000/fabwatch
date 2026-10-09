import type { AlarmSeverity, AlarmStatus, AlarmType, OpenAlarmEquipmentSummary } from './types'

const SEVERITY_RANK: Record<AlarmSeverity, number> = { WARNING: 0, MAJOR: 1, CRITICAL: 2 }

/** 집계에 필요한 알람의 최소 모양 */
export interface AlarmForSummary {
  equipmentId: number
  severity: AlarmSeverity
  alarmType: AlarmType
}

/**
 * 알람 1건을 설비별 미해결 집계에 누적한다 (byEquipment 를 직접 갱신).
 * status 는 이 알람이 조회된 상태(OPEN/ACK) — 서버가 상태별로 따로 돌려주므로 호출부가 넘긴다.
 * count/openCount/maxSeverity 는 PM_OVERDUE 포함 전체 기준, nonPmMaxSeverity 는 PM 지연을 뺀 최고 심각도.
 */
export function accumulateAlarm(
  byEquipment: Record<number, OpenAlarmEquipmentSummary>,
  alarm: AlarmForSummary,
  status: Extract<AlarmStatus, 'OPEN' | 'ACK'>,
): void {
  const isOpen = status === 'OPEN'
  const isPm = alarm.alarmType === 'PM_OVERDUE'
  const current = byEquipment[alarm.equipmentId]
  if (!current) {
    byEquipment[alarm.equipmentId] = {
      count: 1,
      openCount: isOpen ? 1 : 0,
      maxSeverity: alarm.severity,
      pmOverdueCount: isPm ? 1 : 0,
      pmOverdueOpenCount: isPm && isOpen ? 1 : 0,
      nonPmMaxSeverity: isPm ? null : alarm.severity,
    }
    return
  }
  current.count += 1
  if (isOpen) current.openCount += 1
  if (SEVERITY_RANK[alarm.severity] > SEVERITY_RANK[current.maxSeverity]) {
    current.maxSeverity = alarm.severity
  }
  if (isPm) {
    current.pmOverdueCount += 1
    if (isOpen) current.pmOverdueOpenCount += 1
  } else if (
    current.nonPmMaxSeverity === null ||
    SEVERITY_RANK[alarm.severity] > SEVERITY_RANK[current.nonPmMaxSeverity]
  ) {
    current.nonPmMaxSeverity = alarm.severity
  }
}
