package com.fabwatch.inspection.controller;

import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.security.SecurityUtils;
import com.fabwatch.inspection.dto.InspectionCreateRequest;
import com.fabwatch.inspection.dto.InspectionDetailResponse;
import com.fabwatch.inspection.dto.InspectionResponse;
import com.fabwatch.inspection.dto.InspectionUpdateRequest;
import com.fabwatch.inspection.entity.Inspection;
import com.fabwatch.inspection.service.InspectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Instant;

/**
 * 점검 이력 API (docs/06 §4).
 * 권한: 조회·등록=인증 사용자 전체 / 수정=작성자 본인 또는 ENGINEER+(서비스에서 판정) / 승인=ENGINEER+
 * 목록 정렬은 서버가 startedAt desc 로 고정한다(sort 파라미터 무시).
 */
@RestController
@RequestMapping("/api/v1/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;

    @GetMapping
    public PageResponse<InspectionResponse> getInspections(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(required = false) Inspection.Type type,
            @RequestParam(required = false) Inspection.Shift shift,
            @RequestParam(required = false) Long workerId,
            @RequestParam(required = false) Boolean hasNg,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        return inspectionService.getInspections(equipmentId, type, shift, workerId, hasNg, from, to, pageable);
    }

    @GetMapping("/{id}")
    public InspectionDetailResponse getInspection(@PathVariable Long id) {
        return inspectionService.getInspection(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InspectionDetailResponse create(@Valid @RequestBody InspectionCreateRequest request) {
        return inspectionService.create(request, SecurityUtils.currentUserId());
    }

    @PutMapping("/{id}")
    public InspectionDetailResponse update(@PathVariable Long id, @Valid @RequestBody InspectionUpdateRequest request) {
        return inspectionService.update(id, request, SecurityUtils.currentUserId(), SecurityUtils.currentRole());
    }

    @PatchMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('ENGINEER','ADMIN')")
    public InspectionDetailResponse review(@PathVariable Long id) {
        return inspectionService.review(id, SecurityUtils.currentUserId());
    }
}
