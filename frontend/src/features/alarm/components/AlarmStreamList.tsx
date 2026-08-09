import type { ReactNode } from 'react'
import { EmptyState, SeverityBadge } from '@/shared/ui'
import { formatKst } from '@/shared/lib/datetime'
import { ALARM_STATUS_LABEL } from '../types'
import type { Alarm } from '../types'
import './alarm.css'

interface AlarmStreamListProps {
  alarms: Alarm[]
  /** SSE 로 방금 들어온 알람 id 집합 — 1회 강조 애니메이션용 */
  freshIds?: ReadonlySet<number>
  onSelect?: (alarm: Alarm) => void
  /** 우측 상단 부가 표시 (실시간/폴백 배지 등) */
  aside?: ReactNode
}

/** S-1 하단 실시간 알람 스트림 (최근 10건, SSE 로 상단 추가) */
export function AlarmStreamList({ alarms, freshIds, onSelect, aside }: AlarmStreamListProps) {
  return (
    <section className="alarm-stream">
      <div className="alarm-stream-head">
        <h2 className="alarm-stream-title">실시간 알람 스트림 (최근 {alarms.length}건)</h2>
        {aside}
      </div>

      {alarms.length === 0 ? (
        <EmptyState title="표시할 알람이 없습니다" description="발생하는 즉시 여기에 추가됩니다." icon="⚠" />
      ) : (
        alarms.map((alarm) => (
          <button
            key={alarm.id}
            type="button"
            className="alarm-stream-item"
            data-severity={alarm.severity}
            data-fresh={freshIds?.has(alarm.id) ? 'true' : undefined}
            onClick={() => onSelect?.(alarm)}
          >
            <span className="stream-time">{formatKst(alarm.occurredAt)}</span>
            <span className="stream-equipment">
              {alarm.equipmentCode ?? `#${alarm.equipmentId}`}
            </span>
            <SeverityBadge severity={alarm.severity} />
            <span className="stream-message">{alarm.message}</span>
            <span className="alarm-status" data-status={alarm.status}>
              {ALARM_STATUS_LABEL[alarm.status] ?? alarm.status}
            </span>
          </button>
        ))
      )}
    </section>
  )
}
