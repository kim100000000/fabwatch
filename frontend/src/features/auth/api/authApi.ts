import { apiClient, tokenStorage } from '@/shared/api'
import type { LoginRequest, LoginResponse } from '../types'

/**
 * POST /auth/login (docs/06 §1)
 * 성공 시 토큰 쌍을 저장소에 반영한다.
 * 에러: 401 LOGIN_FAILED / 429 ACCOUNT_LOCKED / 403 USER_DISABLED
 */
export async function login(payload: LoginRequest): Promise<LoginResponse> {
  const { data } = await apiClient.post<LoginResponse>('/auth/login', payload)
  tokenStorage.setTokens(data.accessToken, data.refreshToken)
  return data
}

/**
 * POST /auth/logout — 서버의 Refresh 를 무효화한다.
 * 요청 바디는 없다. 백엔드는 Authorization 헤더의 사용자로 대상 Refresh 를 찾는다.
 * 서버 호출이 실패해도 로컬 토큰은 반드시 정리한다.
 */
export async function logout(): Promise<void> {
  try {
    await apiClient.post('/auth/logout')
  } finally {
    tokenStorage.clear()
  }
}
