package com.fabwatch.aireport.controller;

import com.fabwatch.aireport.dto.AiReportAcceptedResponse;
import com.fabwatch.aireport.dto.AiReportCreateRequest;
import com.fabwatch.aireport.dto.AiReportResponse;
import com.fabwatch.aireport.dto.AiReportSummary;
import com.fabwatch.aireport.dto.AiReportUpdateRequest;
import com.fabwatch.aireport.entity.AiReport;
import com.fabwatch.aireport.service.AiReportService;
import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 리포트 API (docs/06 §7).
 * 권한: 조회=인증 사용자 전체 / 생성·수정·확정·재시도=ENGINEER+(ADMIN 포함). Claude 호출은 백엔드 경유만(docs/11 §7).
 * 목록 정렬은 서버가 createdAt desc로 고정한다(sort 파라미터 무시).
 */
@RestController
@RequestMapping("/api/v1/ai-reports")
@RequiredArgsConstructor
public class AiReportController {

    private final AiReportService aiReportService;

    /** 비동기 생성 요청 — 202 Accepted {reportId, status:"GENERATING"}. 클라이언트는 GET으로 폴링한다. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ENGINEER','ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiReportAcceptedResponse create(@Valid @RequestBody AiReportCreateRequest request) {
        return aiReportService.create(request, SecurityUtils.currentUserId());
    }

    @GetMapping("/{id}")
    public AiReportResponse get(@PathVariable Long id) {
        return aiReportService.get(id);
    }

    @GetMapping
    public PageResponse<AiReportSummary> list(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) AiReport.Status status,
            @RequestParam(required = false) Long alarmId,
            @RequestParam(required = false) Long inspectionId,
            @PageableDefault(size = 20) Pageable pageable) {
        return aiReportService.list(equipmentId, status, alarmId, inspectionId, pageable);
    }

    /** 확정본 편집 저장 — DRAFT 또는 FAILED(수동 작성)만. 그 외 409 INVALID_REPORT_STATE */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ENGINEER','ADMIN')")
    public AiReportResponse update(@PathVariable Long id, @Valid @RequestBody AiReportUpdateRequest request) {
        return aiReportService.updateFinal(id, request);
    }

    @PatchMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('ENGINEER','ADMIN')")
    public AiReportResponse confirm(@PathVariable Long id) {
        return aiReportService.confirm(id, SecurityUtils.currentUserId());
    }

    /** FAILED 재시도 — 같은 reportId 재사용, 202 */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAnyRole('ENGINEER','ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiReportAcceptedResponse retry(@PathVariable Long id) {
        return aiReportService.retry(id, SecurityUtils.currentUserId());
    }
}
