import { describe, expect, it } from 'vitest'
import { accumulateAlarm } from './alarmSummary'
import type { AlarmForSummary } from './alarmSummary'
import type { OpenAlarmEquipmentSummary } from './types'

const pm = (equipmentId = 1, severity: AlarmForSummary['severity'] = 'MAJOR'): AlarmForSummary => ({
  equipmentId,
  severity,
  alarmType: 'PM_OVERDUE',
})
const sensor = (severity: AlarmForSummary['severity'], equipmentId = 1): AlarmForSummary => ({
  equipmentId,
  severity,
  alarmType: 'SENSOR_THRESHOLD',
})

describe('accumulateAlarm (미해결 알람 설비별 집계)', () => {
  it('PM 지연만 있으면 전체 건수는 세되 비PM 심각도는 null', () => {
    const byEquipment: Record<number, OpenAlarmEquipmentSummary> = {}
    accumulateAlarm(byEquipment, pm(), 'OPEN')
    expect(byEquipment[1]).toEqual({
      count: 1,
      openCount: 1,
      maxSeverity: 'MAJOR',
      pmOverdueCount: 1,
      pmOverdueOpenCount: 1,
      nonPmMaxSeverity: null,
    })
  })

  it('PM + 센서 혼합: 전체 최고는 MAJOR, 비PM 최고는 센서 WARNING', () => {
    const byEquipment: Record<number, OpenAlarmEquipmentSummary> = {}
    accumulateAlarm(byEquipment, pm(), 'OPEN')
    accumulateAlarm(byEquipment, sensor('WARNING'), 'OPEN')
    expect(byEquipment[1]).toMatchObject({
      count: 2,
      openCount: 2,
      maxSeverity: 'MAJOR',
      pmOverdueCount: 1,
      nonPmMaxSeverity: 'WARNING',
    })
  })

  it('센서 알람이 먼저 와도 이후 PM 이 비PM 심각도를 바꾸지 않는다', () => {
    const byEquipment: Record<number, OpenAlarmEquipmentSummary> = {}
    accumulateAlarm(byEquipment, sensor('WARNING'), 'OPEN')
    accumulateAlarm(byEquipment, pm(1, 'CRITICAL'), 'ACK')
    expect(byEquipment[1].maxSeverity).toBe('CRITICAL')
    expect(byEquipment[1].nonPmMaxSeverity).toBe('WARNING')
  })

  it('OPEN/ACK 혼합: OPEN 건수만 openCount 에 들어간다', () => {
    const byEquipment: Record<number, OpenAlarmEquipmentSummary> = {}
    accumulateAlarm(byEquipment, sensor('CRITICAL'), 'OPEN')
    accumulateAlarm(byEquipment, sensor('WARNING'), 'ACK')
    accumulateAlarm(byEquipment, sensor('WARNING'), 'ACK')
    expect(byEquipment[1]).toMatchObject({ count: 3, openCount: 1, maxSeverity: 'CRITICAL', nonPmMaxSeverity: 'CRITICAL' })
  })

  it('PM OPEN + 센서 ACK: 비PM 기준 OPEN 은 0 (openCount - pmOverdueOpenCount = 0 → 깜빡임 없음)', () => {
    const byEquipment: Record<number, OpenAlarmEquipmentSummary> = {}
    accumulateAlarm(byEquipment, pm(), 'OPEN')
    accumulateAlarm(byEquipment, sensor('CRITICAL'), 'ACK')
    const entry = byEquipment[1]
    expect(entry.openCount).toBe(1)
    expect(entry.pmOverdueOpenCount).toBe(1)
    expect(entry.openCount - entry.pmOverdueOpenCount).toBe(0)
    expect(entry.nonPmMaxSeverity).toBe('CRITICAL')
  })

  it('비PM 최고 심각도는 더 높은 것으로만 올라간다', () => {
    const byEquipment: Record<number, OpenAlarmEquipmentSummary> = {}
    accumulateAlarm(byEquipment, sensor('MAJOR'), 'OPEN')
    accumulateAlarm(byEquipment, sensor('WARNING'), 'OPEN')
    expect(byEquipment[1].nonPmMaxSeverity).toBe('MAJOR')
    accumulateAlarm(byEquipment, sensor('CRITICAL'), 'OPEN')
    expect(byEquipment[1].nonPmMaxSeverity).toBe('CRITICAL')
  })

  it('설비별로 따로 집계한다', () => {
    const byEquipment: Record<number, OpenAlarmEquipmentSummary> = {}
    accumulateAlarm(byEquipment, sensor('WARNING', 1), 'OPEN')
    accumulateAlarm(byEquipment, sensor('CRITICAL', 2), 'OPEN')
    expect(byEquipment[1].maxSeverity).toBe('WARNING')
    expect(byEquipment[2].maxSeverity).toBe('CRITICAL')
  })
})
