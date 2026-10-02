package com.fabwatch.aireport.dto;

/**
 * POST /ai-reports 요청 — alarmId / inspectionId 중 하나 이상 (둘 다 없으면 400 VALIDATION_ERROR).
 * 둘 다 있으면 둘 다 저장하되 서로 다른 설비면 400.
 */
public record AiReportCreateRequest(Long alarmId, Long inspectionId) {
}
