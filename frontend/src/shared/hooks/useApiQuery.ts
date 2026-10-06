import { useCallback, useEffect, useState } from 'react'
import axios from 'axios'
import { toApiError } from '@/shared/api'
import type { ApiError } from '@/shared/api'

export interface ApiQueryResult<T> {
  data: T | null
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

/**
 * 단순 조회용 공통 훅.
 * fetcher 는 반드시 useCallback 으로 메모이즈해서 넘긴다(무한 루프 방지).
 * 언마운트/의존성 변경 시 AbortController 로 요청을 취소한다.
 *
 * refreshMs 를 주면 그 주기로 조용히 재조회한다(로딩 표시 없음, 탭이 숨겨진 동안은 중지하고 복귀 시 즉시 1회).
 * 주기 갱신이 성공하면 error 를 해제하고, 실패하면 직전 data 를 유지한 채 error 만 올린다 —
 * 호출부는 error 를 우선 표시해 오래된 값을 최신처럼 보이지 않게 한다.
 */
export function useApiQuery<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  options?: { enabled?: boolean; refreshMs?: number },
): ApiQueryResult<T> {
  const enabled = options?.enabled ?? true
  const refreshMs = options?.refreshMs
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState<boolean>(enabled)
  const [error, setError] = useState<ApiError | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  const refetch = useCallback(() => setReloadKey((key) => key + 1), [])

  useEffect(() => {
    if (!enabled) {
      setLoading(false)
      return
    }

    const controller = new AbortController()
    let alive = true

    setLoading(true)
    setError(null)

    fetcher(controller.signal)
      .then((result) => {
        if (!alive) return
        setData(result)
        setLoading(false)
      })
      .catch((cause: unknown) => {
        if (!alive || axios.isCancel(cause)) return
        setError(toApiError(cause))
        setData(null)
        setLoading(false)
      })

    return () => {
      alive = false
      controller.abort()
    }
  }, [fetcher, enabled, reloadKey])

  // 주기 갱신 (옵션) — 첫 로딩/수동 refetch 와 별도 컨트롤러로 돌려 서로 취소하지 않는다
  useEffect(() => {
    if (!enabled || !refreshMs) return

    let alive = true
    let controller: AbortController | null = null

    const run = () => {
      if (document.hidden) return
      controller?.abort()
      controller = new AbortController()
      fetcher(controller.signal)
        .then((result) => {
          if (!alive) return
          setData(result)
          setError(null)
        })
        .catch((cause: unknown) => {
          if (!alive || axios.isCancel(cause)) return
          setError(toApiError(cause))
        })
    }

    const timer = window.setInterval(run, refreshMs)
    const onVisibility = () => {
      if (!document.hidden) run()
    }
    document.addEventListener('visibilitychange', onVisibility)

    return () => {
      alive = false
      controller?.abort()
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [fetcher, enabled, refreshMs])

  return { data, loading, error, refetch }
}
