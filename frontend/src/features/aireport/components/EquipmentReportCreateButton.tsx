import { useState } from 'react'
import { useAuth } from '@/app/providers/useAuth'
import { ALARM_STATUS_LABEL } from '@/features/alarm'
import type { Alarm } from '@/features/alarm'
import { formatKst } from '@/shared/lib/datetime'
import { Modal, SeverityBadge, Spinner } from '@/shared/ui'
import { useReportLauncher } from '../api/useReportLauncher'
import { UNRESOLVED_ALARM_PAGE_SIZE, useUnresolvedAlarms } from '../api/useUnresolvedAlarms'
import { canManageReport } from '../types'
import './report.css'

interface EquipmentReportCreateButtonProps {
  equipmentId: number
  /** 비활성 사유를 버튼 옆에 글자로도 보여준다 (탭 상단용) */
  showHint?: boolean
}

const DISABLED_HINT = '미해결 알람이 있을 때 생성할 수 있습니다'
const QUERY_FAILED_HINT = '알람 조회에 실패했습니다'

/**
 * 설비 단위 'AI 리포트 생성' 버튼 (S-3 헤더 + 리포트 탭 공용). ENGINEER+ 에게만 보인다.
 * - 미해결(OPEN/ACK) 알람이 없으면 비활성 (툴팁/안내)
 * - 1건이면 바로 생성, 여러 건이면 대상 선택 다이얼로그 (기본: 최신 알람)
 */
export function EquipmentReportCreateButton(props: EquipmentReportCreateButtonProps) {
  const { user } = useAuth()
  if (!canManageReport(user?.role)) return null
  return <CreateButtonInner {...props} />
}

function CreateButtonInner({ equipmentId, showHint = false }: EquipmentReportCreateButtonProps) {
  const { alarms, loading, error: queryError, truncated, refetch } = useUnresolvedAlarms(equipmentId, true)
  const launcher = useReportLauncher()
  const [pickerOpen, setPickerOpen] = useState(false)

  // 조회 실패는 '알람 없음'이 아니다 — 두 상태를 분리해 안내한다 (QA L-5)
  const queryFailed = !loading && queryError !== null
  const noAlarm = !loading && !queryFailed && alarms.length === 0

  const handleClick = () => {
    launcher.clearError()
    // 클릭 시점에 다시 읽어 둔다 (알람 상태가 바뀌었을 수 있음 — 서버도 최종 검증)
    refetch()
    if (alarms.length === 1) {
      void launcher.launch({ alarmId: alarms[0].id })
    } else if (alarms.length > 1) {
      setPickerOpen(true)
    }
  }

  return (
    <span className="report-create">
      <button
        type="button"
        className="btn btn-primary"
        disabled={loading || queryFailed || noAlarm || launcher.busy}
        title={queryFailed ? QUERY_FAILED_HINT : noAlarm ? DISABLED_HINT : undefined}
        aria-describedby={noAlarm && showHint ? 'report-create-hint' : undefined}
        onClick={handleClick}
      >
        {launcher.busy && <Spinner />}
        AI 리포트 생성
      </button>
      {noAlarm && showHint && (
        <span id="report-create-hint" className="field-hint">
          {DISABLED_HINT}
        </span>
      )}
      {queryFailed && (
        <span className="report-inline-error" role="alert">
          {QUERY_FAILED_HINT}{' '}
          <button type="button" className="report-link-btn" onClick={refetch}>
            다시 시도
          </button>
        </span>
      )}
      {truncated && !queryFailed && (
        <span className="field-hint">미해결 알람이 많아 최근 {UNRESOLVED_ALARM_PAGE_SIZE}건만 표시합니다</span>
      )}
      {launcher.error && (
        <span className="report-inline-error" role="alert">
          {launcher.error}
        </span>
      )}

      {pickerOpen && (
        <AlarmPickerDialog
          alarms={alarms}
          busy={launcher.busy}
          error={launcher.error}
          onCancel={() => setPickerOpen(false)}
          onSubmit={async (alarmId) => {
            const ok = await launcher.launch({ alarmId })
            if (ok) setPickerOpen(false)
          }}
        />
      )}
    </span>
  )
}

interface AlarmPickerDialogProps {
  alarms: Alarm[]
  busy: boolean
  error: string | null
  onCancel: () => void
  onSubmit: (alarmId: number) => void
}

/** 대상 알람 선택 — 기본은 최신 알람 (alarms 는 발생 최신순) */
function AlarmPickerDialog({ alarms, busy, error, onCancel, onSubmit }: AlarmPickerDialogProps) {
  const [selectedId, setSelectedId] = useState<number>(alarms[0].id)

  return (
    <Modal
      title="리포트 대상 알람 선택"
      onClose={onCancel}
      footer={
        <>
          <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={busy}>
            취소
          </button>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => onSubmit(selectedId)}>
            {busy && <Spinner />}
            생성
          </button>
        </>
      }
    >
      <fieldset className="alarm-picker">
        <legend className="field-hint">미해결 알람이 여러 건입니다. 리포트를 만들 알람을 선택해 주세요.</legend>
        {alarms.map((alarm) => (
          <label key={alarm.id} className="alarm-picker-item" data-selected={alarm.id === selectedId}>
            <input
              type="radio"
              name="report-alarm"
              checked={alarm.id === selectedId}
              onChange={() => setSelectedId(alarm.id)}
            />
            <SeverityBadge severity={alarm.severity} />
            <span className="alarm-picker-message">{alarm.message}</span>
            <span className="mono alarm-picker-meta">
              #{alarm.id} · {ALARM_STATUS_LABEL[alarm.status]} · {formatKst(alarm.occurredAt)}
            </span>
          </label>
        ))}
      </fieldset>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
    </Modal>
  )
}
