/**
 * 임계치 편집 폼 검증 (docs/03 F-2 '임계치 설정').
 * 규칙: crit_low ≤ warn_low < warn_high ≤ crit_high — null(빈 값)인 경계는 비교에서 제외한다.
 * 서버 ThresholdRangeValidator 와 같은 6쌍을 그대로 검사한다(서버가 최종 판정, 여기는 즉시 안내용).
 * 센서 컬럼은 decimal(10,2) 이므로 소수 둘째 자리까지만 허용한다.
 */
import type { ThresholdValues } from './types'

export type ThresholdField = 'critLow' | 'warnLow' | 'warnHigh' | 'critHigh'

/** 수직선(낮음→높음) 순서 — 화면 입력 순서이기도 하다 */
export const THRESHOLD_FIELDS: { key: ThresholdField; label: string; kind: 'crit' | 'warn' }[] = [
  { key: 'critLow', label: '위험 하한', kind: 'crit' },
  { key: 'warnLow', label: '경고 하한', kind: 'warn' },
  { key: 'warnHigh', label: '경고 상한', kind: 'warn' },
  { key: 'critHigh', label: '위험 상한', kind: 'crit' },
]

export type ThresholdInputs = Record<ThresholdField, string>

export interface ThresholdValidation {
  /** 파싱된 값 (빈 값=null, 형식 오류도 null 로 두되 fieldErrors 에 표시) */
  values: ThresholdValues
  fieldErrors: Partial<Record<ThresholdField, string>>
  valid: boolean
}

const NUMBER_PATTERN = /^-?\d+(\.\d{1,2})?$/
const MAX_ABS = 99_999_999.99 // decimal(10,2)

/** 숫자 → 입력창 문자열 (null=빈 값) */
export function toInput(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value)
}

/** 센서의 현재 임계치로 폼 초기값을 만든다 */
export function toInputs(values: Partial<ThresholdValues>): ThresholdInputs {
  return {
    critLow: toInput(values.critLow),
    warnLow: toInput(values.warnLow),
    warnHigh: toInput(values.warnHigh),
    critHigh: toInput(values.critHigh),
  }
}

/** 순서 규칙 — lower 가 upper 보다 작아야(strict) 하거나 같아도(≤) 되는 쌍. 오류는 error 필드에 표시 */
const ORDER_RULES: {
  lower: ThresholdField
  upper: ThresholdField
  strict: boolean
  error: ThresholdField
  message: string
}[] = [
  { lower: 'critLow', upper: 'warnLow', strict: false, error: 'critLow', message: '위험 하한은 경고 하한 이하여야 합니다.' },
  { lower: 'warnLow', upper: 'warnHigh', strict: true, error: 'warnHigh', message: '경고 상한은 경고 하한보다 커야 합니다.' },
  { lower: 'warnHigh', upper: 'critHigh', strict: false, error: 'critHigh', message: '위험 상한은 경고 상한 이상이어야 합니다.' },
  // 중간 경계가 비어 있을 때도 교차하지 않도록 하는 쌍 (서버 검증과 동일)
  { lower: 'critLow', upper: 'warnHigh', strict: true, error: 'critLow', message: '위험 하한은 경고 상한보다 작아야 합니다.' },
  { lower: 'warnLow', upper: 'critHigh', strict: true, error: 'critHigh', message: '위험 상한은 경고 하한보다 커야 합니다.' },
  { lower: 'critLow', upper: 'critHigh', strict: true, error: 'critHigh', message: '위험 상한은 위험 하한보다 커야 합니다.' },
]

export function validateThresholds(inputs: ThresholdInputs): ThresholdValidation {
  const values: ThresholdValues = { critLow: null, warnLow: null, warnHigh: null, critHigh: null }
  const fieldErrors: Partial<Record<ThresholdField, string>> = {}

  for (const { key, label } of THRESHOLD_FIELDS) {
    const text = inputs[key].trim()
    if (text === '') continue // 빈 값 = 해당 방향 미사용(null)
    if (!NUMBER_PATTERN.test(text) || Math.abs(Number(text)) > MAX_ABS) {
      fieldErrors[key] = `${label}은(는) 숫자(소수 둘째 자리까지)로 입력해 주세요.`
      continue
    }
    values[key] = Number(text)
  }

  for (const rule of ORDER_RULES) {
    const lower = values[rule.lower]
    const upper = values[rule.upper]
    if (lower === null || upper === null) continue
    const broken = rule.strict ? lower >= upper : lower > upper
    if (broken && !fieldErrors[rule.error]) fieldErrors[rule.error] = rule.message
  }

  // 4값을 전부 비우면 센서 감시가 꺼지므로 서버도 거부한다(INVALID_THRESHOLD_RANGE) — 같은 규칙으로 막는다
  const allEmpty = THRESHOLD_FIELDS.every(({ key }) => inputs[key].trim() === '')
  return { values, fieldErrors, valid: Object.keys(fieldErrors).length === 0 && !allEmpty }
}

/** 현재 값과 완전히 같은지 (같은 값으로 저장하면 이력만 쌓이므로 막는다) */
export function isSameThresholds(a: ThresholdValues, b: Partial<ThresholdValues>): boolean {
  return (
    a.critLow === (b.critLow ?? null) &&
    a.warnLow === (b.warnLow ?? null) &&
    a.warnHigh === (b.warnHigh ?? null) &&
    a.critHigh === (b.critHigh ?? null)
  )
}
