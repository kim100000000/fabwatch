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
  // INVALID_STATUS_TRANSITION 은 서버 message 에 허용 전이 목록("허용: [IDLE, DOWN, PM]")이 담겨 있어
  // 일부러 매핑하지 않는다 — NOT_FOUND/VALIDATION_ERROR 와 동일하게 서버 message 폴백이 더 유용하다.
  DUPLICATE_EQUIPMENT_CODE: '이미 존재하는 설비 코드입니다. 다른 코드를 입력해 주세요.',
  // 센서 임계치 (docs/06 §2 PUT thresholds)
  // 서버 message 는 "임계치 순서 위반(crit_low ≤ warn_low): 85 > 90" 처럼 DB 컬럼명이 섞여 있어
  // 폴백시키면 사용자 화면에 snake_case 가 노출된다 — 반드시 매핑한다.
  INVALID_THRESHOLD_RANGE:
    '임계치 순서가 올바르지 않습니다. 위험 하한 ≤ 경고 하한 < 경고 상한 ≤ 위험 상한 이 되도록 입력해 주세요.',
  // 알람 (docs/06 §6)
  // ACK_REQUIRED_FIRST / INVALID_ALARM_STATUS 는 서버 message 에 "현재 상태: OPEN" 처럼 실제 상태가
  // 담겨 있어 일부러 매핑하지 않는다 — NOT_FOUND/VALIDATION_ERROR/INVALID_STATUS_TRANSITION 과 동일 원칙.
  // (의도적 미매핑임을 명시해 둔다. 매핑을 추가하면 상태 정보가 사라져 오히려 불친절해진다.)
  // 점검 이력 (docs/06 §4) — VALIDATION_ERROR(종료<=시작, 미래 시각 등)는 서버 message 가 구체적이라 매핑하지 않는다
  CAUSE_4M_REQUIRED: 'BM 점검은 4M 원인 분류(사람/설비/자재/방법)를 선택해야 합니다.',
  // AI 리포트 (docs/06 §7)
  AI_QUOTA_EXCEEDED: '오늘 AI 리포트 생성 한도를 모두 사용했습니다. 수동으로 작성해 주세요',
  ALREADY_GENERATING: '이미 생성 중인 리포트가 있습니다',
  INVALID_REPORT_STATE: '현재 상태에서는 수행할 수 없습니다',
  // 시뮬레이터 (docs/06 §8)
  SCENARIO_ALREADY_ACTIVE:
    '해당 센서에 같은 유형의 시나리오가 이미 실행 중입니다. 기존 시나리오를 해제한 뒤 다시 주입해 주세요.',
  // 공통 (docs/06 공통 오류) — 서버가 공통 포맷으로 내려주는 4xx
  // RATE_LIMITED 는 toUserMessage 에서 Retry-After(초)를 붙여 따로 만든다.
  RATE_LIMITED: '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
  PAYLOAD_TOO_LARGE: '보내는 내용이 너무 큽니다. 내용을 줄여서 다시 시도해 주세요.',
  CONFLICT: '다른 사용자가 먼저 변경했거나 이미 처리된 요청입니다. 화면을 새로고침한 뒤 다시 시도해 주세요.',
  METHOD_NOT_ALLOWED: '허용되지 않은 요청입니다.',
  UNSUPPORTED_MEDIA_TYPE: '지원하지 않는 요청 형식입니다.',
  NOT_ACCEPTABLE: '지원하지 않는 응답 형식 요청입니다.',
  // 클라이언트 측
  [CLIENT_ERROR_CODE.NETWORK_ERROR]: '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
  [CLIENT_ERROR_CODE.UNKNOWN]: '알 수 없는 오류가 발생했습니다.',
}

/**
 * 화면 표시용 메시지. 매핑에 없는 코드는 서버 message 를 그대로 보여준다.
 * `??` 가 아니라 `||` 인 이유: 응답 바디에 message 가 비어 있으면(빈 문자열) ApiError.message 도
 * 빈 문자열이 되어 `??` 로는 걸러지지 않고 빈 에러 문구가 노출된다.
 */
export function toUserMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'RATE_LIMITED') {
      // ACCOUNT_LOCKED(429)와 구분하기 위해 반드시 code 로 분기한다
      return error.retryAfterSec
        ? `요청이 너무 많습니다. 잠시 후 다시 시도해 주세요(${error.retryAfterSec}초).`
        : ERROR_MESSAGE.RATE_LIMITED
    }
    return ERROR_MESSAGE[error.code] || error.message || ERROR_MESSAGE[CLIENT_ERROR_CODE.UNKNOWN]
  }
  return ERROR_MESSAGE[CLIENT_ERROR_CODE.UNKNOWN]
}

/** 에러 코드 추출 (분기용) */
export function errorCodeOf(error: unknown): string {
  return error instanceof ApiError ? error.code : CLIENT_ERROR_CODE.UNKNOWN
}
