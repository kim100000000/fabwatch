package com.fabwatch.equipment.controller;

import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.equipment.dto.LineTreeResponse;
import com.fabwatch.equipment.service.EquipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 라인 API (docs/06 §2) — 권한: 로그인한 전체 역할 */
@RestController
@RequestMapping("/api/v1/lines")
@RequiredArgsConstructor
public class LineController {

    private final EquipmentService equipmentService;

    /** 라인+공정+설비 트리 전체. 페이징은 없지만 목록 공통 포맷으로 감싼다 (docs/06 목록 공통 규칙). */
    @GetMapping
    public PageResponse<LineTreeResponse> getLines() {
        return equipmentService.getLineTree();
    }
}
