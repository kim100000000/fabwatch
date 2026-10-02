package com.fabwatch.aireport.dto;

import com.fabwatch.aireport.entity.AiReport;

import java.time.Instant;

/** 목록용 요약 (docs/06 §7) — 본문(draft/final)은 제외한다. */
public record AiReportSummary(
        Long id,
        Long equipmentId,
        String equipmentCode,
        String equipmentName,
        Long alarmId,
        Long inspectionId,
        String title,
        String status,
        String createdByName,
        Instant createdAt,
        Instant confirmedAt) {

    public static AiReportSummary from(AiReport r, String equipmentCode, String equipmentName, String createdByName) {
        return new AiReportSummary(
                r.getId(), r.getEquipmentId(), equipmentCode, equipmentName,
                r.getAlarmId(), r.getInspectionId(), r.getTitle(), r.getStatus().name(),
                createdByName, r.getCreatedAt(), r.getConfirmedAt());
    }
}
