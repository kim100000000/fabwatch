package com.fabwatch.simulator.service;

import com.fabwatch.simulator.config.SimulatorProperties;
import com.fabwatch.simulator.entity.SimulationScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 시나리오 만료 시각 계산·설정 기본값 (안정성 감사 M-13) */
class ScenarioExpiryTest {

    private static final Instant STARTED = Instant.parse("2026-10-05T00:00:00Z");

    private static SimulationScenario scenario(SimulationScenario.Type type, Instant startedAt) {
        return SimulationScenario.builder().sensorId(1L).type(type).active(true).startedAt(startedAt).build();
    }

    @Test
    @DisplayName("설정 기본값: plateau 유지 5분 / STEP·SPIKE 최대 수명 30분, 0 이하는 기본값")
    void 설정_기본값() {
        SimulatorProperties defaults = new SimulatorProperties(null, null, null);
        assertThat(defaults.plateauHoldMinutes()).isEqualTo(5);
        assertThat(defaults.maxLifetimeMinutes()).isEqualTo(30);
        assertThat(new SimulatorProperties(2, 0, -1).plateauHoldMinutes()).isEqualTo(5);
        assertThat(new SimulatorProperties(2, 0, -1).maxLifetimeMinutes()).isEqualTo(30);
        assertThat(new SimulatorProperties(7, 2, 10).plateauHoldMinutes()).isEqualTo(2);
    }

    @Test
    @DisplayName("DRIFT 만료 = 시작 + maxElapsedSec + plateau 유지, durationMin 대체, 상한 정보 없으면 최대 수명")
    void DRIFT_만료_시각() {
        SimulatorProperties props = new SimulatorProperties(null);
        SimulationScenario drift = scenario(SimulationScenario.Type.DRIFT, STARTED);

        assertThat(ScenarioExpiry.expiresAt(drift, Map.of("maxElapsedSec", 120), props))
                .isEqualTo(STARTED.plusSeconds(120 + 300));
        assertThat(ScenarioExpiry.expiresAt(drift, Map.of("durationMin", 10), props))
                .isEqualTo(STARTED.plusSeconds(600 + 300));
        assertThat(ScenarioExpiry.expiresAt(drift, Map.of(), props)).isEqualTo(STARTED.plusSeconds(30 * 60));
    }

    @Test
    @DisplayName("STEP/SPIKE 만료 = 시작 + 최대 수명")
    void STEP_SPIKE_만료_시각() {
        SimulatorProperties props = new SimulatorProperties(2, 5, 10);

        assertThat(ScenarioExpiry.expiresAt(scenario(SimulationScenario.Type.STEP, STARTED), Map.of(), props))
                .isEqualTo(STARTED.plusSeconds(600));
        assertThat(ScenarioExpiry.expiresAt(scenario(SimulationScenario.Type.SPIKE, STARTED), Map.of(), props))
                .isEqualTo(STARTED.plusSeconds(600));
    }

    @Test
    @DisplayName("시작 시각을 알 수 없으면 만료 없음(null), 만료 판정은 '정확히 만료 시각'부터 true")
    void 시작시각_없음과_경계() {
        SimulatorProperties props = new SimulatorProperties(null);
        assertThat(ScenarioExpiry.expiresAt(scenario(SimulationScenario.Type.STEP, null), Map.of(), props)).isNull();

        Instant expiresAt = STARTED.plusSeconds(100);
        assertThat(ScenarioExpiry.isExpired(expiresAt, expiresAt.minusMillis(1))).isFalse();
        assertThat(ScenarioExpiry.isExpired(expiresAt, expiresAt)).isTrue();
        assertThat(ScenarioExpiry.isExpired(null, expiresAt)).isFalse();
    }
}
