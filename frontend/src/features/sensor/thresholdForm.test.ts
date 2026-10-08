import { describe, expect, it } from 'vitest'
import { isSameThresholds, toInputs, validateThresholds } from './thresholdForm'
import type { ThresholdInputs } from './thresholdForm'

const inputs = (patch: Partial<ThresholdInputs> = {}): ThresholdInputs => ({
  critLow: '28',
  warnLow: '30',
  warnHigh: '44',
  critHigh: '48',
  ...patch,
})

describe('validateThresholds', () => {
  it('정상 순서(위험 하한 ≤ 경고 하한 < 경고 상한 ≤ 위험 상한)는 통과한다', () => {
    const result = validateThresholds(inputs())
    expect(result.valid).toBe(true)
    expect(result.values).toEqual({ critLow: 28, warnLow: 30, warnHigh: 44, critHigh: 48 })
  })

  it('경계가 같은 값(위험 하한 = 경고 하한, 경고 상한 = 위험 상한)은 허용한다', () => {
    expect(validateThresholds(inputs({ critLow: '30', critHigh: '44' })).valid).toBe(true)
  })

  it.each([
    ['위험 하한 > 경고 하한', { critLow: '31' }, 'critLow'],
    ['경고 하한 = 경고 상한', { warnLow: '44' }, 'warnHigh'],
    ['경고 상한 > 위험 상한', { critHigh: '43' }, 'critHigh'],
    ['위험 하한 ≥ 경고 상한(중간 경계를 비운 경우)', { warnLow: '', critLow: '44' }, 'critLow'],
    ['경고 하한 ≥ 위험 상한(중간 경계를 비운 경우)', { warnHigh: '', warnLow: '48' }, 'critHigh'],
    ['위험 하한 ≥ 위험 상한(안쪽 경계를 비운 경우)', { warnLow: '', warnHigh: '', critLow: '50' }, 'critHigh'],
  ] as const)('순서 위반 — %s', (_name, patch, errorField) => {
    const result = validateThresholds(inputs(patch))
    expect(result.valid).toBe(false)
    expect(result.fieldErrors[errorField]).toBeTruthy()
  })

  it('빈 값은 null(해당 방향 미사용)로 처리하고 나머지가 맞으면 통과한다', () => {
    const result = validateThresholds(inputs({ critLow: '', warnLow: '' }))
    expect(result.valid).toBe(true)
    expect(result.values.critLow).toBeNull()
    expect(result.values.warnLow).toBeNull()
  })

  it('소수는 둘째 자리까지만 허용한다', () => {
    expect(validateThresholds(inputs({ warnLow: '30.25' })).valid).toBe(true)
    const tooLong = validateThresholds(inputs({ warnLow: '30.123' }))
    expect(tooLong.valid).toBe(false)
    expect(tooLong.fieldErrors.warnLow).toBeTruthy()
  })

  it('숫자가 아닌 입력은 오류다', () => {
    expect(validateThresholds(inputs({ critHigh: 'abc' })).fieldErrors.critHigh).toBeTruthy()
  })

  it('4값을 전부 비우면(센서 감시 꺼짐) 통과시키지 않는다', () => {
    const result = validateThresholds({ critLow: '', warnLow: '', warnHigh: '', critHigh: '' })
    expect(result.valid).toBe(false)
  })
})

describe('isSameThresholds / toInputs', () => {
  it('null 과 undefined 를 같은 미설정으로 비교한다', () => {
    expect(
      isSameThresholds({ critLow: null, warnLow: 30, warnHigh: 44, critHigh: null }, { warnLow: 30, warnHigh: 44 }),
    ).toBe(true)
    expect(
      isSameThresholds({ critLow: 1, warnLow: 30, warnHigh: 44, critHigh: null }, { warnLow: 30, warnHigh: 44 }),
    ).toBe(false)
  })

  it('현재 임계치를 입력 문자열로 바꾼다 (null 은 빈 문자열)', () => {
    expect(toInputs({ critLow: null, warnLow: 30.5, warnHigh: 44, critHigh: undefined })).toEqual({
      critLow: '',
      warnLow: '30.5',
      warnHigh: '44',
      critHigh: '',
    })
  })
})
