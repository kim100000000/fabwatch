package com.fabwatch.equipment.dto;

import com.fabwatch.equipment.entity.Equipment;

/** 설비 목록/트리 항목 (docs/06 §2 GET /equipments) */
public record EquipmentSummaryResponse(
        Long id,
        String code,
        String name,
        String status,
        Long processId,
        String processName,
        Long lineId,
        String lineName,
        String modelName,
        String maker,
        Long managerId,
        String managerName) {

    public static EquipmentSummaryResponse from(Equipment equipment, String managerName) {
        var process = equipment.getProcess();
        var line = process.getLine();
        return new EquipmentSummaryResponse(
                equipment.getId(),
                equipment.getCode(),
                equipment.getName(),
                equipment.getStatus().name(),
                process.getId(),
                process.getName(),
                line.getId(),
                line.getName(),
                equipment.getModelName(),
                equipment.getMaker(),
                equipment.getManagerId(),
                managerName);
    }
}
