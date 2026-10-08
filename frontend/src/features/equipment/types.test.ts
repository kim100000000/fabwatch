import { describe, expect, it } from 'vitest'
import { ALLOWED_STATUS_TRANSITIONS, EQUIPMENT_STATUSES, getStatusCandidates, isReasonRequired } from './types'
import type { EquipmentStatus } from './types'

/** docs/03 F-2 상태 머신 — 역할 × 현재 상태별 수동 전환 후보 전수 */
const EXPECTED: Record<'ADMIN' | 'ENGINEER' | 'TECHNICIAN', Record<EquipmentStatus, EquipmentStatus[]>> = {
  ADMIN: { RUN: ['IDLE', 'DOWN', 'PM'], IDLE: ['RUN', 'DOWN', 'PM'], DOWN: ['IDLE', 'RUN', 'PM'], PM: ['IDLE'] },
  ENGINEER: { RUN: ['IDLE', 'DOWN', 'PM'], IDLE: ['RUN', 'DOWN', 'PM'], DOWN: ['IDLE', 'RUN', 'PM'], PM: ['IDLE'] },
  // TECHNICIAN 은 DOWN → IDLE 만 가능 (DOWN → RUN 은 시운전 생략이라 ENGINEER+)
  TECHNICIAN: { RUN: [], IDLE: [], DOWN: ['IDLE'], PM: [] },
}

describe('getStatusCandidates (역할 × 상태 전수)', () => {
  for (const role of ['ADMIN', 'ENGINEER', 'TECHNICIAN'] as const) {
    for (const status of EQUIPMENT_STATUSES) {
      it(`${role} / ${status} → [${EXPECTED[role][status].join(', ')}]`, () => {
        expect(getStatusCandidates(status, role)).toEqual(EXPECTED[role][status])
      })
    }
  }

  it('역할을 모르면(로그인 전 등) 후보가 없다', () => {
    expect(getStatusCandidates('DOWN', undefined)).toEqual([])
  })

  it('PM → RUN 직행은 어떤 역할에도 없다 (정비 후 시운전 대기를 거쳐야 함)', () => {
    expect(ALLOWED_STATUS_TRANSITIONS.PM).not.toContain('RUN')
    expect(getStatusCandidates('PM', 'ADMIN')).not.toContain('RUN')
  })
})

describe('isReasonRequired', () => {
  it('DOWN 이탈(IDLE/RUN)일 때만 사유가 필수다', () => {
    expect(isReasonRequired('DOWN', 'IDLE')).toBe(true)
    expect(isReasonRequired('DOWN', 'RUN')).toBe(true)
    expect(isReasonRequired('DOWN', 'PM')).toBe(false)
    expect(isReasonRequired('RUN', 'DOWN')).toBe(false)
  })
})
