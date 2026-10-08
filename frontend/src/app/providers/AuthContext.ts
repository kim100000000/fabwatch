import { createContext } from 'react'
import type { AuthUser, LoginRequest } from '@/features/auth'

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

/**
 * 로그인 화면에 보여줄 안내.
 * - expired: 세션 만료로 강제 로그아웃됨
 * - restore-failed: 네트워크 장애 등으로 세션 복구를 못 함(저장된 로그인 정보는 유지)
 */
export type SessionNotice = 'expired' | 'restore-failed' | null

export interface AuthContextValue {
  status: AuthStatus
  user: AuthUser | null
  sessionNotice: SessionNotice
  clearSessionNotice: () => void
  login: (payload: LoginRequest) => Promise<AuthUser>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
