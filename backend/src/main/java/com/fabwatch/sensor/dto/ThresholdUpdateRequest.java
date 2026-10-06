package com.fabwatch.sensor.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PUT /equipments/{id}/sensors/{sensorId}/thresholds (docs/06 §2, docs/03 F-2 FR-2.3).
 *
 * 4값 모두 nullable — NULL은 "해당 방향 미사용"이다 (docs/05 sensors). 단 4값을 전부 비울 수는 없다(센서 감시가 꺼짐).
 * 자릿수는 DB 컬럼 decimal(10,2)에 맞춘다 — 소수 3자리 이상을 받으면 DB가 조용히 반올림해 응답과 저장값이 달라진다.
 * reason은 필수: 현장에서 임계치 임의 변경은 사고의 씨앗이라 누가·언제·왜를 반드시 남긴다.
 */
public record ThresholdUpdateRequest(
        @Digits(integer = 8, fraction = 2, message = "임계치는 정수 8자리·소수 2자리 이내여야 합니다.")
        BigDecimal warnLow,
        @Digits(integer = 8, fraction = 2, message = "임계치는 정수 8자리·소수 2자리 이내여야 합니다.")
        BigDecimal warnHigh,
        @Digits(integer = 8, fraction = 2, message = "임계치는 정수 8자리·소수 2자리 이내여야 합니다.")
        BigDecimal critLow,
        @Digits(integer = 8, fraction = 2, message = "임계치는 정수 8자리·소수 2자리 이내여야 합니다.")
        BigDecimal critHigh,

        @NotBlank(message = "변경 사유는 필수입니다.")
        @Size(max = 300, message = "변경 사유는 300자 이하여야 합니다.")
        String reason) {
}
