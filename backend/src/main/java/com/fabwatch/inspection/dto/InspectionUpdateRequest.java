package com.fabwatch.inspection.dto;

import com.fabwatch.inspection.entity.Inspection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * PUT /inspections/{id} 요청 (docs/06 §4). 설비·유형·알람 연계·작성자는 불변이라 받지 않는다.
 * shift 생략 시: startedAt이 바뀌었으면 자동 재판정, 안 바뀌었으면 기존 값 유지.
 * checkResults가 null이면 기존 결과를 그대로 두고, 값이 있으면(빈 배열 포함) 전체 교체한다.
 */
public record InspectionUpdateRequest(
        Inspection.Shift shift,
        @NotNull Instant startedAt,
        @NotNull Instant endedAt,
        @NotBlank @Size(max = InspectionLimits.CONTENT_MAX) String content,
        @Size(max = InspectionLimits.CONTENT_MAX) String actionTaken,
        Inspection.Cause4M cause4m,
        @Size(max = 300) String causeDetail,
        @Valid @Size(max = InspectionLimits.CHECK_RESULTS_MAX) List<CheckResultRequest> checkResults) {
}
