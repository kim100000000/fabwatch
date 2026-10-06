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
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않는 요청 방식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다. Content-Type을 확인하세요."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 응답 형식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 인증 (docs/03 F-1, docs/06 §1)
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "로그인 5회 실패로 계정이 잠겼습니다. 15분 후 다시 시도하세요."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "액세스 토큰이 만료되었습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    USER_DISABLED(HttpStatus.FORBIDDEN, "비활성화된 계정입니다."),

    // 설비 (docs/03 F-2, docs/06 §2)
    INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "허용되지 않는 설비 상태 전환입니다."),
    DUPLICATE_EQUIPMENT_CODE(HttpStatus.CONFLICT, "이미 존재하는 설비 코드입니다."),

    // 센서 임계치 (docs/03 F-2 "임계치 설정", docs/06 §2 PUT thresholds)
    INVALID_THRESHOLD_RANGE(HttpStatus.BAD_REQUEST, "임계치 순서가 올바르지 않습니다. crit_low ≤ warn_low < warn_high ≤ crit_high 이어야 합니다."),

    // 알람 (docs/03 F-5.3, docs/06 §6)
    ACK_REQUIRED_FIRST(HttpStatus.BAD_REQUEST, "확인(ACK) 처리 후에만 해제할 수 있습니다."),
    INVALID_ALARM_STATUS(HttpStatus.BAD_REQUEST, "현재 알람 상태에서는 수행할 수 없는 처리입니다."),

    // 점검 이력 (docs/03 F-3.1, docs/06 §4)
    CAUSE_4M_REQUIRED(HttpStatus.BAD_REQUEST, "BM(사후보전) 점검 이력은 4M 원인 분류(cause4m)가 필수입니다."),

    // 시뮬레이터 (docs/03 F-4.2, docs/06 §8)
    SCENARIO_ALREADY_ACTIVE(HttpStatus.CONFLICT, "해당 센서에 동일 유형의 활성 시나리오가 이미 있습니다."),

    // AI 리포트 (docs/03 F-6.4, docs/06 §7)
    AI_QUOTA_EXCEEDED(HttpStatus.FORBIDDEN, "오늘의 AI 리포트 생성 한도를 초과했습니다. 내일 다시 시도하거나 수동으로 작성하세요."),
    ALREADY_GENERATING(HttpStatus.CONFLICT, "이미 AI 리포트를 생성 중입니다."),
    INVALID_REPORT_STATE(HttpStatus.CONFLICT, "현재 리포트 상태에서는 수행할 수 없는 처리입니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
