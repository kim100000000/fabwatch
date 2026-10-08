import axios from 'axios'
import type { AxiosError, AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import { tokenStorage } from './tokenStorage'
import { ApiError, CLIENT_ERROR_CODE } from './types'
import type { ApiErrorBody, AuthUserSummary } from './types'

/**
 * API 베이스 URL.
 * - 개발 모드: VITE_API_BASE_URL 이 없으면 로컬 백엔드(localhost:8080)로 폴백한다.
 * - 프로덕션 빌드: localhost 번들이 배포되는 사고를 막기 위해 폴백하지 않는다.
 *   env 가 없으면 같은 도메인의 상대경로 `/api/v1`(리버스 프록시 구성)을 사용한다.
 *   (빌드 시점 검사는 vite.config.ts 에서 수행한다)
 */
export const API_BASE_URL: string =
  import.meta.env.VITE_API_BASE_URL ?? (import.meta.env.DEV ? 'http://localhost:8080/api/v1' : '/api/v1')

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
  let data: RefreshResponse
  try {
    ;({ data } = await axios.post<RefreshResponse>(
      `${API_BASE_URL}/auth/refresh`,
      { refreshToken },
      { headers: { 'Content-Type': 'application/json' }, timeout: 15_000 },
    ))
  } catch (cause) {
    // 서버 응답 코드(code)와 상태를 보존한 ApiError 로 정규화해 호출부가 분기하게 한다.
    throw toApiError(cause)
  }

  tokenStorage.setTokens(data.accessToken, data.refreshToken ?? refreshToken)
  return { accessToken: data.accessToken, user: data.user ?? null }
}

/**
 * 서버가 refresh 토큰을 무효로 판정했는지(401/403) 여부.
 * 네트워크 장애·타임아웃·5xx 는 false — 이때는 저장된 refresh 를 파기하면 안 된다.
 */
export function isRefreshRejected(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403)
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

    if (status === 401 && config && !isPublic) {
      // 재시도한 요청이 또 401 이면 갱신한 토큰도 거부된 것이므로 세션 만료로 확정한다.
      if (config._retried) {
        notifyUnauthorized()
        return Promise.reject(toApiError(error))
      }

      config._retried = true

      // 1) 리프레시 호출 — 실패 원인에 따라 세션 파기 여부가 갈린다.
      let accessToken: string
      try {
        ;({ accessToken } = await refreshAccessToken())
      } catch (refreshError) {
        if (isRefreshRejected(refreshError)) {
          // 서버가 401/403 으로 refresh 무효를 알렸다 → 세션 종료
          notifyUnauthorized()
          return Promise.reject(toApiError(error))
        }
        // 일시적 네트워크 장애/5xx — 저장된 refresh 를 파기하지 않고 원인 오류를 그대로 전달한다.
        return Promise.reject(toApiError(refreshError))
      }

      // 2) 원래 요청 재시도 — 실패해도 세션과 무관하므로 그 오류를 그대로 reject 한다.
      //    (재시도에서 다시 401 이면 위 분기에서 로그아웃 처리된다)
      config.headers.set('Authorization', `Bearer ${accessToken}`)
      return apiClient.request(config as AxiosRequestConfig)
    }

    return Promise.reject(toApiError(error))
  },
)

/** Retry-After 헤더(초 단위 정수)를 숫자로 — 날짜 형식 등 해석 불가면 undefined */
function parseRetryAfter(value: unknown): number | undefined {
  if (typeof value !== 'string' && typeof value !== 'number') return undefined
  const seconds = Number(value)
  return Number.isFinite(seconds) && seconds >= 0 ? Math.ceil(seconds) : undefined
}

/** axios 에러를 화면에서 다루는 ApiError 로 변환 (code 기준 분기용) */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }

  if (axios.isAxiosError<ApiErrorBody>(error)) {
    const body = error.response?.data
    if (body?.code) {
      return new ApiError(
        body.code,
        body.message,
        error.response?.status ?? 0,
        body.timestamp,
        parseRetryAfter(error.response?.headers?.['retry-after']),
      )
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
