package com.fabwatch.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 공통 에러 코드 (docs/03 F-7, docs/06 에러 공통 포맷).
 * 응답은 항상 { code, message, timestamp } — 스택트레이스는 절대 포함하지 않는다 (docs/11 §6).
 */
@Getter
public enum ErrorCode {

    // 공통
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 인증 (docs/03 F-1, docs/06 §1)
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "로그인 5회 실패로 계정이 잠겼습니다. 15분 후 다시 시도하세요."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "액세스 토큰이 만료되었습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    USER_DISABLED(HttpStatus.FORBIDDEN, "비활성화된 계정입니다."),

    // 설비 (docs/03 F-2, docs/06 §2)
    INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "허용되지 않는 설비 상태 전환입니다."),
    DUPLICATE_EQUIPMENT_CODE(HttpStatus.CONFLICT, "이미 존재하는 설비 코드입니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
