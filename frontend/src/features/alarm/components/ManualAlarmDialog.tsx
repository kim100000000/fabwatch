import { useState } from 'react'
import { Modal, Spinner } from '@/shared/ui'
import { toApiError } from '@/shared/api'
import { toUserMessage } from '@/shared/lib/errorMessage'
import type { EquipmentSummary } from '@/features/equipment'
import { createManualAlarm } from '../api/alarmApi'
import { ALARM_SEVERITIES } from '../types'
import type { Alarm, AlarmSeverity } from '../types'
import './alarm.css'

interface ManualAlarmDialogProps {
  equipments: EquipmentSummary[]
  /** 설비가 이미 정해진 화면에서 열 때 초기값 */
  defaultEquipmentId?: number | null
  onClose: () => void
  onCreated: (alarm: Alarm) => void
}

/**
 * 수동 고장 보고 폼 — POST /alarms/manual `{equipmentId, severity, message}` (docs/06 §6).
 * 센서 임계치와 무관하게 작업자가 직접 올리는 알람이라 alarmType 은 서버가 MANUAL 로 채운다.
 */
export function ManualAlarmDialog({
  equipments,
  defaultEquipmentId = null,
  onClose,
  onCreated,
}: ManualAlarmDialogProps) {
  const [equipmentId, setEquipmentId] = useState<number | null>(
    defaultEquipmentId ?? equipments[0]?.id ?? null,
  )
  const [severity, setSeverity] = useState<AlarmSeverity>('MAJOR')
  const [message, setMessage] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const trimmed = message.trim()
  const valid = equipmentId !== null && trimmed.length > 0

  const submit = async () => {
    if (!valid || equipmentId === null) return
    setSubmitting(true)
    setError(null)
    try {
      const created = await createManualAlarm({ equipmentId, severity, message: trimmed })
      onCreated(created)
    } catch (cause) {
      setError(toUserMessage(toApiError(cause)))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title="수동 고장 보고"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={submitting}>
            취소
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => void submit()}
            disabled={submitting || !valid}
          >
            {submitting ? <Spinner /> : null}
            보고
          </button>
        </>
      }
    >
      <div className="alarm-form">
        <div className="field">
          <label htmlFor="manual-alarm-equipment">설비</label>
          <select
            id="manual-alarm-equipment"
            value={equipmentId ?? ''}
            onChange={(event) =>
              setEquipmentId(event.target.value ? Number(event.target.value) : null)
            }
          >
            <option value="">설비를 선택하세요</option>
            {equipments.map((equipment) => (
              <option key={equipment.id} value={equipment.id}>
                {equipment.code} · {equipment.name}
              </option>
            ))}
          </select>
        </div>

        <div className="field">
          <label htmlFor="manual-alarm-severity">심각도</label>
          <select
            id="manual-alarm-severity"
            value={severity}
            onChange={(event) => setSeverity(event.target.value as AlarmSeverity)}
          >
            {ALARM_SEVERITIES.map((item) => (
              <option key={item} value={item}>
                {item}
              </option>
            ))}
          </select>
          <span className="field-hint">
            CRITICAL 로 보고하면 서버 규칙에 따라 설비 상태가 자동 DOWN 으로 기록될 수 있습니다
            (DB 상태 기록일 뿐 실설비 제어가 아님 — docs/11 §10).
          </span>
        </div>

        <div className="field">
          <label htmlFor="manual-alarm-message">내용</label>
          <textarea
            id="manual-alarm-message"
            rows={4}
            value={message}
            maxLength={300}
            placeholder="예) 합착 롤러 이음 발생, 육안 점검 필요"
            onChange={(event) => setMessage(event.target.value)}
          />
        </div>

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
      </div>
    </Modal>
  )
}
