import axios, { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Mock } from 'vitest'
import { apiClient, onUnauthorized } from './client'
import { tokenStorage } from './tokenStorage'
import { ApiError } from './types'

/** 요청 설정에 대해 응답을 만들어 주는 가짜 어댑터 — 실제 네트워크를 쓰지 않는다 */
type Responder = (config: InternalAxiosRequestConfig) => { status: number; data?: unknown } | Error

function useAdapter(responder: Responder) {
  const calls: InternalAxiosRequestConfig[] = []
  apiClient.defaults.adapter = (config) => {
    calls.push(config)
    const result = responder(config)
    if (result instanceof Error) return Promise.reject(result)
    const response = { status: result.status, statusText: '', headers: {}, config, data: result.data ?? {} } as AxiosResponse
    if (result.status >= 400) {
      return Promise.reject(new AxiosError('failed', String(result.status), config, null, response))
    }
    return Promise.resolve(response)
  }
  return calls
}

const errorBody = (code: string) => ({ code, message: code, timestamp: '2026-01-01T00:00:00Z' })

function mockRefresh(result: 'ok' | 'rejected' | 'network' | 'server-error') {
  return vi.spyOn(axios, 'post').mockImplementation(() => {
    if (result === 'ok') {
      return Promise.resolve({ data: { accessToken: 'new-access', refreshToken: 'new-refresh' } })
    }
    if (result === 'network') return Promise.reject(new AxiosError('Network Error', 'ERR_NETWORK'))
    const status = result === 'rejected' ? 401 : 503
    const body = errorBody(result === 'rejected' ? 'INVALID_TOKEN' : 'INTERNAL_ERROR')
    const response = { status, statusText: '', headers: {}, config: {}, data: body } as AxiosResponse
    return Promise.reject(new AxiosError('failed', String(status), undefined, null, response))
  })
}

/** 실행 환경(Node 버전)에 따라 jsdom 의 localStorage 가 동작하지 않을 수 있어 메모리 구현으로 고정한다 */
function stubLocalStorage() {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
    clear: () => store.clear(),
  })
}

describe('API 인터셉터 (401 리프레시)', () => {
  let unauthorized: Mock<() => void>
  let off: () => void

  beforeEach(() => {
    stubLocalStorage()
    tokenStorage.setTokens('old-access', 'old-refresh')
    unauthorized = vi.fn<() => void>()
    off = onUnauthorized(unauthorized)
  })

  afterEach(() => {
    off()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    tokenStorage.clear()
  })

  it('리프레시 성공 후 재시도가 400 이면 강제 로그아웃하지 않고 그 오류를 그대로 전달한다 (H1 회귀)', async () => {
    mockRefresh('ok')
    let first = true
    useAdapter(() => {
      if (first) {
        first = false
        return { status: 401, data: errorBody('TOKEN_EXPIRED') }
      }
      return { status: 400, data: errorBody('VALIDATION_ERROR') }
    })

    const rejection = await apiClient.post('/inspections', {}).catch((error: unknown) => error)

    expect(rejection).toBeInstanceOf(ApiError)
    expect((rejection as ApiError).code).toBe('VALIDATION_ERROR')
    expect((rejection as ApiError).status).toBe(400)
    expect(unauthorized).not.toHaveBeenCalled()
    expect(tokenStorage.getRefreshToken()).toBe('new-refresh')
  })

  it('리프레시 성공 후 재시도가 또 401 이면 그때만 로그아웃한다', async () => {
    mockRefresh('ok')
    useAdapter(() => ({ status: 401, data: errorBody('TOKEN_EXPIRED') }))

    await expect(apiClient.get('/equipments')).rejects.toBeInstanceOf(ApiError)

    expect(unauthorized).toHaveBeenCalledTimes(1)
    expect(tokenStorage.getRefreshToken()).toBeNull()
  })

  it('서버가 refresh 를 401 로 거부했을 때만 로그아웃하고 저장된 refresh 를 파기한다', async () => {
    mockRefresh('rejected')
    useAdapter(() => ({ status: 401, data: errorBody('TOKEN_EXPIRED') }))

    await expect(apiClient.get('/equipments')).rejects.toBeInstanceOf(ApiError)

    expect(unauthorized).toHaveBeenCalledTimes(1)
    expect(tokenStorage.getRefreshToken()).toBeNull()
  })

  it.each(['network', 'server-error'] as const)(
    '리프레시가 일시 장애(%s)로 실패하면 세션을 파기하지 않는다',
    async (kind) => {
      mockRefresh(kind)
      useAdapter(() => ({ status: 401, data: errorBody('TOKEN_EXPIRED') }))

      await expect(apiClient.get('/equipments')).rejects.toBeInstanceOf(ApiError)

      expect(unauthorized).not.toHaveBeenCalled()
      expect(tokenStorage.getRefreshToken()).toBe('old-refresh')
    },
  )

  it('동시에 여러 요청이 401 을 받아도 리프레시는 1회만 호출한다 (single-flight)', async () => {
    const refresh = mockRefresh('ok')
    const seen = new Set<InternalAxiosRequestConfig>()
    useAdapter((config) => {
      // 요청마다 첫 시도만 401, 재시도(새 토큰)는 성공
      const token = config.headers.get('Authorization')
      if (token === 'Bearer new-access') return { status: 200, data: { ok: true } }
      seen.add(config)
      return { status: 401, data: errorBody('TOKEN_EXPIRED') }
    })

    const results = await Promise.all([
      apiClient.get('/equipments'),
      apiClient.get('/alarms'),
      apiClient.get('/lines'),
    ])

    expect(results.every((response) => response.status === 200)).toBe(true)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(unauthorized).not.toHaveBeenCalled()
  })
})
