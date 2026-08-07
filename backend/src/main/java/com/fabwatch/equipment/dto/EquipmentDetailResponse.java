package com.fabwatch.equipment.dto;

import com.fabwatch.equipment.entity.Equipment;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 설비 상세 (docs/06 §2 GET /equipments/{id}).
 *
 * ※ 문서상 상세에는 "센서 + PM스케줄 + 미해결알람수"가 포함되지만, sensor/inspection/alarm 도메인은
 *   이번 라운드 구현 범위가 아니라 기본 정보만 반환한다. 다음 라운드에서 필드를 추가한다.
 */
public record EquipmentDetailResponse(
        Long id,
        String code,
        String name,
        String modelName,
        String maker,
        LocalDate installedAt,
        String status,
        Long processId,
        String processName,
        Long lineId,
        String lineName,
        Long managerId,
        String managerName,
        String note,
        Instant createdAt,
        Instant updatedAt) {

    public static EquipmentDetailResponse from(Equipment equipment, String managerName) {
        var process = equipment.getProcess();
        var line = process.getLine();
        return new EquipmentDetailResponse(
                equipment.getId(),
                equipment.getCode(),
                equipment.getName(),
                equipment.getModelName(),
                equipment.getMaker(),
                equipment.getInstalledAt(),
                equipment.getStatus().name(),
                process.getId(),
                process.getName(),
                line.getId(),
                line.getName(),
                equipment.getManagerId(),
                managerName,
                equipment.getNote(),
                equipment.getCreatedAt(),
                equipment.getUpdatedAt());
    }
}
