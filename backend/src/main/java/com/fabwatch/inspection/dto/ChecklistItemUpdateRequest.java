package com.fabwatch.inspection.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** PUT /equipments/{id}/checklist/{itemId} — 삭제는 active=false (물리 삭제 없음). seq null이면 순서 유지. */
public record ChecklistItemUpdateRequest(
        @NotBlank @Size(max = 200) String itemName,
        @Size(max = 200) String criteria,
        @Min(0) Integer seq,
        @NotNull Boolean active) {
}
