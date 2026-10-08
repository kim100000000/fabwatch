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
  // 연결은 열려 있어도 이벤트가 끊겼으면 '지연'으로 보여 준다(배지가 초록인 채 낡은 값이 보이는 것을 막는다)
  const stale = state.mode === 'SSE' && state.stale
  const mode = stale ? 'POLLING' : state.mode
  const label = stale ? '지연 (폴링 보강)' : MODE_LABEL[state.mode]
  return (
    <span className="stream-badge" data-mode={mode} title={label} role="status">
      <span className="stream-dot" aria-hidden="true" />
      {label}
    </span>
  )
}
