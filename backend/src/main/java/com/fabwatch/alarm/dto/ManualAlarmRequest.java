package com.fabwatch.alarm.dto;

import com.fabwatch.alarm.entity.Alarm;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /alarms/manual — 수동 고장 보고 (docs/06 §6).
 * severity가 CRITICAL이면 설비가 자동 DOWN 된다 (docs/03 F-4.3 — DB 필드 변경만, docs/11 §10).
 */
public record ManualAlarmRequest(
        @NotNull(message = "설비 ID는 필수입니다.")
        Long equipmentId,

        @NotNull(message = "심각도는 필수입니다.")
        Alarm.Severity severity,

        @NotBlank(message = "내용은 필수입니다.")
        @Size(max = 300, message = "내용은 300자 이하여야 합니다.")
        String message) {
}
