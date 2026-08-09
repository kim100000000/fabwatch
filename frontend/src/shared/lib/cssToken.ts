import { useMemo } from 'react'

/**
 * CSS 디자인 토큰(:root 변수) 값을 JS 로 읽는다.
 *
 * Recharts 는 색을 SVG presentation attribute(stroke/fill)로 내보내는데,
 * presentation attribute 는 `var(--token)` 치환을 지원하지 않는다.
 * 그래서 하드코딩 대신 global.css 의 토큰 값을 런타임에 읽어 넘긴다.
 * (docs/04 §1 토큰이 단일 출처로 유지된다 — 헥스값을 코드에 복사하지 않는다.)
 */
export function readCssToken(name: string): string {
  if (typeof window === 'undefined' || typeof document === 'undefined') return ''
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

/** 차트에서 쓰는 토큰 묶음 — 렌더마다 getComputedStyle 을 호출하지 않도록 메모이즈 */
export interface ChartTokens {
  accent: string
  warning: string
  critical: string
  border: string
  textSecondary: string
  bgCard: string
  textPrimary: string
}

export function useChartTokens(): ChartTokens {
  return useMemo(
    () => ({
      accent: readCssToken('--accent'),
      warning: readCssToken('--alarm-warning'),
      critical: readCssToken('--alarm-critical'),
      border: readCssToken('--border'),
      textSecondary: readCssToken('--text-secondary'),
      bgCard: readCssToken('--bg-card'),
      textPrimary: readCssToken('--text-primary'),
    }),
    [],
  )
}
