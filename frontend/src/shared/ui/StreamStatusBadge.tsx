import type { SseState } from '@/shared/api'
import './ui.css'

interface StreamStatusBadgeProps {
  state: SseState
}

const MODE_LABEL: Record<SseState['mode'], string> = {
  SSE: '실시간 (SSE)',
  POLLING: '폴백 (3초 폴링)',
  CONNECTING: '연결 중…',
  IDLE: '미연결',
}

/**
 * 실시간 연결 상태 표시.
 * SSE 가 막혀 폴링으로 내려갔는지 화면에서도 바로 보이게 한다 (콘솔 로그와 함께 디버깅 단서).
 */
export function StreamStatusBadge({ state }: StreamStatusBadgeProps) {
  return (
    <span className="stream-badge" data-mode={state.mode} title={MODE_LABEL[state.mode]}>
      <span className="stream-dot" aria-hidden="true" />
      {MODE_LABEL[state.mode]}
    </span>
  )
}
