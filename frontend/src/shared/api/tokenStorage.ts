/**
 * 토큰 저장소 (docs/11_보안명세서.md §2)
 * - Access Token: 메모리(모듈 변수) 보관 — 번들 밖 저장소에 남기지 않아 XSS 노출 창을 줄인다.
 * - Refresh Token: 새로고침 세션 유지를 위해 localStorage 만 사용.
 */

const REFRESH_TOKEN_KEY = 'fabwatch.refreshToken'

let accessToken: string | null = null

export const tokenStorage = {
  getAccessToken(): string | null {
    return accessToken
  },

  setAccessToken(token: string | null): void {
    accessToken = token
  },

  getRefreshToken(): string | null {
    try {
      return localStorage.getItem(REFRESH_TOKEN_KEY)
    } catch {
      // 프라이빗 모드 등 localStorage 접근 불가 환경 — 메모리 세션으로만 동작
      return null
    }
  },

  setRefreshToken(token: string | null): void {
    try {
      if (token) {
        localStorage.setItem(REFRESH_TOKEN_KEY, token)
      } else {
        localStorage.removeItem(REFRESH_TOKEN_KEY)
      }
    } catch {
      // 저장 실패는 무시 (세션이 새로고침에서 유지되지 않을 뿐)
    }
  },

  /** 로그인 성공 / 토큰 회전 시 토큰 쌍을 한 번에 갱신 */
  setTokens(access: string, refresh: string | null): void {
    accessToken = access
    if (refresh) {
      tokenStorage.setRefreshToken(refresh)
    }
  },

  clear(): void {
    accessToken = null
    tokenStorage.setRefreshToken(null)
  },
}
