import { errorCodeOf, toUserMessage } from '@/shared/lib/errorMessage'
import './ui.css'

interface ErrorStateProps {
  error: unknown
  onRetry?: () => void
}

/** 조회 실패 표시 — 메시지는 code 기준 매핑에서 가져온다 */
export function ErrorState({ error, onRetry }: ErrorStateProps) {
  return (
    <div className="error-state" role="alert">
      <span>{toUserMessage(error)}</span>
      <span className="error-code mono">{errorCodeOf(error)}</span>
      {onRetry && (
        <button type="button" className="btn btn-ghost" onClick={onRetry}>
          다시 시도
        </button>
      )}
    </div>
  )
}
