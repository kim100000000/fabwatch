import { useState } from 'react'
import type { FormEvent } from 'react'
import { toApiError } from '@/shared/api'
import { toUserMessage } from '@/shared/lib/errorMessage'
import { Modal, Spinner, StatusBadge } from '@/shared/ui'
import { changeEquipmentStatus } from '../api/equipmentApi'
import { ALLOWED_STATUS_TRANSITIONS } from '../types'
import type { EquipmentDetail, EquipmentStatus } from '../types'
import './equipment.css'

interface EquipmentStatusDialogProps {
  equipment: EquipmentDetail
  onClose: () => void
  /** 전환 성공 — 서버가 돌려준 최신 상세 */
  onChanged: (updated: EquipmentDetail) => void
}

/**
 * S-3 상태 변경 (ADMIN·ENGINEER — 노출 판단은 호출부에서 한다).
 * 후보는 docs/03 F-2 전이표로 좁혀 보여주고, 최종 검증은 서버(400 INVALID_STATUS_TRANSITION)가 한다.
 * ※ 상태 전환은 DB 필드 변경일 뿐 실제 설비를 정지시키는 물리 제어가 아니다 (docs/03 F-2).
 */
export function EquipmentStatusDialog({ equipment, onClose, onChanged }: EquipmentStatusDialogProps) {
  const candidates = ALLOWED_STATUS_TRANSITIONS[equipment.status] ?? []
  const [toStatus, setToStatus] = useState<EquipmentStatus | null>(null)
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!toStatus || submitting) return

    setSubmitting(true)
    setError(null)
    try {
      const updated = await changeEquipmentStatus(equipment.id, {
        toStatus,
        reason: reason.trim() === '' ? null : reason.trim(),
      })
      onChanged(updated)
    } catch (cause) {
      // INVALID_STATUS_TRANSITION 은 서버 message 에 허용 전이 목록이 담겨 있어 그대로 보여준다
      setError(toUserMessage(toApiError(cause)))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title="설비 상태 변경"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={submitting}>
            취소
          </button>
          <button
            type="submit"
            form="status-change-form"
            className="btn btn-primary"
            disabled={!toStatus || submitting}
          >
            {submitting && <Spinner />}
            {submitting ? '변경 중…' : '상태 변경'}
          </button>
        </>
      }
    >
      <form id="status-change-form" className="equipment-form" onSubmit={handleSubmit} noValidate>
        <div className="field">
          <label>현재 상태</label>
          <p className="field-readonly">
            <StatusBadge status={equipment.status} />
            <span className="field-hint">
              상태 전환은 이력(KPI 원천)에 기록됩니다. 실제 설비를 제어하지는 않습니다.
            </span>
          </p>
        </div>

        <div className="field">
          <label id="status-target-label">변경할 상태</label>
          {candidates.length === 0 ? (
            <p className="field-hint">현재 상태에서 전환할 수 있는 상태가 없습니다.</p>
          ) : (
            <div className="status-choice" role="group" aria-labelledby="status-target-label">
              {candidates.map((status) => (
                <button
                  key={status}
                  type="button"
                  data-status={status}
                  className={toStatus === status ? 'filter-chip active' : 'filter-chip'}
                  aria-pressed={toStatus === status}
                  onClick={() => setToStatus(status)}
                >
                  {status}
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="field">
          <label htmlFor="status-reason">사유</label>
          <textarea
            id="status-reason"
            value={reason}
            maxLength={200}
            rows={3}
            placeholder="선택 — 예: 정기 PM 착수"
            onChange={(event) => setReason(event.target.value)}
          />
        </div>

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
      </form>
    </Modal>
  )
}
