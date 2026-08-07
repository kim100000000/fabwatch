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
 */
export function useApiQuery<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  options?: { enabled?: boolean },
): ApiQueryResult<T> {
  const enabled = options?.enabled ?? true
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

  return { data, loading, error, refetch }
}
