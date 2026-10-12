package com.fabwatch.simulator.service;

import com.fabwatch.sensor.service.SensorQueryService;
import com.fabwatch.sensor.service.SensorReading;
import com.fabwatch.sensor.service.SensorSpec;
import com.fabwatch.simulator.config.SimulatorProperties;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SIMULATOR_ENABLED 운영 스위치 — false면 센서 값을 만들지 않고, 기본(생략)은 켜짐. */
class SimulatorEnabledSwitchTest {

    private static final SensorSpec VIB = new SensorSpec(7L, 10L, "VIBRATION", "mm/s",
            new BigDecimal("2.0"), BigDecimal.ZERO,
            new BigDecimal("3.0"), new BigDecimal("4.0"), null, new BigDecimal("5.0"));

    @Test
    @DisplayName("enabled 생략/null이면 기본 true")
    void 기본값_켜짐() {
        assertThat(new SimulatorProperties(null).enabled()).isTrue();
        assertThat(new SimulatorProperties(2, 5, 30).enabled()).isTrue();
        assertThat(new SimulatorProperties(2, 5, 30, null).enabled()).isTrue();
    }

    @Test
    @DisplayName("enabled=false면 수집 틱에서 빈 목록 — 센서 조회·DB 접근 자체를 하지 않는다")
    void 꺼짐() {
        SensorQueryService sensorQueryService = mock(SensorQueryService.class);
        SimulationScenarioRepository repository = mock(SimulationScenarioRepository.class);
        SimulatorSensorDataSource source = new SimulatorSensorDataSource(sensorQueryService, repository,
                new ObjectMapper(), new SimulatorProperties(2, 5, 30, false), new Random(1));

        assertThat(source.read(Instant.parse("2026-10-12T00:00:00Z"))).isEmpty();
        verifyNoInteractions(sensorQueryService, repository);
    }

    @Test
    @DisplayName("enabled=true면 정상 생성")
    void 켜짐() {
        SensorQueryService sensorQueryService = mock(SensorQueryService.class);
        SimulationScenarioRepository repository = mock(SimulationScenarioRepository.class);
        when(sensorQueryService.findAllSpecs()).thenReturn(List.of(VIB));
        SimulatorSensorDataSource source = new SimulatorSensorDataSource(sensorQueryService, repository,
                new ObjectMapper(), new SimulatorProperties(2, 5, 30, true), new Random(1));

        List<SensorReading> readings = source.read(Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(readings).hasSize(1);
    }
}
