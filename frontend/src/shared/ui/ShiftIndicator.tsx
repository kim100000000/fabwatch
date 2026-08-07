import { useEffect, useState } from 'react'
import { currentShift } from '@/shared/lib/datetime'
import type { Shift } from '@/shared/lib/datetime'
import './ui.css'

const SHIFT_LABEL: Record<Shift, string> = {
  D: '주간 (08–20)',
  N: '야간 (20–08)',
}

/** 현재 교대 D/N 표시 — KST 기준, 1분마다 재판정 */
export function ShiftIndicator() {
  const [shift, setShift] = useState<Shift>(() => currentShift())

  useEffect(() => {
    const timer = window.setInterval(() => setShift(currentShift()), 60_000)
    return () => window.clearInterval(timer)
  }, [])

  return (
    <span className="shift-indicator" title={SHIFT_LABEL[shift]}>
      교대 <strong>{shift}</strong>
    </span>
  )
}
