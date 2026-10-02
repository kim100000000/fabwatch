import type { ReactNode } from 'react'
import { Modal } from './Modal'
import { Spinner } from './Spinner'
import './ui.css'

interface ConfirmDialogProps {
  title: string
  message: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  /** 처리 중 — 이중 제출을 막고 스피너를 보여준다 */
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/** 확인/취소 2버튼 다이얼로그 (확정·이탈 경고 등 되돌릴 수 없는 동작 앞에서 사용) */
export function ConfirmDialog({
  title,
  message,
  confirmLabel = '확인',
  cancelLabel = '취소',
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <Modal
      title={title}
      onClose={busy ? () => undefined : onCancel}
      footer={
        <>
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onCancel}>
            {cancelLabel}
          </button>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={onConfirm}>
            {busy && <Spinner />}
            {confirmLabel}
          </button>
        </>
      }
    >
      <div className="confirm-message">{message}</div>
    </Modal>
  )
}
