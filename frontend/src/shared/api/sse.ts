import { useEffect, useRef, useState } from 'react'
import { API_BASE_URL } from './client'
import { tokenStorage } from './tokenStorage'

/**
 * SSE 공통 훅 (docs/06 §3 `GET /stream/sensors`, docs/11 §4 토큰 쿼리파라미터)
 *
 * - EventSource 는 커스텀 헤더를 못 붙이므로 액세스 토큰을 `?token=` 으로 전달한다
 *   (SSE 엔드포인트 한정 예외 — docs/11 §4).
 * - 연결 실패 / 브라우저 미지원 시 3초 폴링으로 자동 폴백하고, 폴백 여부를 콘솔에 남긴다.
 * - 폴백 중에도 주기적으로 재연결을 시도해 백엔드가 나중에 떠도 SSE 로 복귀한다.
 * - 언마운트 시 EventSource.close() + 폴링 인터벌 clear + 진행 중 폴링 요청 abort.
 *
 * 이 훅은 페이로드 타입을 모른다(shared 는 features 를 참조할 수 없음).
 * 도메인별 타입 부여는 features/sensor 의 useSensorStream 이 담당한다.
 */

export type SseMode = 'IDLE' | 'CONNECTING' | 'SSE' | 'POLLING'

/** 파싱된 event payload 를 받는 핸들러 */
export type SseEventHandler = (data: unknown) => void

export interface UseSseOptions {
  /** API_BASE_URL 기준 경로 (예: '/stream/sensors') */
  path: string
  /** 쿼리 파라미터 — null/undefined/'' 는 제외된다 (token 은 훅이 직접 붙인다) */
  params?: Record<string, string | number | null | undefined>
  /** event 이름 → 핸들러. 예: { sensor, alarm, status } */
  events: Record<string, SseEventHandler>
  enabled?: boolean
  /** SSE 불가 시 주기적으로 호출되는 폴백 로더 */
  onPoll?: (signal: AbortSignal) => Promise<void> | void
  /** 폴백 폴링 주기 (기본 3초 — CLAUDE.md 우회 규칙) */
  pollIntervalMs?: number
  /** 폴백 상태에서 SSE 재연결을 시도하는 주기 (기본 30초) */
  retryIntervalMs?: number
}

export interface SseState {
  mode: SseMode
  /** SSE 로 실시간 수신 중인지 (false 면 폴링 폴백이거나 미연결) */
  connected: boolean
  /** 마지막 이벤트 수신 시각 (ms epoch) — 화면 상단 "실시간" 표시에 사용 */
  lastEventAt: number | null
}

/** null/undefined/빈 문자열을 제외한 쿼리 문자열을 만든다 ('' 또는 '?a=1' 형태) */
function buildQuery(params?: Record<string, string | number | null | undefined>): string {
  if (!params) return ''
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === null || value === undefined || value === '') return
    search.set(key, String(value))
  })
  const query = search.toString()
  return query ? `?${query}` : ''
}

export function useSse(options: UseSseOptions): SseState {
  const {
    path,
    params,
    events,
    enabled = true,
    onPoll,
    pollIntervalMs = 3_000,
    retryIntervalMs = 30_000,
  } = options

  const [mode, setMode] = useState<SseMode>('IDLE')
  const [lastEventAt, setLastEventAt] = useState<number | null>(null)
  // 센서 이벤트는 2초 주기로 설비×센서 수만큼 쏟아진다 —
  // 표시용 lastEventAt 때문에 매 이벤트마다 리렌더가 나지 않도록 1초로 throttle 한다.
  const lastEventRef = useRef<number>(0)

  // 핸들러/폴러는 매 렌더 새 함수여도 재연결이 일어나지 않도록 ref 로 보관한다.
  const handlersRef = useRef(events)
  const pollRef = useRef(onPoll)
  useEffect(() => {
    handlersRef.current = events
    pollRef.current = onPoll
  })

  const query = buildQuery(params)
  // 구독할 event 이름이 바뀔 때만 재연결하도록 정렬된 키를 의존성으로 쓴다.
  const eventNames = Object.keys(events).sort().join(',')

  useEffect(() => {
    if (!enabled) {
      setMode('IDLE')
      return
    }

    let disposed = false
    let source: EventSource | null = null
    let pollTimer: ReturnType<typeof setInterval> | null = null
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let pollController: AbortController | null = null

    const clearRetry = () => {
      if (retryTimer) {
        clearTimeout(retryTimer)
        retryTimer = null
      }
    }

    const stopPolling = () => {
      if (pollTimer) {
        clearInterval(pollTimer)
        pollTimer = null
      }
      pollController?.abort()
      pollController = null
    }

    const closeSource = () => {
      if (source) {
        source.close()
        source = null
      }
    }

    const runPollOnce = () => {
      const poll = pollRef.current
      if (!poll || disposed) return
      pollController?.abort()
      const controller = new AbortController()
      pollController = controller
      void (async () => {
        try {
          await poll(controller.signal)
        } catch {
          // 폴백 폴링 실패는 다음 주기에 자연히 재시도된다 (화면 에러로 승격하지 않음)
        }
      })()
    }

    const startPolling = (reason: string) => {
      if (disposed || pollTimer) return
      setMode('POLLING')
      console.warn(
        `[SSE] ${path} 연결 불가 → ${pollIntervalMs}ms 폴링 폴백으로 전환합니다 (사유: ${reason})`,
      )
      runPollOnce()
      pollTimer = setInterval(runPollOnce, pollIntervalMs)
    }

    const scheduleRetry = (delayMs: number = retryIntervalMs) => {
      if (disposed || retryTimer) return
      retryTimer = setTimeout(() => {
        retryTimer = null
        connect()
      }, delayMs)
    }

    function connect(): void {
      if (disposed) return

      if (typeof EventSource === 'undefined') {
        // 미지원 브라우저는 재연결 시도 자체가 무의미하므로 폴링으로 고정한다.
        startPolling('브라우저가 EventSource 를 지원하지 않음')
        return
      }

      const token = tokenStorage.getAccessToken()
      if (!token) {
        // 세션 복구(리프레시) 직전일 수 있다 — 폴링으로 버티다가 토큰이 생기면 곧바로 재연결한다.
        startPolling('액세스 토큰 없음(세션 복구 대기)')
        scheduleRetry(pollIntervalMs)
        return
      }

      setMode((previous) => (previous === 'POLLING' ? previous : 'CONNECTING'))

      const url = `${API_BASE_URL}${path}${query ? `${query}&` : '?'}token=${encodeURIComponent(token)}`
      const eventSource = new EventSource(url)
      source = eventSource

      eventSource.onopen = () => {
        if (disposed) return
        stopPolling()
        clearRetry()
        setMode('SSE')
        console.info(`[SSE] ${path} 연결됨 — 실시간 수신 시작`)
      }

      eventSource.onerror = () => {
        if (disposed) return
        // 브라우저 자동 재연결에 맡기지 않고 직접 닫은 뒤 폴백/재시도를 제어한다.
        closeSource()
        startPolling('SSE 연결 오류(백엔드 미기동 또는 네트워크 장애)')
        scheduleRetry()
      }

      eventNames
        .split(',')
        .filter(Boolean)
        .forEach((name) => {
          eventSource.addEventListener(name, (event) => {
            if (disposed) return
            const handler = handlersRef.current[name]
            if (!handler) return
            const raw = (event as MessageEvent<string>).data
            try {
              handler(JSON.parse(raw))
              const now = Date.now()
              if (now - lastEventRef.current >= 1_000) {
                lastEventRef.current = now
                setLastEventAt(now)
              }
            } catch {
              console.warn(`[SSE] ${path} event=${name} 페이로드 파싱 실패`, raw)
            }
          })
        })
    }

    connect()

    return () => {
      disposed = true
      closeSource()
      stopPolling()
      clearRetry()
    }
  }, [path, query, eventNames, enabled, pollIntervalMs, retryIntervalMs])

  return { mode, connected: mode === 'SSE', lastEventAt }
}
