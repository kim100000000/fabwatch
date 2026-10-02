import { SeverityBadge } from '@/shared/ui'
import { formatKst } from '@/shared/lib/datetime'
import { ALARM_STATUS_LABEL, ALARM_TYPE_LABEL, linkedInspectionId } from '../types'
import type { Alarm } from '../types'
import './alarm.css'

interface AlarmTableProps {
  alarms: Alarm[]
  /** ACK 처리 (OPEN 에서만 활성) */
  onAck: (alarm: Alarm) => void
  /** RESOLVE 다이얼로그 열기 (ACK 에서만 활성 — 해제 사유 필수) */
  onResolveRequest: (alarm: Alarm) => void
  /** 처리 중인 알람 id — 중복 클릭 방지 */
  busyAlarmId?: number | null
  /** 설비가 고정된 화면(S-3 알람 탭)에서는 설비 컬럼을 숨긴다 */
  hideEquipment?: boolean
  onSelectEquipment?: (equipmentId: number) => void
  /** 해제 사유가 'BM 점검 이력 #id' 인 알람의 점검 이력 이동 링크 (선택) */
  onOpenInspection?: (inspectionId: number) => void
}

/** 발생값/기준값 표기 — 둘 다 없으면 '-' */
function formatTrigger(alarm: Alarm): string {
  if (alarm.triggerValue === null || alarm.triggerValue === undefined) return '-'
  if (alarm.thresholdValue === null || alarm.thresholdValue === undefined) {
    return `${alarm.triggerValue}`
  }
  return `${alarm.triggerValue} / ${alarm.thresholdValue}`
}

/** 해제(RESOLVED) 알람의 해제 사유 · 처리자 · 처리 시각 (KST) */
function ResolveInfo({
  alarm,
  onOpenInspection,
}: {
  alarm: Alarm
  onOpenInspection?: (inspectionId: number) => void
}) {
  const inspectionId = linkedInspectionId(alarm.resolveNote)
  return (
    <div className="alarm-resolve-info">
      <span className="alarm-resolve-note">해제 사유: {alarm.resolveNote || '(사유 없음)'}</span>
      <span className="alarm-resolve-meta">
        {alarm.resolvedByName ?? '-'} · <span className="mono">{formatKst(alarm.resolvedAt)}</span>
        {inspectionId !== null && onOpenInspection && (
          <>
            {' · '}
            <button type="button" className="inline-link" onClick={() => onOpenInspection(inspectionId)}>
              점검 이력 #{inspectionId} 보기
            </button>
          </>
        )}
      </span>
    </div>
  )
}

/**
 * 알람 테이블 — OPEN 상단 고정 정렬은 호출부(서버 정렬 + compareAlarms)에서 보장한다.
 * ACK → RESOLVE 순서 강제(docs/03 F-5.3): RESOLVE 버튼은 ACK 상태에서만 활성화.
 */
export function AlarmTable({
  alarms,
  onAck,
  onResolveRequest,
  busyAlarmId = null,
  hideEquipment = false,
  onSelectEquipment,
  onOpenInspection,
}: AlarmTableProps) {
  return (
    <div className="table-scroll">
      <table className="data-table alarm-table">
        <thead>
          <tr>
            <th>발생 시각 (KST)</th>
            {!hideEquipment && <th>설비</th>}
            <th>유형</th>
            <th>심각도</th>
            <th>메시지</th>
            <th>발생값 / 기준</th>
            <th>상태</th>
            <th>담당</th>
            <th>조치</th>
          </tr>
        </thead>
        <tbody>
          {alarms.map((alarm) => (
            <tr
              key={alarm.id}
              data-status={alarm.status}
              data-severity={alarm.severity}
              data-open={alarm.status !== 'RESOLVED'}
            >
              <td className="mono">{formatKst(alarm.occurredAt)}</td>
              {!hideEquipment && (
                <td>
                  {onSelectEquipment ? (
                    <button
                      type="button"
                      className="equipment-link"
                      onClick={() => onSelectEquipment(alarm.equipmentId)}
                    >
                      {alarm.equipmentCode ?? `#${alarm.equipmentId}`}
                    </button>
                  ) : (
                    <span className="mono">{alarm.equipmentCode ?? `#${alarm.equipmentId}`}</span>
                  )}
                </td>
              )}
              <td>
                {ALARM_TYPE_LABEL[alarm.alarmType] ?? alarm.alarmType}
                {alarm.sensorType ? <span className="mono"> · {alarm.sensorType}</span> : null}
              </td>
              <td>
                <SeverityBadge severity={alarm.severity} />
              </td>
              <td className="alarm-message">
                {alarm.message}
                {alarm.status === 'RESOLVED' && (
                  <ResolveInfo alarm={alarm} onOpenInspection={onOpenInspection} />
                )}
              </td>
              <td className="mono">{formatTrigger(alarm)}</td>
              <td>
                <span className="alarm-status" data-status={alarm.status}>
                  {ALARM_STATUS_LABEL[alarm.status] ?? alarm.status}
                </span>
              </td>
              <td>
                {alarm.status === 'RESOLVED'
                  ? (alarm.resolvedByName ?? '-')
                  : (alarm.ackByName ?? '-')}
              </td>
              <td>
                <span className="row-actions">
                  <button
                    type="button"
                    className="btn btn-sm"
                    disabled={alarm.status !== 'OPEN' || busyAlarmId === alarm.id}
                    onClick={() => onAck(alarm)}
                  >
                    ACK
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm"
                    // ACK 없이 RESOLVED 불가 (서버도 400 ACK_REQUIRED_FIRST 로 막는다)
                    disabled={alarm.status !== 'ACK' || busyAlarmId === alarm.id}
                    title={alarm.status === 'OPEN' ? '먼저 ACK(확인) 처리해야 합니다' : undefined}
                    onClick={() => onResolveRequest(alarm)}
                  >
                    RESOLVE
                  </button>
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
