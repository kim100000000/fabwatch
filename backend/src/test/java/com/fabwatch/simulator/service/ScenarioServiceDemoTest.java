package com.fabwatch.simulator.service;

import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.sensor.service.SensorQueryService;
import com.fabwatch.sensor.service.SensorSpec;
import com.fabwatch.simulator.config.SimulatorProperties;
import com.fabwatch.simulator.entity.SimulationScenario;
import com.fabwatch.simulator.repository.SimulationScenarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 데모 자동 시작(FR-4.5)의 DRIFT 지속 시간 — 설정값 기본 2분, 이미 주입돼 있으면 건드리지 않는다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScenarioServiceDemoTest {

    private static final SensorSpec VIBRATION = new SensorSpec(7L, 10L, "VIBRATION", "mm/s",
            new BigDecimal("2.0"), new BigDecimal("0.3"),
            new BigDecimal("3.0"), new BigDecimal("4.0"), null, new BigDecimal("5.0"));

    @Mock
    private SimulationScenarioRepository scenarioRepository;
    @Mock
    private SensorQueryService sensorQueryService;
    @Mock
    private EquipmentQueryService equipmentQueryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        given(sensorQueryService.findAllSpecs()).willReturn(List.of(VIBRATION));
        given(sensorQueryService.findSpec(7L)).willReturn(Optional.of(VIBRATION));
        given(scenarioRepository.save(any(SimulationScenario.class))).willAnswer(call -> call.getArgument(0));
    }

    private ScenarioService service(SimulatorProperties properties) {
        return new ScenarioService(scenarioRepository, sensorQueryService, equipmentQueryService,
                objectMapper, properties);
    }

    @Test
    @DisplayName("설정 기본값은 2분 (미설정/0 이하 → 2)")
    void 설정_기본값() {
        assertThat(new SimulatorProperties(null).demoDurationMin()).isEqualTo(2);
        assertThat(new SimulatorProperties(0).demoDurationMin()).isEqualTo(2);
        assertThat(new SimulatorProperties(5).demoDurationMin()).isEqualTo(5);
    }

    @Test
    @DisplayName("데모 시작은 진동 센서에 설정된 durationMin(기본 2분)으로 DRIFT를 주입하고 상한(120초)을 저장한다")
    void 데모_기본_2분_주입() throws Exception {
        given(scenarioRepository.existsBySensorIdAndTypeAndActiveTrue(7L, SimulationScenario.Type.DRIFT))
                .willReturn(false);
        given(scenarioRepository.findByActiveTrueOrderByIdAsc()).willReturn(List.of());

        service(new SimulatorProperties(null)).startDemo(null);

        var captor = org.mockito.ArgumentCaptor.forClass(SimulationScenario.class);
        verify(scenarioRepository).save(captor.capture());
        Map<?, ?> param = objectMapper.readValue(captor.getValue().getParam(), Map.class);
        assertThat(captor.getValue().getType()).isEqualTo(SimulationScenario.Type.DRIFT);
        assertThat(((Number) param.get("durationMin")).intValue()).isEqualTo(2);
        assertThat(((Number) param.get("maxElapsedSec")).longValue()).isEqualTo(120L);
        // 기울기 = (crit_high 5.0 + 노이즈 1σ 0.3 − base 2.0) / 120초
        assertThat(((Number) param.get("slopePerSec")).doubleValue()).isCloseTo(3.3 / 120, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("설정으로 durationMin을 바꿀 수 있다 (예: 4분 → 상한 240초)")
    void 데모_설정값_반영() throws Exception {
        given(scenarioRepository.existsBySensorIdAndTypeAndActiveTrue(7L, SimulationScenario.Type.DRIFT))
                .willReturn(false);
        given(scenarioRepository.findByActiveTrueOrderByIdAsc()).willReturn(List.of());

        service(new SimulatorProperties(4)).startDemo(null);

        var captor = org.mockito.ArgumentCaptor.forClass(SimulationScenario.class);
        verify(scenarioRepository).save(captor.capture());
        Map<?, ?> param = objectMapper.readValue(captor.getValue().getParam(), Map.class);
        assertThat(((Number) param.get("maxElapsedSec")).longValue()).isEqualTo(240L);
    }

    @Test
    @DisplayName("이미 DRIFT가 주입돼 있으면 그대로 두고 현재 시나리오 목록만 반환한다")
    void 이미_주입돼_있으면_유지() {
        given(scenarioRepository.existsBySensorIdAndTypeAndActiveTrue(7L, SimulationScenario.Type.DRIFT))
                .willReturn(true);
        given(scenarioRepository.findByActiveTrueOrderByIdAsc()).willReturn(List.of());

        var result = service(new SimulatorProperties(2)).startDemo(null);

        verify(scenarioRepository, never()).save(any());
        assertThat(result.content()).isEmpty();
    }
}
