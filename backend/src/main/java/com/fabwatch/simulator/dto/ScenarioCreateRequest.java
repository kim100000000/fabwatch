package com.fabwatch.simulator.dto;

import com.fabwatch.simulator.entity.SimulationScenario;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * POST /simulator/scenarios (docs/06 §8).
 * param은 선택 — 생략하면 유형별 기본값이 적용된다
 * (DRIFT {durationMin:10} / SPIKE {probability:0.1, multiplier:1.8} / STEP {offsetRatio:0.15}).
 */
public record ScenarioCreateRequest(
        @NotNull(message = "센서 ID는 필수입니다.")
        Long sensorId,

        @NotNull(message = "시나리오 유형은 필수입니다.")
        SimulationScenario.Type type,

        Map<String, Object> param) {
}
