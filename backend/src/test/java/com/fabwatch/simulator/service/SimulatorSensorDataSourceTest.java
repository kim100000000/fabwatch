package com.fabwatch.simulator.service;

import com.fabwatch.sensor.service.SensorQueryService;
import com.fabwatch.sensor.service.SensorReading;
import com.fabwatch.sensor.service.SensorSpec;
import com.fabwatch.simulator.entity.SimulationScenario;
import com.fabwatch.simulator.repository.SimulationScenarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DRIFT plateau가 실제 값 생성 경로(호출부)에도 적용되는지 — 노이즈 σ=0으로 결정적 검증. */
class SimulatorSensorDataSourceTest {

    private static final SensorSpec VIB = new SensorSpec(7L, 10L, "VIBRATION", "mm/s",
            new BigDecimal("2.0"), BigDecimal.ZERO,
            new BigDecimal("3.0"), new BigDecimal("4.0"), null, new BigDecimal("5.0"));

    private static final Instant STARTED = Instant.parse("2026-10-05T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private double valueAt(String paramJson, long secondsAfterStart) {
        SensorQueryService sensorQueryService = mock(SensorQueryService.class);
        SimulationScenarioRepository repository = mock(SimulationScenarioRepository.class);
        when(sensorQueryService.findAllSpecs()).thenReturn(List.of(VIB));
        SimulationScenario scenario = SimulationScenario.builder()
                .sensorId(7L).type(SimulationScenario.Type.DRIFT).param(paramJson)
                .active(true).startedAt(STARTED).build();
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(scenario));

        SimulatorSensorDataSource source = new SimulatorSensorDataSource(
                sensorQueryService, repository, objectMapper, new Random(1));
        List<SensorReading> readings = source.read(STARTED.plusSeconds(secondsAfterStart));
        return readings.get(0).value().doubleValue();
    }

    // durationMin 2 → 120초에 base 2.0 → crit 5.0 (slope 0.025/초)
    private static final String PARAM_2MIN =
            "{\"durationMin\":2,\"slopePerSec\":0.025,\"maxElapsedSec\":120,\"targetValue\":5.0}";

    @Test
    @DisplayName("상한 도달 전에는 선형 상승, 도달 후에는 목표값(5.0)에서 고정")
    void 상한_전후() {
        assertThat(valueAt(PARAM_2MIN, 0)).isEqualTo(2.0);
        assertThat(valueAt(PARAM_2MIN, 60)).isEqualTo(3.5);
        assertThat(valueAt(PARAM_2MIN, 120)).isEqualTo(5.0);
        assertThat(valueAt(PARAM_2MIN, 121)).isEqualTo(5.0);
        assertThat(valueAt(PARAM_2MIN, 3 * 3600)).isEqualTo(5.0); // 수 시간 뒤에도 무한 상승 없음
    }

    @Test
    @DisplayName("maxElapsedSec가 없는 과거 시나리오도 durationMin×60을 상한으로 쓴다")
    void 과거_시나리오_호환() {
        String legacy = "{\"durationMin\":10,\"slopePerSec\":0.005}"; // 600초에 2.0 → 5.0
        assertThat(valueAt(legacy, 600)).isEqualTo(5.0);
        assertThat(valueAt(legacy, 7200)).isEqualTo(5.0);
    }
}
