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
 * POST /auth/logout — 현재 기기의 Refresh 세션만 서버에서 무효화한다 (docs/06 §1).
 * 본문의 refreshToken 으로 대상 세션을 지정한다(생략하면 서버가 본인의 모든 세션을 폐기하므로
 * 저장된 값이 있으면 항상 보낸다). 서버 호출이 실패해도 로컬 토큰은 반드시 정리한다.
 */
export async function logout(): Promise<void> {
  const refreshToken = tokenStorage.getRefreshToken()
  try {
    await apiClient.post('/auth/logout', refreshToken ? { refreshToken } : undefined)
  } finally {
    tokenStorage.clear()
  }
}
