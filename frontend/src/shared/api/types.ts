/**
 * 백엔드 공통 응답 계약 (docs/06_API명세서.md 헤더)
 * - 목록: { content, totalElements, totalPages, number }
 * - 에러: { code, message, timestamp }
 */

/** 목록 API 공통 래핑 응답 */
export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
}

/**
 * 로그인/리프레시 응답에 실려오는 사용자 요약 (docs/06 §1 `user:{id,name,role}`).
 * shared 계층은 features 를 참조할 수 없으므로 role 은 문자열로 두고,
 * 좁은 UserRole 타입 확정은 features/auth 쪽에서 한다.
 */
export interface AuthUserSummary {
  id: number
  name: string
  role: string
}

/** 백엔드 에러 응답 바디 (docs/03 F-7) */
export interface ApiErrorBody {
  code: string
  message: string
  timestamp: string
}

/**
 * 목록 응답에서 배열을 꺼낸다.
 * 백엔드는 목록을 `{ content, ... }` 로 감싸는 것이 계약(docs/06 목록 공통)이지만,
 * 2주차 시점에 sensor/alarm 엔드포인트는 백엔드 병렬 구현 중이라 래핑 여부가 확정되지 않았다.
 * 계약 위반을 조용히 삼키지 않도록 배열이 그대로 오면 console.warn 으로 드러낸다(QA 대조용).
 */
export function unwrapList<T>(data: PageResponse<T> | T[] | null | undefined, endpoint: string): T[] {
  if (Array.isArray(data)) {
    console.warn(
      `[API] ${endpoint} 응답이 목록 공통 래핑({content,...})이 아니라 배열입니다 — 백엔드 계약 확인 필요`,
    )
    return data
  }
  return data?.content ?? []
}

/** 목록 조회 공통 쿼리 파라미터 */
export interface PageParams {
  page?: number
  size?: number
  sort?: string
}

/**
 * 화면에서 다루는 정규화된 에러.
 * 분기는 반드시 `code`로 한다 — message 문자열 매칭 금지(문구 변경/다국어에 취약).
 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly timestamp: string

  constructor(code: string, message: string, status: number, timestamp: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.timestamp = timestamp
  }
}

/** 네트워크 장애 등 백엔드 응답 자체가 없을 때 사용하는 코드 */
export const CLIENT_ERROR_CODE = {
  NETWORK_ERROR: 'NETWORK_ERROR',
  UNKNOWN: 'UNKNOWN',
} as const
