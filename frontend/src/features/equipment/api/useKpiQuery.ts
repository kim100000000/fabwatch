import { useCallback, useEffect, useState } from 'react'
import axios from 'axios'
import { toApiError } from '@/shared/api'
import type { ApiError } from '@/shared/api'

export interface KpiQueryResult<T> {
  data: T | null
  /** 첫 로딩 또는 기간/필터 변경 직후 — 이전 값은 비워지고 로딩 표시만 보인다 */
  loading: boolean
  error: ApiError | null
  refetch: () => void
}

interface KpiQueryOptions {
  /** 주면 이 주기로 백그라운드 갱신한다 (탭이 숨겨져 있으면 건너뜀) */
  refreshMs?: number
}

/**
 * KPI 조회 공통 훅.
 * - fetcher 가 바뀌면(기간·라인 변경) 이전 값을 비우고 로딩으로 돌아간다 → 다른 기간 값이 남아 보이는 깜빡임 방지.
 * - 주기 갱신은 백그라운드로 조용히 한다(로딩 표시 없음). 갱신 실패 시 직전 값을 유지하고 오류는 띄우지 않는다.
 * - 탭이 숨겨진 동안은 갱신하지 않고, 다시 보이면 즉시 한 번 갱신한다.
 * fetcher 는 useCallback 으로 메모이즈해서 넘긴다.
 */
export function useKpiQuery<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  options: KpiQueryOptions = {},
): KpiQueryResult<T> {
  const { refreshMs } = options
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<ApiError | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  const refetch = useCallback(() => setReloadKey((key) => key + 1), [])

  useEffect(() => {
    let alive = true
    let controller = new AbortController()

    /** 첫 로딩/재시도: 값을 비우고 로딩 표시 */
    const loadInitial = () => {
      setLoading(true)
      setError(null)
      setData(null)
      fetcher(controller.signal)
        .then((result) => {
          if (!alive) return
          setData(result)
          setLoading(false)
        })
        .catch((cause: unknown) => {
          if (!alive || axios.isCancel(cause)) return
          setError(toApiError(cause))
          setLoading(false)
        })
    }

    /** 주기 갱신: 조용히 값만 교체, 실패는 무시 */
    const loadBackground = () => {
      controller.abort()
      controller = new AbortController()
      fetcher(controller.signal)
        .then((result) => {
          if (!alive) return
          setData(result)
          setError(null)
          // 첫 로딩 요청이 주기 갱신/탭 복귀로 abort된 경우 loading이 영구 true로 남지 않게 한다
          setLoading(false)
        })
        .catch(() => {
          // 직전 값 유지 — 다음 주기에 재시도
        })
    }

    loadInitial()

    let timer: number | undefined
    const onVisibility = () => {
      if (!document.hidden) loadBackground()
    }
    if (refreshMs) {
      timer = window.setInterval(() => {
        if (!document.hidden) loadBackground()
      }, refreshMs)
      document.addEventListener('visibilitychange', onVisibility)
    }

    return () => {
      alive = false
      controller.abort()
      if (timer !== undefined) window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [fetcher, refreshMs, reloadKey])

  return { data, loading, error, refetch }
}
