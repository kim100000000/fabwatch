package com.fabwatch.equipment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * PUT /equipments/{id} (ADMIN) — 설비 수정.
 * code(설비코드)와 status는 여기서 바꿀 수 없다 — 코드는 물리 설비 식별자,
 * 상태는 PATCH /equipments/{id}/status(상태 머신)에서만 변경한다.
 */
public record EquipmentUpdateRequest(
        @NotNull Long processId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String modelName,
        @Size(max = 100) String maker,
        LocalDate installedAt,
        Long managerId,
        @Size(max = 500) String note) {
}
