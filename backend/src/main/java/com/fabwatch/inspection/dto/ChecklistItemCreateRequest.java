package com.fabwatch.inspection.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /equipments/{id}/checklist — seq 생략 시 맨 뒤에 추가 */
public record ChecklistItemCreateRequest(
        @NotBlank @Size(max = 200) String itemName,
        @Size(max = 200) String criteria,
        @Min(0) Integer seq) {
}
