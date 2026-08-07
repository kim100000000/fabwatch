import { ApiError, CLIENT_ERROR_CODE } from '@/shared/api'

/**
 * 에러 코드 → 사용자 문구 매핑.
 * 분기는 항상 code 로 한다 (docs/06 에러 공통 포맷). message 문자열 매칭 금지.
 */
const ERROR_MESSAGE: Record<string, string> = {
  // 인증 (docs/06 §1)
  LOGIN_FAILED: '이메일 또는 비밀번호가 올바르지 않습니다.',
  ACCOUNT_LOCKED: '로그인 5회 실패로 계정이 잠겼습니다. 15분 후 다시 시도해 주세요.',
  TOKEN_EXPIRED: '세션이 만료되었습니다. 다시 로그인해 주세요.',
  INVALID_TOKEN: '세션이 만료되었습니다. 다시 로그인해 주세요.',
  USER_DISABLED: '비활성화된 계정입니다. 관리자에게 문의하세요.',
  UNAUTHORIZED: '로그인이 필요합니다.',
  FORBIDDEN: '권한이 없습니다.',
  // 공통 — NOT_FOUND / VALIDATION_ERROR 는 서버 message 가 더 구체적이라 일부러 매핑하지 않는다
  INTERNAL_ERROR: '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  // 설비 (docs/06 §2)
  INVALID_STATUS_TRANSITION: '허용되지 않는 설비 상태 전환입니다.',
  DUPLICATE_EQUIPMENT_CODE: '이미 존재하는 설비 코드입니다.',
  // 클라이언트 측
  [CLIENT_ERROR_CODE.NETWORK_ERROR]: '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
  [CLIENT_ERROR_CODE.UNKNOWN]: '알 수 없는 오류가 발생했습니다.',
}

/** 화면 표시용 메시지. 매핑에 없는 코드는 서버 message 를 그대로 보여준다. */
export function toUserMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return ERROR_MESSAGE[error.code] ?? error.message ?? ERROR_MESSAGE[CLIENT_ERROR_CODE.UNKNOWN]
  }
  return ERROR_MESSAGE[CLIENT_ERROR_CODE.UNKNOWN]
}

/** 에러 코드 추출 (분기용) */
export function errorCodeOf(error: unknown): string {
  return error instanceof ApiError ? error.code : CLIENT_ERROR_CODE.UNKNOWN
}
