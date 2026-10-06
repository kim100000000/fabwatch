import { useCallback } from 'react'
import type { ApiError } from '@/shared/api'
import { useApiQuery } from '@/shared/hooks/useApiQuery'
import { fetchUserLookup } from './userApi'
import type { UserLookupItem } from '../types'

export interface UserLookupResult {
  users: UserLookupItem[]
  loading: boolean
  /** 조회 실패 — 호출부는 해당 선택 UI 만 비활성화하고 나머지 기능은 유지한다 */
  error: ApiError | null
  refetch: () => void
}

/** GET /users/lookup 훅 (작업자 필터용) */
export function useUserLookup(): UserLookupResult {
  const fetcher = useCallback((signal: AbortSignal) => fetchUserLookup(signal), [])
  const { data, loading, error, refetch } = useApiQuery(fetcher)
  return { users: data ?? [], loading, error, refetch }
}
