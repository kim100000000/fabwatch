package com.fabwatch.simulator.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 시나리오 응답 (docs/06 §8).
 * param은 정규화된 결과(기본값 + 자동 계산된 slopePerSec/offset 포함)를 그대로 돌려준다 —
 * 시연자가 "지금 어떤 기울기로 흐르는 중인지" 화면에서 확인할 수 있어야 하기 때문.
 */
public record ScenarioResponse(
        Long id,
        Long sensorId,
        Long equipmentId,
        String equipmentCode,
        String sensorType,
        String type,
        Map<String, Object> param,
        boolean active,
        Instant startedAt,
        Instant endedAt) {
}
