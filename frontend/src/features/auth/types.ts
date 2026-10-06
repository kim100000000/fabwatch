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

/**
 * GET /users/lookup 항목 — 작업자 필터 등 '이름으로 사용자를 고르는' 화면용 최소 정보.
 * 이메일 등 개인정보는 내려오지 않는다. 이름순 단순 배열, 로그인한 전체 역할이 조회 가능.
 */
export interface UserLookupItem {
  id: number
  name: string
  role: UserRole
}
