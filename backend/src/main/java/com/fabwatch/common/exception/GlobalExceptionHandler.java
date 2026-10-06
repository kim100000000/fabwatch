package com.fabwatch.common.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 모든 예외를 공통 에러 포맷으로 변환한다. 스택트레이스는 응답에 절대 포함하지 않는다 (docs/11 §6).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("비즈니스 예외: {} - {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, e.getMessage()));
    }

    /** @Valid 실패 — 어떤 필드가 왜 틀렸는지 메시지에 담는다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining(", "));
        return build(ErrorCode.VALIDATION_ERROR, detail.isBlank() ? null : detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException e) {
        return build(ErrorCode.VALIDATION_ERROR, e.getMessage());
    }

    /** enum 파싱 실패·JSON 형식 오류 등 — 400으로 통일. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception e) {
        log.warn("요청 파싱 실패: {}", e.getMessage());
        return build(ErrorCode.VALIDATION_ERROR, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return build(ErrorCode.FORBIDDEN, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return build(ErrorCode.NOT_FOUND, null);
    }

    /** 필수 쿼리 파라미터 누락 — 클라이언트 실수이므로 500이 아니라 400. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return build(ErrorCode.VALIDATION_ERROR, "필수 파라미터가 없습니다: " + e.getParameterName());
    }

    /** 허용되지 않는 HTTP 메서드 — 500이 아니라 405 (클라이언트 실수가 서버 오류로 보이지 않게). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, null);
    }

    /** 지원하지 않는 Content-Type — 415. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
    }

    /** 지원하지 않는 Accept — 406. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        // Accept가 JSON이 아니면 오류 본문도 협상에 실패하므로 JSON으로 고정한다(본문이 비지 않게).
        return ResponseEntity.status(ErrorCode.NOT_ACCEPTABLE.getStatus())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(ErrorCode.NOT_ACCEPTABLE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return build(ErrorCode.INTERNAL_ERROR, null);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message) {
        ErrorResponse body = message == null ? ErrorResponse.of(code) : ErrorResponse.of(code, message);
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
