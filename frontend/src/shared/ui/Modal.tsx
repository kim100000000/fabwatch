import { useEffect, useRef } from 'react'
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
  /** 제출 중 등 — true 면 ESC/오버레이/닫기 버튼으로 닫히지 않는다(이중 제출·결과 유실 방지) */
  busy?: boolean
}

/** 공통 모달 — 오버레이 클릭/ESC 로 닫힌다 (docs/04 §4 담백한 다크 카드) */
export function Modal({ title, onClose, children, footer, wide = false, busy = false }: ModalProps) {
  const cardRef = useRef<HTMLDivElement>(null)
  const busyRef = useRef(busy)
  busyRef.current = busy
  const requestClose = () => {
    if (!busyRef.current) onClose()
  }

  // 접근성: 열릴 때 첫 입력(없으면 첫 포커스 가능 요소)으로 포커스 이동, Tab 이 모달 밖으로 나가지 않게 순환,
  // 닫힐 때 열기 전 포커스 위치로 복귀.
  useEffect(() => {
    const card = cardRef.current
    if (!card) return
    const previouslyFocused = document.activeElement as HTMLElement | null
    const focusables = () =>
      Array.from(
        card.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((element) => element.offsetParent !== null)
    const firstField = card.querySelector<HTMLElement>(
      '.modal-body input:not([disabled]), .modal-body textarea:not([disabled]), .modal-body select:not([disabled])',
    )
    ;(firstField ?? focusables()[0] ?? card).focus()

    const onTab = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return
      const items = focusables()
      if (items.length === 0) {
        event.preventDefault()
        return
      }
      const first = items[0]
      const last = items[items.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    card.addEventListener('keydown', onTab)
    return () => {
      card.removeEventListener('keydown', onTab)
      previouslyFocused?.focus?.()
    }
  }, [])

  // ESC 로 닫기. 열려 있는 동안 배경 스크롤 잠금.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busyRef.current) onClose()
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
    <div className="modal-overlay" onClick={requestClose} role="presentation">
      <div
        ref={cardRef}
        tabIndex={-1}
        className={wide ? 'modal-card modal-wide' : 'modal-card'}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        aria-busy={busy || undefined}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="modal-head">
          <h2 className="modal-title">{title}</h2>
          <button type="button" className="modal-close" onClick={requestClose} aria-label="닫기" disabled={busy}>
            ✕
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  )
}
