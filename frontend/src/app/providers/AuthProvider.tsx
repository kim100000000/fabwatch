import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { login as loginRequest, logout as logoutRequest } from '@/features/auth'
import type { AuthUser, LoginRequest, UserRole } from '@/features/auth'
import { isRefreshRejected, onUnauthorized, restoreSession, tokenStorage } from '@/shared/api'
import type { AuthUserSummary } from '@/shared/api'
import { AuthContext } from './AuthContext'
import type { AuthContextValue, AuthStatus, SessionNotice } from './AuthContext'

/**
 * 전역 인증 상태.
 * - Access 는 메모리, Refresh 는 localStorage (docs/11 §2)
 * - 새로고침 시 저장된 Refresh 로 세션 복구 시도
 * - 인터셉터의 리프레시가 최종 실패하면 onUnauthorized 로 통지받아 세션을 비운다
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading')
  const [user, setUser] = useState<AuthUser | null>(null)
  const [sessionNotice, setSessionNotice] = useState<SessionNotice>(null)

  // 세션 복구: Refresh 가 있으면 Access 재발급 → 응답의 user 로 사용자 정보 복원
  useEffect(() => {
    let alive = true

    if (!tokenStorage.getRefreshToken()) {
      setStatus('anonymous')
      return
    }

    restoreSession()
      .then(({ user: restoredUser }) => {
        if (!alive) return
        const restored = toAuthUser(restoredUser)
        setUser(restored)
        setStatus(restored ? 'authenticated' : 'anonymous')
        if (!restored) {
          tokenStorage.clear()
        }
      })
      .catch((cause: unknown) => {
        if (!alive) return
        setUser(null)
        setStatus('anonymous')
        if (isRefreshRejected(cause)) {
          // 서버가 401/403 으로 refresh 무효를 알린 경우에만 저장된 세션을 파기한다.
          tokenStorage.clear()
        } else {
          // 네트워크 장애/5xx — 저장된 refresh 는 유지(새로고침하면 복구 재시도 가능)
          setSessionNotice('restore-failed')
        }
      })

    return () => {
      alive = false
    }
  }, [])

  // 인터셉터에서 세션 만료가 확정되면 상태를 비운다
  useEffect(() => onUnauthorized(() => {
    setUser(null)
    setStatus('anonymous')
    setSessionNotice('expired')
  }), [])

  const clearSessionNotice = useCallback(() => setSessionNotice(null), [])

  const login = useCallback(async (payload: LoginRequest) => {
    const response = await loginRequest(payload)
    setUser(response.user)
    setSessionNotice(null)
    setStatus('authenticated')
    return response.user
  }, [])

  const logout = useCallback(async () => {
    try {
      await logoutRequest()
    } finally {
      setUser(null)
      setStatus('anonymous')
    }
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, sessionNotice, clearSessionNotice, login, logout }),
    [status, user, sessionNotice, clearSessionNotice, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

/**
 * 리프레시 응답의 user( id/name/role )를 화면 타입으로 좁힌다.
 * 백엔드 refresh 응답은 로그인과 동일 shape 이라 토큰 파싱이 필요 없다 (docs/06 §1).
 */
function toAuthUser(summary: AuthUserSummary | null): AuthUser | null {
  if (!summary) return null
  return { id: summary.id, name: summary.name, role: summary.role as UserRole }
}
