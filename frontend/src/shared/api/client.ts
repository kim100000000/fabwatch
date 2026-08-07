import axios from 'axios'
import type { AxiosError, AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import { tokenStorage } from './tokenStorage'
import { ApiError, CLIENT_ERROR_CODE } from './types'
import type { ApiErrorBody, AuthUserSummary } from './types'

export const API_BASE_URL: string =
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1'

/** 인증 없이 호출되는 엔드포인트 (docs/11 P-4) — 401 리프레시 대상에서 제외 */
const PUBLIC_PATHS = ['/auth/login', '/auth/refresh']

/** 재시도 여부 표시용 플래그를 붙인 요청 설정 */
type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean }

/** 단일 axios 인스턴스 — 다른 곳에서 새 인스턴스를 만들지 않는다 */
export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15_000,
  headers: { 'Content-Type': 'application/json' },
})

/* ------------------------------------------------------------------
 * 세션 만료 구독 — AuthProvider 가 구독해 로그인 화면으로 보낸다.
 * ------------------------------------------------------------------ */
type UnauthorizedListener = () => void
const unauthorizedListeners = new Set<UnauthorizedListener>()

export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener)
  return () => {
    unauthorizedListeners.delete(listener)
  }
}

function notifyUnauthorized(): void {
  tokenStorage.clear()
  unauthorizedListeners.forEach((listener) => listener())
}

/* ------------------------------------------------------------------
 * 요청 인터셉터: Authorization 헤더 자동 첨부
 * ------------------------------------------------------------------ */
apiClient.interceptors.request.use((config) => {
  const token = tokenStorage.getAccessToken()
  const isPublic = PUBLIC_PATHS.some((path) => config.url?.startsWith(path))
  if (token && !isPublic) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

/* ------------------------------------------------------------------
 * 토큰 리프레시 (docs/11 §2 — Refresh 회전)
 * 동시에 여러 요청이 401을 받아도 리프레시는 1회만 수행하고 결과를 공유한다.
 * ------------------------------------------------------------------ */
/**
 * POST /auth/refresh 응답 — 로그인과 동일한 shape 이다.
 * 사용자 정보(user)가 함께 오므로 JWT 클레임을 디코딩할 필요가 없다 (docs/06 §1).
 */
export interface RefreshResponse {
  accessToken: string
  refreshToken: string
  accessTokenExpiresIn?: number
  user?: AuthUserSummary
}

/** 리프레시 결과 — 인터셉터는 accessToken 만, 세션 복구는 user 까지 사용한다 */
export interface RefreshResult {
  accessToken: string
  user: AuthUserSummary | null
}

let refreshPromise: Promise<RefreshResult> | null = null

async function requestNewTokens(): Promise<RefreshResult> {
  const refreshToken = tokenStorage.getRefreshToken()
  if (!refreshToken) {
    throw new ApiError('TOKEN_EXPIRED', '세션이 만료되었습니다. 다시 로그인해 주세요.', 401, new Date().toISOString())
  }

  // 인터셉터 재귀를 피하기 위해 인스턴스가 아닌 기본 axios 로 호출한다.
  const { data } = await axios.post<RefreshResponse>(
    `${API_BASE_URL}/auth/refresh`,
    { refreshToken },
    { headers: { 'Content-Type': 'application/json' }, timeout: 15_000 },
  )

  tokenStorage.setTokens(data.accessToken, data.refreshToken ?? refreshToken)
  return { accessToken: data.accessToken, user: data.user ?? null }
}

function refreshAccessToken(): Promise<RefreshResult> {
  if (!refreshPromise) {
    refreshPromise = requestNewTokens().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

/* ------------------------------------------------------------------
 * 응답 인터셉터: 401 → 리프레시 1회 재시도, 그 외에는 ApiError 로 정규화
 * ------------------------------------------------------------------ */
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiErrorBody>) => {
    const config = error.config as RetriableConfig | undefined
    const status = error.response?.status
    const isPublic = PUBLIC_PATHS.some((path) => config?.url?.startsWith(path))

    if (status === 401 && config && !config._retried && !isPublic) {
      config._retried = true
      try {
        const { accessToken } = await refreshAccessToken()
        config.headers.set('Authorization', `Bearer ${accessToken}`)
        return await apiClient.request(config as AxiosRequestConfig)
      } catch {
        notifyUnauthorized()
        return Promise.reject(toApiError(error))
      }
    }

    return Promise.reject(toApiError(error))
  },
)

/** axios 에러를 화면에서 다루는 ApiError 로 변환 (code 기준 분기용) */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }

  if (axios.isAxiosError<ApiErrorBody>(error)) {
    const body = error.response?.data
    if (body?.code) {
      return new ApiError(body.code, body.message, error.response?.status ?? 0, body.timestamp)
    }
    return new ApiError(
      error.response ? CLIENT_ERROR_CODE.UNKNOWN : CLIENT_ERROR_CODE.NETWORK_ERROR,
      error.message,
      error.response?.status ?? 0,
      new Date().toISOString(),
    )
  }

  return new ApiError(
    CLIENT_ERROR_CODE.UNKNOWN,
    error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.',
    0,
    new Date().toISOString(),
  )
}

/** 세션 종료 시 토큰 정리 + 구독자 통지 (로그아웃/리프레시 실패 공통) */
export function clearSession(): void {
  notifyUnauthorized()
}

/**
 * 새로고침 후 세션 복구용 — 저장된 Refresh 로 Access 를 재발급한다.
 * 응답의 user 를 그대로 돌려주므로 호출부가 토큰을 직접 파싱하지 않는다.
 */
export function restoreSession(): Promise<RefreshResult> {
  return refreshAccessToken()
}
