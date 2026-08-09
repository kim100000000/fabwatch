/**
 * 시각 유틸 — 서버는 UTC로 응답하므로 화면 표시는 항상 KST 변환 후 렌더링한다.
 * 교대 판정: D=08~20시 / N=20~08시 (KST 기준, CLAUDE.md 컨벤션)
 */

const KST_TIME_ZONE = 'Asia/Seoul'

export type Shift = 'D' | 'N'

// sv-SE 로케일은 ISO 형태(YYYY-MM-DD HH:mm)로 출력돼 자릿수가 흔들리지 않는다.
const DATETIME_FORMATTER = new Intl.DateTimeFormat('sv-SE', {
  timeZone: KST_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const DATE_FORMATTER = new Intl.DateTimeFormat('sv-SE', {
  timeZone: KST_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/** 'YYYY-MM-DD HH:mm' (KST) — 차트 축에서 넘어오는 epoch ms 도 받는다 */
export function formatKst(value: string | Date | number | null | undefined): string {
  const date = typeof value === 'number' ? new Date(value) : toDate(value)
  return date ? DATETIME_FORMATTER.format(date) : '-'
}

/** 'YYYY-MM-DD' (KST) */
export function formatKstDate(value: string | Date | null | undefined): string {
  const date = toDate(value)
  return date ? DATE_FORMATTER.format(date) : '-'
}

// 초 단위까지 필요한 실시간 차트 축·스트림용 (KST)
const TIME_FORMATTER = new Intl.DateTimeFormat('sv-SE', {
  timeZone: KST_TIME_ZONE,
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

/** 'HH:mm:ss' (KST) — 실시간 차트 X축 */
export function formatKstTime(value: string | Date | number | null | undefined): string {
  const date = typeof value === 'number' ? new Date(value) : toDate(value)
  return date ? TIME_FORMATTER.format(date) : '-'
}

/** 'MM-DD HH:mm' (KST) — 이력 차트 X축 (자릿수 절약) */
export function formatKstShort(value: string | Date | number | null | undefined): string {
  const date = typeof value === 'number' ? new Date(value) : toDate(value)
  return date ? DATETIME_FORMATTER.format(date).slice(5) : '-'
}

/**
 * KST 날짜 문자열('YYYY-MM-DD')을 그 날의 시작/끝 시각 UTC ISO 로 변환한다.
 * 화면 입력은 KST, 서버 전달은 UTC (CLAUDE.md 시각 규칙).
 */
export function kstDateToUtcIso(dateText: string, edge: 'start' | 'end'): string | undefined {
  if (!dateText) return undefined
  const suffix = edge === 'start' ? 'T00:00:00.000+09:00' : 'T23:59:59.999+09:00'
  const date = new Date(`${dateText}${suffix}`)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}

/** 지금부터 n분 전 시각의 UTC ISO (이력 조회 from 값 계산용) */
export function minutesAgoIso(minutes: number): string {
  return new Date(Date.now() - minutes * 60_000).toISOString()
}

/** KST 기준 시(hour) 값 */
export function kstHour(value: string | Date = new Date()): number {
  const date = toDate(value) ?? new Date()
  const hour = new Intl.DateTimeFormat('en-US', {
    timeZone: KST_TIME_ZONE,
    hour: '2-digit',
    hour12: false,
  }).format(date)
  return Number(hour) % 24
}

/** 교대조 판정 (KST): 08:00~19:59 = D, 20:00~07:59 = N */
export function currentShift(value: string | Date = new Date()): Shift {
  const hour = kstHour(value)
  return hour >= 8 && hour < 20 ? 'D' : 'N'
}

function toDate(value: string | Date | null | undefined): Date | null {
  if (!value) return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}
