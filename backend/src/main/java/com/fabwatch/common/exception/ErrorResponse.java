package com.fabwatch.common.exception;

import java.time.Instant;

/**
 * 공통 에러 응답 바디 — { "code": "...", "message": "...", "timestamp": "..." } (docs/03 F-7).
 * timestamp는 UTC ISO-8601.
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getDefaultMessage(), Instant.now());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message, Instant.now());
    }
}
