package com.fabwatch.aireport.dto;

import com.fabwatch.aireport.entity.AiReport;

import java.time.Instant;

/**
 * AI 리포트 상세 응답 (docs/06 §7). GENERATING 중에는 draftContent/finalContent를 null로 내려준다.
 * equipmentCode/Name, createdByName/confirmedByName은 다른 도메인 조회 인터페이스로 채운 표시용 필드다.
 */
public record AiReportResponse(
        Long id,
        Long equipmentId,
        String equipmentCode,
        String equipmentName,
        Long alarmId,
        Long inspectionId,
        String title,
        String status,
        String draftContent,
        String finalContent,
        String failReason,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Long createdBy,
        String createdByName,
        Long confirmedBy,
        String confirmedByName,
        Instant createdAt,
        Instant confirmedAt) {

    public static AiReportResponse from(AiReport r, String equipmentCode, String equipmentName,
                                        String createdByName, String confirmedByName) {
        boolean generating = r.getStatus() == AiReport.Status.GENERATING;
        return new AiReportResponse(
                r.getId(), r.getEquipmentId(), equipmentCode, equipmentName,
                r.getAlarmId(), r.getInspectionId(), r.getTitle(), r.getStatus().name(),
                generating ? null : r.getDraftContent(),
                generating ? null : r.getFinalContent(),
                r.getFailReason(), r.getModel(), r.getPromptTokens(), r.getCompletionTokens(),
                r.getCreatedBy(), createdByName, r.getConfirmedBy(), confirmedByName,
                r.getCreatedAt(), r.getConfirmedAt());
    }
}
