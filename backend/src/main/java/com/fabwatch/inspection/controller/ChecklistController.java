package com.fabwatch.inspection.controller;

import com.fabwatch.inspection.dto.ChecklistItemCreateRequest;
import com.fabwatch.inspection.dto.ChecklistItemResponse;
import com.fabwatch.inspection.dto.ChecklistItemUpdateRequest;
import com.fabwatch.inspection.service.ChecklistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PM 체크리스트 템플릿 API (docs/06 §4). 경로는 /equipments/{id}/... 아래지만 자원은 점검 도메인 소유다.
 * 조회=전체 / 관리=ENGINEER+. 목록은 단순 배열(활성 항목만, seq 순).
 */
@RestController
@RequestMapping("/api/v1/equipments/{equipmentId}/checklist")
@RequiredArgsConstructor
public class ChecklistController {

    private final ChecklistService checklistService;

    @GetMapping
    public List<ChecklistItemResponse> getChecklist(@PathVariable Long equipmentId) {
        return checklistService.getChecklist(equipmentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ENGINEER','ADMIN')")
    public ChecklistItemResponse create(@PathVariable Long equipmentId,
                                        @Valid @RequestBody ChecklistItemCreateRequest request) {
        return checklistService.create(equipmentId, request);
    }

    @PutMapping("/{itemId}")
    @PreAuthorize("hasAnyRole('ENGINEER','ADMIN')")
    public ChecklistItemResponse update(@PathVariable Long equipmentId, @PathVariable Long itemId,
                                        @Valid @RequestBody ChecklistItemUpdateRequest request) {
        return checklistService.update(equipmentId, itemId, request);
    }
}
