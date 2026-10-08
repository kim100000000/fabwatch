import type { ReactNode } from 'react'
import { EmptyState, SeverityBadge } from '@/shared/ui'
import { useNow } from '@/shared/hooks/useNow'
import { formatKst, formatRelative } from '@/shared/lib/datetime'
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

const DAY_MS = 24 * 60 * 60 * 1000

/** 해제됐거나 24시간이 지난 알람 — 스트림에서 흐리게 표시 */
function isOld(alarm: Alarm, now: number): boolean {
  return alarm.status === 'RESOLVED' || now - new Date(alarm.occurredAt).getTime() > DAY_MS
}

/** S-1 하단 실시간 알람 스트림 (최근 10건, SSE 로 상단 추가) */
export function AlarmStreamList({ alarms, freshIds, onSelect, aside }: AlarmStreamListProps) {
  // 상대시간('3분 전')이 멈춰 있지 않도록 30초마다 갱신
  const now = useNow(30_000)

  return (
    <section className="alarm-stream">
      <div className="alarm-stream-head">
        <h2 className="alarm-stream-title">최근 알람 (미해결 우선 {alarms.length}건)</h2>
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
            // 해제됐거나 하루 넘은 항목은 흐리게 — 지금 봐야 할 알람과 구분한다
            data-old={isOld(alarm, now) ? 'true' : undefined}
            aria-label={`${alarm.equipmentCode ?? `설비 ${alarm.equipmentId}`} ${alarm.severity} ${alarm.message}, ${ALARM_STATUS_LABEL[alarm.status] ?? alarm.status}, ${formatRelative(alarm.occurredAt, now)}. 설비 상세 보기`}
            onClick={() => onSelect?.(alarm)}
          >
            <span className="stream-time" title={`${formatKst(alarm.occurredAt)} KST`}>
              {formatRelative(alarm.occurredAt, now)}
            </span>
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
