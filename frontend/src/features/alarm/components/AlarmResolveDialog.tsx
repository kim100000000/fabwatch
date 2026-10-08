import { useState } from 'react'
import { Modal, SeverityBadge, Spinner } from '@/shared/ui'
import { toApiError } from '@/shared/api'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { formatKst } from '@/shared/lib/datetime'
import { resolveAlarm } from '../api/alarmApi'
import type { Alarm } from '../types'
import './alarm.css'

interface AlarmResolveDialogProps {
  alarm: Alarm
  onClose: () => void
  onResolved: (alarm: Alarm) => void
}

/**
 * 알람 해제 다이얼로그 (docs/03 F-5.3).
 * - ACK 상태에서만 열린다 (호출부에서 버튼을 비활성화).
 * - 해제 사유(resolveNote)는 필수 — 비어 있으면 서버에 보내지 않는다.
 */
export function AlarmResolveDialog({ alarm, onClose, onResolved }: AlarmResolveDialogProps) {
  const [note, setNote] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const trimmed = note.trim()

  const submit = async () => {
    if (!trimmed) {
      setError('해제 사유는 필수입니다.')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const updated = await resolveAlarm(alarm.id, { resolveNote: trimmed })
      onResolved(updated)
    } catch (cause) {
      // 분기는 code 로 한다 — ACK_REQUIRED_FIRST 는 서버 message 가 더 구체적이라 그대로 노출
      setError(toUserMessage(toApiError(cause)))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title={`알람 해제 · #${alarm.id}`}
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
            disabled={submitting || !trimmed}
          >
            {submitting ? <Spinner /> : null}
            해제
          </button>
        </>
      }
    >
      <div className="alarm-form">
        <div className="alarm-summary">
          <span>
            <SeverityBadge severity={alarm.severity} />{' '}
            <strong>{alarm.equipmentCode ?? `#${alarm.equipmentId}`}</strong>
          </span>
          <span>{alarm.message}</span>
          <span className="mono">발생 {formatKst(alarm.occurredAt)} (KST)</span>
          {alarm.ackByName && <span>확인자 {alarm.ackByName}</span>}
        </div>

        <div className="field">
          <label htmlFor="alarm-resolve-note">해제 사유 (필수)</label>
          <textarea
            id="alarm-resolve-note"
            rows={4}
            value={note}
            maxLength={300}
            placeholder="조치 내용을 입력하세요. 예) 베어링 교체 후 진동 정상 확인"
            onChange={(event) => setNote(event.target.value)}
          />
          <span className="field-hint">해제 처리 시 사유가 이력으로 남습니다.</span>
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
