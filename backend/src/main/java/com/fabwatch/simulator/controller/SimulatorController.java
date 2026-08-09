package com.fabwatch.simulator.controller;

import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.simulator.dto.ScenarioCreateRequest;
import com.fabwatch.simulator.dto.ScenarioResponse;
import com.fabwatch.simulator.service.ScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시뮬레이터 데모 제어 API (docs/06 §8). 권한 ENGINEER+ (= ADMIN·ENGINEER, docs/11 §3).
 */
@RestController
@RequestMapping("/api/v1/simulator")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
public class SimulatorController {

    private final ScenarioService scenarioService;

    @GetMapping("/scenarios")
    public PageResponse<ScenarioResponse> getScenarios() {
        return scenarioService.getActiveScenarios();
    }

    @PostMapping("/scenarios")
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioResponse inject(@Valid @RequestBody ScenarioCreateRequest request) {
        return scenarioService.inject(request);
    }

    @DeleteMapping("/scenarios/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable Long id) {
        scenarioService.release(id);
    }

    /** 데모 자동 시나리오 시작 (FR-4.5, Should) — 활성 시나리오 목록을 돌려준다. */
    @PostMapping("/demo")
    public PageResponse<ScenarioResponse> startDemo(@RequestParam(required = false) Long equipmentId) {
        return scenarioService.startDemo(equipmentId);
    }
}
