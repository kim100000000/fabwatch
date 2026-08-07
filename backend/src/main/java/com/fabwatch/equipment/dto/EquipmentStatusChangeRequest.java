package com.fabwatch.equipment.dto;

import com.fabwatch.equipment.entity.EquipmentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * PATCH /equipments/{id}/status — { toStatus, reason } (docs/06 §2).
 * 상태 전환은 DB 필드 변경일 뿐 실제 설비 제어가 아니다 (docs/03 F-2, docs/11 §10).
 */
public record EquipmentStatusChangeRequest(
        @NotNull EquipmentStatus toStatus,
        @Size(max = 200) String reason) {
}
