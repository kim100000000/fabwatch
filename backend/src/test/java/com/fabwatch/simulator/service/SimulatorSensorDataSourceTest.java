package com.fabwatch.simulator.service;

import com.fabwatch.sensor.service.SensorQueryService;
import com.fabwatch.sensor.service.SensorReading;
import com.fabwatch.sensor.service.SensorSpec;
import com.fabwatch.simulator.config.SimulatorProperties;
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
                sensorQueryService, repository, objectMapper, new SimulatorProperties(null), new Random(1));
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
        assertThat(valueAt(PARAM_2MIN, 120 + 299)).isEqualTo(5.0); // plateau 유지 시간(기본 5분) 안에서는 계속 고정
    }

    @Test
    @DisplayName("maxElapsedSec가 없는 과거 시나리오도 durationMin×60을 상한으로 쓴다")
    void 과거_시나리오_호환() {
        String legacy = "{\"durationMin\":10,\"slopePerSec\":0.005}"; // 600초에 2.0 → 5.0
        assertThat(valueAt(legacy, 600)).isEqualTo(5.0);
        assertThat(valueAt(legacy, 600 + 299)).isEqualTo(5.0); // plateau 유지 시간 안
    }

    // ---- 수집 측 방어 (poison pill): 이상 시나리오가 있어도 수집이 죽지 않는다 ----

    private List<SensorReading> readWith(String paramJson, SimulationScenario.Type type, long secondsAfterStart) {
        SensorQueryService sensorQueryService = mock(SensorQueryService.class);
        SimulationScenarioRepository repository = mock(SimulationScenarioRepository.class);
        when(sensorQueryService.findAllSpecs()).thenReturn(List.of(VIB));
        SimulationScenario scenario = SimulationScenario.builder()
                .sensorId(7L).type(type).param(paramJson)
                .active(true).startedAt(STARTED).build();
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(scenario));
        SimulatorSensorDataSource source = new SimulatorSensorDataSource(
                sensorQueryService, repository, objectMapper, new SimulatorProperties(null), new Random(1));
        return source.read(STARTED.plusSeconds(secondsAfterStart));
    }

    @Test
    @DisplayName("범위 밖 기울기(수억 이상) — 해당 센서만 기본값(base+노이즈)으로 대체되고 수집은 계속된다")
    void 범위_초과_값은_기본값으로_대체() {
        String poison = "{\"durationMin\":240,\"slopePerSec\":1e12,\"maxElapsedSec\":14400}";
        List<SensorReading> readings = readWith(poison, SimulationScenario.Type.DRIFT, 3600);

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).value()).isEqualByComparingTo("2.00"); // σ=0 → base
    }

    @Test
    @DisplayName("NaN·Infinity 문자열 파라미터 — 예외 없이 기본값으로 대체")
    void 비유한_값은_기본값으로_대체() {
        assertThat(readWith("{\"offset\":\"NaN\"}", SimulationScenario.Type.STEP, 0).get(0).value())
                .isEqualByComparingTo("2.00");
        assertThat(readWith("{\"slopePerSec\":\"Infinity\",\"durationMin\":10}",
                SimulationScenario.Type.DRIFT, 100).get(0).value()).isEqualByComparingTo("2.00");
        assertThat(readWith("{\"probability\":1,\"multiplier\":\"1e999\"}",
                SimulationScenario.Type.SPIKE, 0).get(0).value()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("반올림 후에도 decimal(10,2) 경계(99,999,999.99)를 넘으면 대체, 경계 안이면 그대로")
    void 저장_범위_경계() {
        assertThat(SimulatorSensorDataSource.toStorable(99_999_999.99)).isEqualByComparingTo("99999999.99");
        assertThat(SimulatorSensorDataSource.toStorable(-99_999_999.99)).isEqualByComparingTo("-99999999.99");
        assertThat(SimulatorSensorDataSource.toStorable(99_999_999.995)).isNull(); // 반올림하면 1억 → 자릿수 초과
        assertThat(SimulatorSensorDataSource.toStorable(1e8)).isNull();
        assertThat(SimulatorSensorDataSource.toStorable(Double.NaN)).isNull();
        assertThat(SimulatorSensorDataSource.toStorable(Double.POSITIVE_INFINITY)).isNull();
    }

    @Test
    @DisplayName("깨진 파라미터 JSON — 변형 없이 기본값, 한 센서 문제가 다른 센서 수집을 막지 않는다")
    void 한_센서의_이상이_다른_센서를_막지_않는다() {
        SensorSpec other = new SensorSpec(8L, 10L, "TEMP", "C", new BigDecimal("50.0"), BigDecimal.ZERO,
                null, null, null, null);
        SensorQueryService sensorQueryService = mock(SensorQueryService.class);
        SimulationScenarioRepository repository = mock(SimulationScenarioRepository.class);
        when(sensorQueryService.findAllSpecs()).thenReturn(List.of(VIB, other));
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(
                SimulationScenario.builder().sensorId(7L).type(SimulationScenario.Type.DRIFT)
                        .param("{\"slopePerSec\":1e300,\"durationMin\":10}").active(true).startedAt(STARTED).build(),
                SimulationScenario.builder().sensorId(8L).type(SimulationScenario.Type.STEP)
                        .param("{broken json").active(true).startedAt(STARTED).build()));

        List<SensorReading> readings = new SimulatorSensorDataSource(
                sensorQueryService, repository, objectMapper, new SimulatorProperties(null), new Random(1)).read(STARTED.plusSeconds(100));

        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).value()).isEqualByComparingTo("2.00");
        assertThat(readings.get(1).value()).isEqualByComparingTo("50.00");
    }

    // ---- 시나리오 자동 만료 (안정성 감사 M-13) ----

    private record Result(List<SensorReading> readings, SimulationScenario scenario) {
    }

    private Result readScenario(SimulationScenario.Type type, String paramJson, Instant startedAt,
                                SimulatorProperties props, Instant at) {
        SensorQueryService sensorQueryService = mock(SensorQueryService.class);
        SimulationScenarioRepository repository = mock(SimulationScenarioRepository.class);
        when(sensorQueryService.findAllSpecs()).thenReturn(List.of(VIB));
        SimulationScenario scenario = SimulationScenario.builder()
                .sensorId(7L).type(type).param(paramJson).active(true).startedAt(startedAt).build();
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(scenario));
        List<SensorReading> readings = new SimulatorSensorDataSource(
                sensorQueryService, repository, objectMapper, props, new Random(1)).read(at);
        return new Result(readings, scenario);
    }

    @Test
    @DisplayName("★ DRIFT: plateau 유지 시간(기본 5분) 직전까지는 고정값·활성, 정확히 만료 시각부터 정상값 복귀·비활성")
    void DRIFT_만료_직전_직후() {
        SimulatorProperties props = new SimulatorProperties(null); // plateau-hold 5분
        // 상한 120초 + 유지 300초 = 420초
        Result before = readScenario(SimulationScenario.Type.DRIFT, PARAM_2MIN, STARTED, props, STARTED.plusSeconds(419));
        assertThat(before.readings().get(0).value().doubleValue()).isEqualTo(5.0);
        assertThat(before.scenario().isActive()).isTrue();

        Result after = readScenario(SimulationScenario.Type.DRIFT, PARAM_2MIN, STARTED, props, STARTED.plusSeconds(420));
        assertThat(after.readings().get(0).value().doubleValue()).as("만료 후 정상값(base 2.0)").isEqualTo(2.0);
        assertThat(after.scenario().isActive()).isFalse();
        assertThat(after.scenario().getEndedAt()).isEqualTo(STARTED.plusSeconds(420));
    }

    @Test
    @DisplayName("plateau-hold-minutes 설정이 만료 시각에 반영된다 (1분 → 120+60초)")
    void DRIFT_유지시간_설정() {
        SimulatorProperties props = new SimulatorProperties(2, 1, 30);

        assertThat(readScenario(SimulationScenario.Type.DRIFT, PARAM_2MIN, STARTED, props, STARTED.plusSeconds(179))
                .scenario().isActive()).isTrue();
        assertThat(readScenario(SimulationScenario.Type.DRIFT, PARAM_2MIN, STARTED, props, STARTED.plusSeconds(180))
                .scenario().isActive()).isFalse();
    }

    @Test
    @DisplayName("maxElapsedSec 없는 과거 DRIFT는 durationMin×60 + 유지 시간으로 만료된다")
    void 과거_DRIFT_만료() {
        String legacy = "{\"durationMin\":10,\"slopePerSec\":0.005}"; // 600초 + 300초 = 900초
        SimulatorProperties props = new SimulatorProperties(null);

        assertThat(readScenario(SimulationScenario.Type.DRIFT, legacy, STARTED, props, STARTED.plusSeconds(899))
                .scenario().isActive()).isTrue();
        Result expired = readScenario(SimulationScenario.Type.DRIFT, legacy, STARTED, props, STARTED.plusSeconds(900));
        assertThat(expired.scenario().isActive()).isFalse();
        assertThat(expired.readings().get(0).value().doubleValue()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("상한 정보가 전혀 없는 DRIFT(파라미터 비어 있음)도 최대 수명(기본 30분)으로 만료된다")
    void 상한정보_없는_DRIFT는_최대수명() {
        SimulatorProperties props = new SimulatorProperties(null);

        assertThat(readScenario(SimulationScenario.Type.DRIFT, "{}", STARTED, props, STARTED.plusSeconds(30 * 60 - 1))
                .scenario().isActive()).isTrue();
        assertThat(readScenario(SimulationScenario.Type.DRIFT, "{}", STARTED, props, STARTED.plusSeconds(30 * 60))
                .scenario().isActive()).isFalse();
    }

    @Test
    @DisplayName("★ STEP/SPIKE는 최대 수명(기본 30분) 후 만료 — 직전엔 변형이 적용되고 직후엔 정상값")
    void STEP_SPIKE_최대수명() {
        SimulatorProperties props = new SimulatorProperties(null);
        String step = "{\"offset\":10}";

        Result before = readScenario(SimulationScenario.Type.STEP, step, STARTED, props, STARTED.plusSeconds(30 * 60 - 1));
        assertThat(before.readings().get(0).value().doubleValue()).isEqualTo(12.0); // base 2.0 + 10
        assertThat(before.scenario().isActive()).isTrue();

        Result after = readScenario(SimulationScenario.Type.STEP, step, STARTED, props, STARTED.plusSeconds(30 * 60));
        assertThat(after.readings().get(0).value().doubleValue()).isEqualTo(2.0);
        assertThat(after.scenario().isActive()).isFalse();

        String spike = "{\"probability\":1,\"multiplier\":2}";
        assertThat(readScenario(SimulationScenario.Type.SPIKE, spike, STARTED, props, STARTED.plusSeconds(60))
                .readings().get(0).value().doubleValue()).isEqualTo(4.0);
        assertThat(readScenario(SimulationScenario.Type.SPIKE, spike, STARTED, props, STARTED.plusSeconds(30 * 60))
                .readings().get(0).value().doubleValue()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("★ 재시작 후 복원: DB에 active=true로 남은 오래된 시나리오는 첫 수집 틱에서 만료 처리되어 정상값이 나온다")
    void 재시작_후_오래된_시나리오_복원은_만료() {
        Instant yesterday = STARTED.minusSeconds(24 * 3600);

        Result restored = readScenario(SimulationScenario.Type.DRIFT, PARAM_2MIN, yesterday,
                new SimulatorProperties(null), STARTED);

        assertThat(restored.readings().get(0).value().doubleValue()).isEqualTo(2.0);
        assertThat(restored.scenario().isActive()).isFalse();
    }

    @Test
    @DisplayName("한 센서의 시나리오가 만료돼도 같은 센서의 다른 활성 시나리오는 계속 적용된다")
    void 만료는_시나리오_단위() {
        SensorQueryService sensorQueryService = mock(SensorQueryService.class);
        SimulationScenarioRepository repository = mock(SimulationScenarioRepository.class);
        when(sensorQueryService.findAllSpecs()).thenReturn(List.of(VIB));
        SimulationScenario oldStep = SimulationScenario.builder().sensorId(7L).type(SimulationScenario.Type.STEP)
                .param("{\"offset\":10}").active(true).startedAt(STARTED.minusSeconds(3600)).build();
        SimulationScenario freshStep2 = SimulationScenario.builder().sensorId(7L).type(SimulationScenario.Type.DRIFT)
                .param(PARAM_2MIN).active(true).startedAt(STARTED).build();
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(oldStep, freshStep2));

        List<SensorReading> readings = new SimulatorSensorDataSource(sensorQueryService, repository, objectMapper,
                new SimulatorProperties(null), new Random(1)).read(STARTED.plusSeconds(60));

        assertThat(oldStep.isActive()).isFalse();
        assertThat(freshStep2.isActive()).isTrue();
        assertThat(readings.get(0).value().doubleValue()).isEqualTo(3.5); // DRIFT만 적용 (STEP 10은 빠짐)
    }
}
