package com.fabwatch.aireport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PUT /ai-reports/{id} 요청 — 확정본(편집본) 저장. 공백만 있는 내용은 400. */
public record AiReportUpdateRequest(
        @NotBlank(message = "finalContent는 필수입니다.")
        @Size(max = 100_000, message = "finalContent는 100,000자 이하여야 합니다.")
        String finalContent) {
}
