import { useEffect } from 'react'
import type { ReactNode } from 'react'
import './ui.css'

interface ModalProps {
  title: string
  onClose: () => void
  children: ReactNode
  /** 하단 버튼 영역 */
  footer?: ReactNode
  /** 폼·표가 들어가는 넓은 모달 */
  wide?: boolean
}

/** 공통 모달 — 오버레이 클릭/ESC 로 닫힌다 (docs/04 §4 담백한 다크 카드) */
export function Modal({ title, onClose, children, footer, wide = false }: ModalProps) {
  // ESC 로 닫기. 열려 있는 동안 배경 스크롤 잠금.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
    }
  }, [onClose])

  return (
    <div className="modal-overlay" onClick={onClose} role="presentation">
      <div
        className={wide ? 'modal-card modal-wide' : 'modal-card'}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="modal-head">
          <h2 className="modal-title">{title}</h2>
          <button type="button" className="modal-close" onClick={onClose} aria-label="닫기">
            ✕
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  )
}
