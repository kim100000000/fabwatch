import { describe, expect, it } from 'vitest'
import { currentShift, formatKst, formatRelative, kstLocalToUtcIso, toKstLocalInput } from './datetime'

describe('kstLocalToUtcIso / toKstLocalInput', () => {
  it('KST 입력값을 UTC ISO 로 바꾼다 (KST = UTC+9)', () => {
    expect(kstLocalToUtcIso('2026-10-08T08:00')).toBe('2026-10-07T23:00:00.000Z')
    expect(kstLocalToUtcIso('2026-10-08T00:30')).toBe('2026-10-07T15:30:00.000Z')
  })

  it('빈 값/해석 불가 값은 null', () => {
    expect(kstLocalToUtcIso('')).toBeNull()
    expect(kstLocalToUtcIso('not-a-date')).toBeNull()
  })

  it('왕복 변환이 일치한다', () => {
    const utc = kstLocalToUtcIso('2026-03-01T19:45')
    expect(toKstLocalInput(utc)).toBe('2026-03-01T19:45')
  })
})

describe('currentShift (KST 기준 D=08~20 / N=20~08)', () => {
  // KST 시각을 UTC ISO 로 만들어 전달한다 (서버 응답과 같은 형태)
  const kst = (local: string) => kstLocalToUtcIso(local)!

  it.each([
    ['2026-10-08T07:59', 'N'],
    ['2026-10-08T08:00', 'D'],
    ['2026-10-08T19:59', 'D'],
    ['2026-10-08T20:00', 'N'],
    ['2026-10-08T00:00', 'N'],
    ['2026-10-08T12:30', 'D'],
  ] as const)('%s → %s', (local, expected) => {
    expect(currentShift(kst(local))).toBe(expected)
  })

  it('UTC 날짜가 달라도 KST 시각으로 판정한다 (UTC 23:00 = KST 08:00 → D)', () => {
    expect(currentShift('2026-10-07T23:00:00Z')).toBe('D')
    expect(currentShift('2026-10-07T22:59:00Z')).toBe('N')
  })
})

describe('formatKst', () => {
  it('UTC 를 KST 로 변환해 표시한다', () => {
    expect(formatKst('2026-10-07T23:05:00Z')).toBe('2026-10-08 08:05')
  })
  it('값이 없으면 -', () => {
    expect(formatKst(null)).toBe('-')
  })
})

describe('formatRelative', () => {
  const now = new Date('2026-10-08T12:00:00Z').getTime()
  const ago = (ms: number) => new Date(now - ms).toISOString()

  it.each([
    [10_000, '방금'],
    [3 * 60_000, '3분 전'],
    [2 * 3_600_000, '2시간 전'],
    [3 * 86_400_000, '3일 전'],
  ])('%i ms 전 → %s', (diff, expected) => {
    expect(formatRelative(ago(diff), now)).toBe(expected)
  })

  it('미래 시각은 방금으로 본다', () => {
    expect(formatRelative(new Date(now + 60_000).toISOString(), now)).toBe('방금')
  })
})
