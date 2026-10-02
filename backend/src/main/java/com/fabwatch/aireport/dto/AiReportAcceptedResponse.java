package com.fabwatch.aireport.dto;

/** 202 Accepted 응답 — { reportId, status:"GENERATING" } (POST /ai-reports, POST /ai-reports/{id}/retry) */
public record AiReportAcceptedResponse(Long reportId, String status) {

    public static AiReportAcceptedResponse generating(Long reportId) {
        return new AiReportAcceptedResponse(reportId, "GENERATING");
    }
}
