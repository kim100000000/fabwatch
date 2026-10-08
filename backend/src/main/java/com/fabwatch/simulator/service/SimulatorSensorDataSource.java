package com.fabwatch.simulator.service;

import com.fabwatch.common.util.LogSanitizer;
import com.fabwatch.sensor.service.SensorDataSource;
import com.fabwatch.sensor.service.SensorQueryService;
import com.fabwatch.sensor.service.SensorReading;
import com.fabwatch.sensor.service.SensorSpec;
import com.fabwatch.simulator.config.SimulatorProperties;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 센서 시뮬레이터 (docs/03 F-4.2) — SensorDataSource의 유일한 구현체 (docs/10 ADR-8).
 *
 * 설비×센서별로 {@code 평균 + N(0, σ)}를 생성하고, 활성 시나리오가 있으면 변형을 적용한다.
 * 기준값(base_value/noise_sigma)은 DB의 sensors 테이블에서 읽으므로 docs/03 4.1 표의 5설비 시드와
 * 자동으로 일치한다 — 코드에 수치를 하드코딩하지 않는다(가상값 원칙, docs/08 B-3).
 *
 * DRIFT는 durationMin(maxElapsedSec) 경과 후 목표값(crit 도달값)에서 고정(plateau)되고, 거기서 plateau-hold-minutes(기본 5분)가
 * 지나면 시나리오가 자동 비활성화돼 정상값으로 돌아온다. STEP/SPIKE는 max-lifetime-minutes(기본 30분) 뒤 자동 비활성화.
 * 만료 판정은 매 수집 틱(read)에서 하므로 서버 재시작 후 복원된 오래된 시나리오도 첫 틱에 정리된다 (ScenarioExpiry).
 *
 * 변형 적용 순서: STEP(기준선 이동) → DRIFT(시간 비례) → SPIKE(순간 급발).
 * SPIKE를 마지막에 두는 이유: 급발은 "그 순간의 최종값"에 얹히는 현상이기 때문.
 *
 * ★ 방어(보안 감사 M-1): 시나리오 파라미터가 비정상이어도(예전에 검증 없이 저장된 DB 값 포함) 수집이 멈추지 않는다.
 *   센서별로 계산 결과가 유한수이고 decimal(10,2) 범위(±99,999,999.99) 안인지 검사해 벗어나면 그 센서만
 *   기본값(base+노이즈)으로 대체하고 WARN을 센서당 1회 남긴다. 한 센서의 예외가 다른 센서 수집을 막지도 않는다.
 *
 * ★ 읽기 전용이다. 실제 설비로 값을 내보내는 코드는 없다 (docs/11 §10 안전 게이트).
 */
@Slf4j
@Service
public class SimulatorSensorDataSource implements SensorDataSource {

    /** sensor_data.value decimal(10,2)의 최대 절대값 */
    private static final BigDecimal MAX_STORABLE = new BigDecimal("99999999.99");

    private final SensorQueryService sensorQueryService;
    private final SimulationScenarioRepository scenarioRepository;
    private final ObjectMapper objectMapper;
    private final SimulatorProperties properties;
    private final Random random;
    /** 이상 값으로 기본값 대체 경고를 이미 남긴 센서 — 2초마다 같은 경고가 쌓이지 않게 센서당 1회만 기록한다 */
    private final Set<Long> warnedSensors = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired
    public SimulatorSensorDataSource(SensorQueryService sensorQueryService,
                                     SimulationScenarioRepository scenarioRepository,
                                     ObjectMapper objectMapper,
                                     SimulatorProperties properties) {
        this(sensorQueryService, scenarioRepository, objectMapper, properties, new Random());
    }

    /** 테스트에서 시드 고정 Random을 주입하기 위한 생성자 */
    SimulatorSensorDataSource(SensorQueryService sensorQueryService,
                              SimulationScenarioRepository scenarioRepository,
                              ObjectMapper objectMapper,
                              SimulatorProperties properties,
                              Random random) {
        this.sensorQueryService = sensorQueryService;
        this.scenarioRepository = scenarioRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.random = random;
    }

    /**
     * 만료된 시나리오를 비활성화하므로 쓰기 가능 트랜잭션이다(짧은 트랜잭션 — 수집 틱의 저장·SSE와는 분리돼 있다).
     */
    @Override
    @Transactional
    public List<SensorReading> read(Instant at) {
        List<SensorSpec> specs = sensorQueryService.findAllSpecs();
        if (specs.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SimulationScenario>> scenariosBySensor = new HashMap<>();
        for (SimulationScenario scenario : scenarioRepository.findByActiveTrueOrderByIdAsc()) {
            if (expireIfDue(scenario, at)) {
                continue; // 만료된 시나리오는 이번 틱부터 적용하지 않는다 → 정상값으로 복귀
            }
            scenariosBySensor.computeIfAbsent(scenario.getSensorId(), key -> new ArrayList<>()).add(scenario);
        }

        List<SensorReading> readings = new ArrayList<>(specs.size());
        for (SensorSpec spec : specs) {
            readings.add(new SensorReading(
                    spec.sensorId(),
                    spec.equipmentId(),
                    safeValue(spec, scenariosBySensor.getOrDefault(spec.sensorId(), List.of()), at),
                    at));
        }
        return readings;
    }

    /**
     * 만료 시각이 지났으면 active=false로 내리고 true를 반환한다 (재시작 후 복원된 오래된 시나리오도 첫 틱에 정리된다).
     * ended_at에는 실제 만료 시각(계산값)을 남긴다. 사용자의 수동 해제(DELETE)와는 독립이며 둘이 겹쳐도 무해하다.
     */
    private boolean expireIfDue(SimulationScenario scenario, Instant at) {
        Instant expiresAt = ScenarioExpiry.expiresAt(scenario, readParamQuietly(scenario.getParam()), properties);
        if (!ScenarioExpiry.isExpired(expiresAt, at)) {
            return false;
        }
        scenario.deactivate(expiresAt);
        log.info("시나리오 자동 만료: id={}, sensorId={}, type={}, 만료 시각={}",
                scenario.getId(), scenario.getSensorId(), scenario.getType(), expiresAt);
        return true;
    }

    /** 시나리오 적용 값을 계산하되, 이상 값이면 기본값(base+노이즈)으로 대체한다. */
    private BigDecimal safeValue(SensorSpec spec, List<SimulationScenario> scenarios, Instant at) {
        double base = spec.baseValue() == null ? 0 : spec.baseValue().doubleValue();
        double sigma = spec.noiseSigma() == null ? 0 : spec.noiseSigma().doubleValue();
        double baseline = base + random.nextGaussian() * sigma;

        BigDecimal stored = null;
        String problem = null;
        try {
            stored = toStorable(generate(baseline, scenarios, at));
            if (stored == null) {
                problem = "계산 결과가 유한수가 아니거나 저장 범위(±99,999,999.99)를 벗어남";
            }
        } catch (RuntimeException e) {
            problem = "계산 중 예외 " + e.getClass().getSimpleName();
        }
        if (stored != null) {
            return stored;
        }
        if (warnedSensors.add(spec.sensorId())) {
            log.warn("시나리오 파라미터 이상 — 기본값으로 대체: sensorId={}, 사유={} (이 센서는 이후 반복 경고를 생략합니다)",
                    spec.sensorId(), LogSanitizer.clean(problem));
        }
        // 기본값 자체도 방어한다(base/σ가 비정상으로 저장된 경우 0으로 — 수집이 멈추는 것보다 낫다)
        BigDecimal fallback = toStorable(baseline);
        return fallback != null ? fallback : BigDecimal.ZERO.setScale(2);
    }

    /** 유한수이고 소수 2자리 반올림 후에도 decimal(10,2)에 들어가면 값, 아니면 null */
    static BigDecimal toStorable(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        BigDecimal scaled = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
        return scaled.abs().compareTo(MAX_STORABLE) > 0 ? null : scaled;
    }

    @Override
    public String sourceName() {
        return "SIMULATOR";
    }

    /** 기준 값(평균+노이즈)에 시나리오 변형을 적용한다 (docs/03 F-4.2) */
    private double generate(double baseline, List<SimulationScenario> scenarios, Instant at) {
        double value = baseline;
        for (SimulationScenario scenario : ordered(scenarios)) {
            Map<String, Object> param = readParam(scenario.getParam());
            value = switch (scenario.getType()) {
                case STEP -> ScenarioValueCalculator.applyStep(value,
                        ScenarioParams.doubleValue(param, "offset", 0));
                case DRIFT -> ScenarioValueCalculator.applyDrift(value,
                        ScenarioParams.doubleValue(param, "slopePerSec", 0),
                        elapsedSeconds(scenario.getStartedAt(), at),
                        ScenarioParams.driftMaxElapsedSeconds(param));
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

    private static long elapsedSeconds(Instant startedAt, Instant at) {
        if (startedAt == null || at == null) {
            return 0;
        }
        return Math.max(0, java.time.Duration.between(startedAt, at).getSeconds());
    }

    /** 만료 판정용 파싱 — 깨진 JSON이어도 경고 없이 빈 값(generate 쪽 readParam이 이미 경고한다) */
    private Map<String, Object> readParamQuietly(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> readParam(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("시나리오 파라미터 파싱 실패 — 변형 없이 진행: {}", LogSanitizer.clean(json));
            return Map.of();
        }
    }
}
