package com.fabwatch.inspection.dto;

import com.fabwatch.inspection.entity.InspectionCheckResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 체크리스트 항목별 판정 입력 (docs/03 F-3.2) */
public record CheckResultRequest(
        @NotNull Long checklistItemId,
        @NotNull InspectionCheckResult.Result result,
        @Size(max = 300) String note) {
}
