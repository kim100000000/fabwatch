package com.fabwatch.inspection.dto;

import com.fabwatch.inspection.entity.Inspection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * POST /inspections 요청 (docs/06 §4). workerId는 받지 않는다 — 로그인 사용자가 작성자다.
 * shift 생략 시 startedAt(KST)으로 자동 판정한다.
 */
public record InspectionCreateRequest(
        @NotNull Long equipmentId,
        @NotNull Inspection.Type type,
        Inspection.Shift shift,
        @NotNull Instant startedAt,
        @NotNull Instant endedAt,
        @NotBlank @Size(max = InspectionLimits.CONTENT_MAX) String content,
        @Size(max = InspectionLimits.CONTENT_MAX) String actionTaken,
        Inspection.Cause4M cause4m,
        @Size(max = 300) String causeDetail,
        Long alarmId,
        @Valid @Size(max = InspectionLimits.CHECK_RESULTS_MAX) List<CheckResultRequest> checkResults) {
}
