package com.fabwatch.equipment.controller;

import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.security.SecurityUtils;
import com.fabwatch.equipment.dto.EquipmentCreateRequest;
import com.fabwatch.equipment.dto.EquipmentDetailResponse;
import com.fabwatch.equipment.dto.EquipmentStatusChangeRequest;
import com.fabwatch.equipment.dto.EquipmentStatusLogResponse;
import com.fabwatch.equipment.dto.EquipmentSummaryResponse;
import com.fabwatch.equipment.dto.EquipmentUpdateRequest;
import com.fabwatch.equipment.entity.EquipmentStatus;
import com.fabwatch.equipment.service.EquipmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
 * 설비 API (docs/06 §2). 권한은 docs/11 §3 매트릭스 기준:
 * 조회=전체 / 등록·수정=ADMIN / 상태 전환=ADMIN·ENGINEER
 */
@RestController
@RequestMapping("/api/v1/equipments")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentService equipmentService;

    @GetMapping
    public PageResponse<EquipmentSummaryResponse> getEquipments(
            @RequestParam(required = false) Long processId,
            @RequestParam(required = false) EquipmentStatus status,
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return equipmentService.getEquipments(processId, status, pageable);
    }

    @GetMapping("/{id}")
    public EquipmentDetailResponse getEquipment(@PathVariable Long id) {
        return equipmentService.getEquipment(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public EquipmentDetailResponse create(@Valid @RequestBody EquipmentCreateRequest request) {
        return equipmentService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EquipmentDetailResponse update(@PathVariable Long id, @Valid @RequestBody EquipmentUpdateRequest request) {
        return equipmentService.update(id, request);
    }

    /** 상태 전환 — 상태 머신 밖의 전이는 400 INVALID_STATUS_TRANSITION */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
    public EquipmentDetailResponse changeStatus(@PathVariable Long id,
                                                @Valid @RequestBody EquipmentStatusChangeRequest request) {
        return equipmentService.changeStatus(id, request, SecurityUtils.currentUserId());
    }

    @GetMapping("/{id}/status-logs")
    public PageResponse<EquipmentStatusLogResponse> getStatusLogs(
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {
        return equipmentService.getStatusLogs(id, pageable);
    }
}
