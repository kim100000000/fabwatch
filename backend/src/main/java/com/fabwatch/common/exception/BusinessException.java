package com.fabwatch.common.exception;

import lombok.Getter;

/**
 * 도메인 규칙 위반 예외. 컨트롤러에서 직접 ResponseEntity로 에러를 만들지 않고
 * 이 예외를 던져 GlobalExceptionHandler가 공통 포맷으로 변환하게 한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
