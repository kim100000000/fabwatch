import { glossaryOf } from '@/shared/lib/glossary'
import './ui.css'

/** 알람 심각도 (docs/05 alarms.severity) */
export type AlarmSeverity = 'WARNING' | 'MAJOR' | 'CRITICAL'

interface SeverityBadgeProps {
  severity: AlarmSeverity
}

/** 알람 심각도 3색 배지 — 색은 전역 토큰(--alarm-*) 고정 */
export function SeverityBadge({ severity }: SeverityBadgeProps) {
  return (
    <span className="severity-badge" data-severity={severity} title={glossaryOf(severity)?.description}>
      {severity}
    </span>
  )
}
