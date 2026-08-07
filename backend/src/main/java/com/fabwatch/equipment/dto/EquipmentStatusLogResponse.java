package com.fabwatch.equipment.dto;

import com.fabwatch.equipment.entity.EquipmentStatusLog;

import java.time.Instant;

/** GET /equipments/{id}/status-logs (docs/06 §2). changedBy가 null이면 시스템 자동 전환. */
public record EquipmentStatusLogResponse(
        Long id,
        Long equipmentId,
        String fromStatus,
        String toStatus,
        String reason,
        Long changedBy,
        String changedByName,
        Instant changedAt) {

    public static EquipmentStatusLogResponse from(EquipmentStatusLog log, String changedByName) {
        return new EquipmentStatusLogResponse(
                log.getId(),
                log.getEquipment().getId(),
                log.getFromStatus() == null ? null : log.getFromStatus().name(),
                log.getToStatus().name(),
                log.getReason(),
                log.getChangedBy(),
                changedByName,
                log.getChangedAt());
    }
}
