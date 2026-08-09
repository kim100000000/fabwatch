package com.fabwatch.sensor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PUT /equipments/{id}/sensors/{sensorId}/thresholds (docs/06 §2, docs/03 F-2 FR-2.3).
 *
 * 4값 모두 nullable — NULL은 "해당 방향 미사용"이다 (docs/05 sensors).
 * reason은 필수: 현장에서 임계치 임의 변경은 사고의 씨앗이라 누가·언제·왜를 반드시 남긴다.
 */
public record ThresholdUpdateRequest(
        BigDecimal warnLow,
        BigDecimal warnHigh,
        BigDecimal critLow,
        BigDecimal critHigh,

        @NotBlank(message = "변경 사유는 필수입니다.")
        @Size(max = 300, message = "변경 사유는 300자 이하여야 합니다.")
        String reason) {
}
