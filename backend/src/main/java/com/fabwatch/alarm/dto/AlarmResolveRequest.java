package com.fabwatch.alarm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /alarms/{id}/resolve — 해제 사유 필수 (docs/03 F-5.3, docs/06 §6).
 */
public record AlarmResolveRequest(
        @NotBlank(message = "해제 사유는 필수입니다.")
        @Size(max = 300, message = "해제 사유는 300자 이하여야 합니다.")
        String resolveNote) {
}
