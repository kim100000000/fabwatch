/**
 * KPI 수치 표시 포맷 (docs/03 F-5.4) — 설비 상세 KPI 카드와 대시보드 KPI 스트립이 함께 쓴다.
 * null 의미(서버 계약): mtbfHours=null → DOWN 0회 / mttrMin=null → 완료된 DOWN 없음 / availability=null → 집계 대상 시간 없음
 */
import { formatDuration, formatKst } from './datetime'

/** 가동률(0~1 비율) → '87.3%' (소수 1자리), null 은 '-' */
export function formatAvailability(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return '-'
  return `${(ratio * 100).toFixed(1)}%`
}

/** MTBF(시간) → 'N시간' (소수 1자리, 끝의 .0 은 생략), null 은 '고장 없음' */
export function formatMtbf(hours: number | null | undefined): string {
  if (hours === null || hours === undefined || Number.isNaN(hours)) return '고장 없음'
  const rounded = Math.round(hours * 10) / 10
  return `${rounded}시간`
}

/** MTTR(분) → 'N분' / 60분 이상이면 'N시간 M분', null 은 '-' */
export function formatMttr(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined || Number.isNaN(minutes)) return '-'
  // 1분 미만 값이 '0분' 으로 보이면 오해하므로 구분한다
  if (minutes > 0 && minutes < 1) return '1분 미만'
  return formatDuration(minutes)
}

/** 종료 시각이 이 범위 안이면 '현재' 로 본다 (서버가 조회 시각을 periodEnd 로 주는 경우 대비) */
const NOW_TOLERANCE_MS = 2 * 60_000

/** KPI 집계 구간 'YYYY-MM-DD HH:mm ~ 현재' (KST). 종료 시각이 현재(또는 미래)면 '현재' 로 표기한다 */
export function formatKpiRange(
  periodStart: string | null | undefined,
  periodEnd: string | null | undefined,
): string {
  if (!periodStart) return '-'
  const endTime = periodEnd ? new Date(periodEnd).getTime() : Number.NaN
  const endText =
    Number.isNaN(endTime) || endTime > Date.now() - NOW_TOLERANCE_MS ? '현재' : formatKst(periodEnd)
  return `${formatKst(periodStart)} ~ ${endText}`
}
