import { describe, expect, it } from 'vitest'
import { formatAvailability, formatKpiRange, formatMtbf, formatMttr } from './kpiFormat'

describe('formatAvailability', () => {
  it('비율을 소수 1자리 퍼센트로', () => {
    expect(formatAvailability(0.873)).toBe('87.3%')
    expect(formatAvailability(1)).toBe('100.0%')
    expect(formatAvailability(0)).toBe('0.0%')
  })
  it('null 은 -', () => {
    expect(formatAvailability(null)).toBe('-')
  })
})

describe('formatMtbf', () => {
  it('시간 단위, 끝의 .0 은 생략', () => {
    expect(formatMtbf(12)).toBe('12시간')
    expect(formatMtbf(12.34)).toBe('12.3시간')
  })
  it('고장이 없으면(null) "고장 없음"', () => {
    expect(formatMtbf(null)).toBe('고장 없음')
  })
})

describe('formatMttr', () => {
  it('분/시간 분 표기', () => {
    expect(formatMttr(45)).toBe('45분')
    expect(formatMttr(90)).toBe('1시간 30분')
  })
  it('1분 미만 양수는 0분으로 보이지 않게 구분한다', () => {
    expect(formatMttr(0.4)).toBe('1분 미만')
  })
  it('null 은 -', () => {
    expect(formatMttr(null)).toBe('-')
  })
})

describe('formatKpiRange', () => {
  it('종료가 현재에 가까우면 "현재" 로 표기한다', () => {
    const start = '2026-10-07T15:00:00Z'
    const text = formatKpiRange(start, new Date().toISOString())
    expect(text.endsWith('~ 현재')).toBe(true)
  })
  it('시작이 없으면 -', () => {
    expect(formatKpiRange(null, null)).toBe('-')
  })
})
