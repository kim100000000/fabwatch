package com.fabwatch.common.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 예외 매핑 보강 — 정렬 속성 오류 400, 무결성 위반 409, 검증 메시지 원문 비노출 */
class GlobalExceptionHandlerHardeningTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    static class Sample {
        @Size(max = 2)
        String secretField = "너무 긴 값 — 입력 원문";
    }

    private static PropertyReferenceException propertyError() {
        return new PropertyReferenceException("foo", TypeInformation.of(Sample.class), List.of());
    }

    @Test
    @DisplayName("PropertyReferenceException(?sort=foo) → 400 VALIDATION_ERROR (500 아님), 속성명 비노출")
    void 정렬_속성_오류는_400() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidProperty(propertyError());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).doesNotContain("foo").doesNotContain("Sample");
    }

    @Test
    @DisplayName("저장소가 PropertyReferenceException을 감싼 InvalidDataAccessApiUsageException도 400, 다른 원인은 500")
    void 감싼_예외() {
        ResponseEntity<ErrorResponse> wrapped = handler.handleInvalidDataAccess(
                new InvalidDataAccessApiUsageException("Failed", propertyError()));
        assertThat(wrapped.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<ErrorResponse> other = handler.handleInvalidDataAccess(
                new InvalidDataAccessApiUsageException("something else"));
        assertThat(other.getStatusCode().value()).isEqualTo(500);
        assertThat(other.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("DataIntegrityViolationException → 409 CONFLICT, SQL·제약명·테이블명 비노출(고정 문구)")
    void 무결성_위반은_409() {
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrity(new DataIntegrityViolationException(
                "could not execute statement [Duplicate entry 'LAMI-01' for key 'equipments.uk_equipment_code'] "
                        + "[insert into equipments (code) values (?)]"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ErrorResponse body = response.getBody();
        assertThat(body.code()).isEqualTo("CONFLICT");
        assertThat(body.message()).isEqualTo(ErrorCode.CONFLICT.getDefaultMessage());
        assertThat(body.message()).doesNotContain("Duplicate").doesNotContain("insert").doesNotContain("uk_")
                .doesNotContain("equipments").doesNotContain("LAMI-01");
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("ConstraintViolationException → 메시지 원문 대신 고정 문구 + 필드명만")
    void 제약_위반_메시지_원문_비노출() {
        Set<ConstraintViolation<Sample>> violations;
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            violations = factory.getValidator().validate(new Sample());
        }
        assertThat(violations).isNotEmpty();

        ResponseEntity<ErrorResponse> response = handler.handleConstraint(
                new ConstraintViolationException("getAlarms.size: 원문 메시지 SECRET-RAW 입력값", violations));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        String message = response.getBody().message();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(message).contains("secretField").contains(ErrorCode.VALIDATION_ERROR.getDefaultMessage());
        assertThat(message).doesNotContain("SECRET-RAW").doesNotContain("getAlarms").doesNotContain("입력 원문");
    }

    @Test
    @DisplayName("모든 오류 응답은 Content-Type이 JSON으로 고정된다 (Accept: text/event-stream에서도 본문 유지)")
    void 오류_응답_JSON_고정() {
        ResponseEntity<ErrorResponse> response = handler.handleBusiness(
                new BusinessException(ErrorCode.RATE_LIMITED));
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("새 ErrorCode 3종의 상태 코드")
    void 새_에러코드_상태() {
        assertThat(ErrorCode.RATE_LIMITED.getStatus().value()).isEqualTo(429);
        assertThat(ErrorCode.PAYLOAD_TOO_LARGE.getStatus().value()).isEqualTo(413);
        assertThat(ErrorCode.CONFLICT.getStatus().value()).isEqualTo(409);
    }
}
