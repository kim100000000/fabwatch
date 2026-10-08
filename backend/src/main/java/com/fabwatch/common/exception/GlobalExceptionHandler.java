package com.fabwatch.common.exception;

import com.fabwatch.common.security.RequestSizeLimitFilter;
import com.fabwatch.common.util.LogSanitizer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.MediaType;
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
import java.util.TreeSet;

/**
 * 모든 예외를 공통 에러 포맷으로 변환한다. 스택트레이스는 응답에 절대 포함하지 않는다 (docs/11 §6).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        // 메시지에 사용자 입력이 섞일 수 있어 개행·제어문자를 제거하고 기록한다 (로그 인젝션 방지)
        log.warn("비즈니스 예외: {} - {}", code.name(), LogSanitizer.clean(e.getMessage()));
        return build(code, e.getMessage());
    }

    /** @Valid 실패 — 어떤 필드가 왜 틀렸는지 메시지에 담는다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining(", "));
        return build(ErrorCode.VALIDATION_ERROR, detail.isBlank() ? null : detail);
    }

    /** 파라미터 검증 실패 — 메시지 원문(클래스·메서드 경로, 입력값)은 노출하지 않고 고정 문구 + 필드명만 돌려준다. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException e) {
        String fields = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(path -> {
                    String text = String.valueOf(path);
                    return text.substring(text.lastIndexOf('.') + 1);
                })
                .collect(Collectors.toCollection(TreeSet::new))
                .stream()
                .collect(Collectors.joining(", "));
        return build(ErrorCode.VALIDATION_ERROR,
                fields.isBlank() ? null : ErrorCode.VALIDATION_ERROR.getDefaultMessage() + " (" + fields + ")");
    }

    /**
     * enum 파싱 실패·JSON 형식 오류 등 — 400으로 통일.
     * 단, 청크 전송 본문이 크기 상한을 넘어 필터가 던진 예외면 413으로 응답한다.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception e) {
        if (hasCause(e, RequestSizeLimitFilter.PayloadTooLargeException.class)) {
            return build(ErrorCode.PAYLOAD_TOO_LARGE, null);
        }
        // 파싱 오류 메시지는 요청 본문 조각을 포함할 수 있다 — 클래스명만 남기고 본문은 기록하지 않는다
        log.warn("요청 파싱 실패: {}", e.getClass().getSimpleName());
        return build(ErrorCode.VALIDATION_ERROR, null);
    }

    /** 존재하지 않는 sort 속성(?sort=foo) — 500이 아니라 400. 속성명 노출 없이 고정 문구. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handleInvalidProperty(PropertyReferenceException e) {
        return build(ErrorCode.VALIDATION_ERROR, "정렬 또는 조회 기준이 올바르지 않습니다.");
    }

    /** 저장소 계층이 PropertyReferenceException을 감싸서 던지는 경우도 400으로 처리하고, 그 밖의 원인은 500. */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDataAccess(InvalidDataAccessApiUsageException e) {
        if (hasCause(e, PropertyReferenceException.class)) {
            return build(ErrorCode.VALIDATION_ERROR, "정렬 또는 조회 기준이 올바르지 않습니다.");
        }
        return handleUnexpected(e);
    }

    /** UNIQUE·FK·NOT NULL 등 DB 제약 충돌 — 409. SQL·제약명·테이블명은 응답에 싣지 않는다(고정 문구). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("데이터 무결성 위반: {}", e.getClass().getSimpleName());
        return build(ErrorCode.CONFLICT, null);
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
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(ErrorCode.NOT_ACCEPTABLE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return build(ErrorCode.INTERNAL_ERROR, null);
    }

    /**
     * Content-Type을 JSON으로 고정한다 — Accept가 text/event-stream(SSE)처럼 JSON이 아니면
     * 응답 협상이 실패해 오류 본문이 사라지거나 406으로 바뀌는 것을 막는다.
     */
    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message) {
        ErrorResponse body = message == null ? ErrorResponse.of(code) : ErrorResponse.of(code, message);
        return ResponseEntity.status(code.getStatus()).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** 예외 원인 사슬에 지정한 타입이 있는지 확인 */
    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
