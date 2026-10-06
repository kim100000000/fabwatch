import { apiClient } from '@/shared/api'
import type { UserLookupItem } from '../types'

/**
 * GET /users/lookup — 사용자 목록(id·이름·역할만, 이름순 단순 배열, 전체 로그인 역할 접근).
 * 목록 공통 래핑({content,...})이 아니라 단순 배열이 계약이다.
 */
export async function fetchUserLookup(signal?: AbortSignal): Promise<UserLookupItem[]> {
  const { data } = await apiClient.get<UserLookupItem[]>('/users/lookup', { signal })
  return data
}
