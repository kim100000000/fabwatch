/**
 * 인증 도메인 타입 (docs/06 §1, docs/11 §2)
 * 역할명은 백엔드 계약 그대로 사용한다 — TECHNICIAN 을 MAINTENANCE 로 바꾸지 않는다.
 */

export type UserRole = 'ADMIN' | 'ENGINEER' | 'TECHNICIAN'

/** 로그인 응답에 포함되는 사용자 정보 */
export interface AuthUser {
  id: number
  name: string
  role: UserRole
}

/** POST /auth/login 요청 */
export interface LoginRequest {
  email: string
  password: string
}

/** POST /auth/login 응답 */
export interface LoginResponse {
  accessToken: string
  refreshToken: string
  user: AuthUser
}

/** 화면 메뉴 표기용 역할 라벨 (타입/API 필드명은 그대로 유지) */
export const ROLE_LABEL: Record<UserRole, string> = {
  ADMIN: '관리자',
  ENGINEER: '엔지니어',
  TECHNICIAN: '테크니션',
}
