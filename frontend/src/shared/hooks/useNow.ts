import { useEffect, useState } from 'react'

/** 일정 주기로 갱신되는 현재 시각(ms) — 상대시간 표기('3분 전')를 화면에서 최신으로 유지한다 */
export function useNow(intervalMs: number = 30_000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs])
  return now
}
