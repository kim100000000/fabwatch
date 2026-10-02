package com.fabwatch.inspection.controller;

import com.fabwatch.inspection.dto.PmScheduleResponse;
import com.fabwatch.inspection.dto.PmScheduleUpdateRequest;
import com.fabwatch.inspection.service.PmScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** PM 스케줄 API (docs/06 §5). 조회=전체(단순 배열) / 설정=ENGINEER+ */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PmScheduleController {

    private final PmScheduleService pmScheduleService;

    @GetMapping("/pm-schedules")
    public List<PmScheduleResponse> getSchedules(
            @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(required = false) Long equipmentId) {
        return pmScheduleService.getSchedules(overdueOnly, equipmentId);
    }

    @PutMapping("/equipments/{equipmentId}/pm-schedule")
    @PreAuthorize("hasAnyRole('ENGINEER','ADMIN')")
    public PmScheduleResponse upsert(@PathVariable Long equipmentId, @Valid @RequestBody PmScheduleUpdateRequest request) {
        return pmScheduleService.upsert(equipmentId, request);
    }
}
