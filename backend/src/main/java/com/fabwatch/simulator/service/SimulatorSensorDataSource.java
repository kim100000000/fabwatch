package com.fabwatch.simulator.service;

import com.fabwatch.sensor.service.SensorDataSource;
import com.fabwatch.sensor.service.SensorQueryService;
import com.fabwatch.sensor.service.SensorReading;
import com.fabwatch.sensor.service.SensorSpec;
import com.fabwatch.simulator.entity.SimulationScenario;
import com.fabwatch.simulator.repository.SimulationScenarioRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 센서 시뮬레이터 (docs/03 F-4.2) — SensorDataSource의 유일한 구현체 (docs/10 ADR-8).
 *
 * 설비×센서별로 {@code 평균 + N(0, σ)}를 생성하고, 활성 시나리오가 있으면 변형을 적용한다.
 * 기준값(base_value/noise_sigma)은 DB의 sensors 테이블에서 읽으므로 docs/03 4.1 표의 5설비 시드와
 * 자동으로 일치한다 — 코드에 수치를 하드코딩하지 않는다(가상값 원칙, docs/08 B-3).
 *
 * DRIFT는 durationMin(maxElapsedSec) 경과 후 목표값(crit 도달값)에서 고정된다 — 시나리오를 안 꺼도 무한 상승하지 않는다.
 *
 * 변형 적용 순서: STEP(기준선 이동) → DRIFT(시간 비례) → SPIKE(순간 급발).
 * SPIKE를 마지막에 두는 이유: 급발은 "그 순간의 최종값"에 얹히는 현상이기 때문.
 *
 * ★ 읽기 전용이다. 실제 설비로 값을 내보내는 코드는 없다 (docs/11 §10 안전 게이트).
 */
@Slf4j
@Service
public class SimulatorSensorDataSource implements SensorDataSource {

    private final SensorQueryService sensorQueryService;
    private final SimulationScenarioRepository scenarioRepository;
    private final ObjectMapper objectMapper;
    private final Random random;

    @org.springframework.beans.factory.annotation.Autowired
    public SimulatorSensorDataSource(SensorQueryService sensorQueryService,
                                     SimulationScenarioRepository scenarioRepository,
                                     ObjectMapper objectMapper) {
        this(sensorQueryService, scenarioRepository, objectMapper, new Random());
    }

    /** 테스트에서 시드 고정 Random을 주입하기 위한 생성자 */
    SimulatorSensorDataSource(SensorQueryService sensorQueryService,
                              SimulationScenarioRepository scenarioRepository,
                              ObjectMapper objectMapper,
                              Random random) {
        this.sensorQueryService = sensorQueryService;
        this.scenarioRepository = scenarioRepository;
        this.objectMapper = objectMapper;
        this.random = random;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SensorReading> read(Instant at) {
        List<SensorSpec> specs = sensorQueryService.findAllSpecs();
        if (specs.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SimulationScenario>> scenariosBySensor = new HashMap<>();
        for (SimulationScenario scenario : scenarioRepository.findByActiveTrueOrderByIdAsc()) {
            scenariosBySensor.computeIfAbsent(scenario.getSensorId(), key -> new ArrayList<>()).add(scenario);
        }

        List<SensorReading> readings = new ArrayList<>(specs.size());
        for (SensorSpec spec : specs) {
            double value = generate(spec, scenariosBySensor.getOrDefault(spec.sensorId(), List.of()), at);
            readings.add(new SensorReading(
                    spec.sensorId(),
                    spec.equipmentId(),
                    BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP),
                    at));
        }
        return readings;
    }

    @Override
    public String sourceName() {
        return "SIMULATOR";
    }

    /** 평균 + 가우시안 노이즈 → 시나리오 변형 (docs/03 F-4.2) */
    private double generate(SensorSpec spec, List<SimulationScenario> scenarios, Instant at) {
        double base = spec.baseValue() == null ? 0 : spec.baseValue().doubleValue();
        double sigma = spec.noiseSigma() == null ? 0 : spec.noiseSigma().doubleValue();
        double value = base + random.nextGaussian() * sigma;

        for (SimulationScenario scenario : ordered(scenarios)) {
            Map<String, Object> param = readParam(scenario.getParam());
            value = switch (scenario.getType()) {
                case STEP -> ScenarioValueCalculator.applyStep(value,
                        ScenarioParams.doubleValue(param, "offset", 0));
                case DRIFT -> ScenarioValueCalculator.applyDrift(value,
                        ScenarioParams.doubleValue(param, "slopePerSec", 0),
                        elapsedSeconds(scenario.getStartedAt(), at),
                        driftMaxElapsedSeconds(param));
                case SPIKE -> ScenarioValueCalculator.applySpike(value,
                        ScenarioParams.doubleValue(param, "probability", ScenarioParams.DEFAULT_PROBABILITY),
                        ScenarioParams.doubleValue(param, "multiplier", ScenarioParams.DEFAULT_MULTIPLIER),
                        random.nextDouble());
            };
        }
        return value;
    }

    /** STEP → DRIFT → SPIKE 순으로 정렬 */
    private static List<SimulationScenario> ordered(List<SimulationScenario> scenarios) {
        return scenarios.stream()
                .sorted(java.util.Comparator.comparingInt(s -> switch (s.getType()) {
                    case STEP -> 0;
                    case DRIFT -> 1;
                    case SPIKE -> 2;
                }))
                .toList();
    }

    /**
     * DRIFT 상한 시간(초). 정규화 시 저장한 maxElapsedSec를 우선 쓰고, 없는 과거 시나리오는 durationMin×60으로 대체한다.
     * 둘 다 없으면 0(상한 없음).
     */
    private static long driftMaxElapsedSeconds(Map<String, Object> param) {
        double maxElapsed = ScenarioParams.doubleValue(param, "maxElapsedSec", 0);
        if (maxElapsed > 0) {
            return Math.round(maxElapsed);
        }
        double durationMin = ScenarioParams.doubleValue(param, "durationMin", 0);
        return durationMin > 0 ? Math.round(durationMin * 60) : 0;
    }

    private static long elapsedSeconds(Instant startedAt, Instant at) {
        if (startedAt == null || at == null) {
            return 0;
        }
        return Math.max(0, java.time.Duration.between(startedAt, at).getSeconds());
    }

    private Map<String, Object> readParam(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("시나리오 파라미터 파싱 실패 — 변형 없이 진행: {}", json);
            return Map.of();
        }
    }
}
