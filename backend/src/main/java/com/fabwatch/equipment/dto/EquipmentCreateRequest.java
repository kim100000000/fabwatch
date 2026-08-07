package com.fabwatch.equipment.dto;

import com.fabwatch.equipment.entity.EquipmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * POST /equipments (ADMIN) — 설비 등록 (docs/03 F-2 설비 필드).
 * status 미지정 시 IDLE로 생성한다 (docs/05 DEFAULT 'IDLE').
 */
public record EquipmentCreateRequest(
        @NotNull Long processId,
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String modelName,
        @Size(max = 100) String maker,
        LocalDate installedAt,
        EquipmentStatus status,
        Long managerId,
        @Size(max = 500) String note) {
}
