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

/** 'YYYY-MM-DD HH:mm' (KST) */
export function formatKst(value: string | Date | null | undefined): string {
  const date = toDate(value)
  return date ? DATETIME_FORMATTER.format(date) : '-'
}

/** 'YYYY-MM-DD' (KST) */
export function formatKstDate(value: string | Date | null | undefined): string {
  const date = toDate(value)
  return date ? DATE_FORMATTER.format(date) : '-'
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
