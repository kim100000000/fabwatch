package com.fabwatch.simulator.service;

import com.fabwatch.common.dto.PageResponse;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.util.LogSanitizer;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.sensor.service.SensorQueryService;
import com.fabwatch.sensor.service.SensorSpec;
import com.fabwatch.simulator.config.SimulatorProperties;
import com.fabwatch.simulator.dto.ScenarioCreateRequest;
import com.fabwatch.simulator.dto.ScenarioResponse;
import com.fabwatch.simulator.entity.SimulationScenario;
import com.fabwatch.simulator.repository.SimulationScenarioRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 시뮬레이터 시나리오 주입/해제 (docs/03 F-4.2, docs/06 §8).
 *
 * 센서 정의는 sensor 도메인의 SensorQueryService, 설비 코드는 equipment 도메인의 EquipmentQueryService로만
 * 조회한다 — 두 도메인의 엔티티/레포지토리를 직접 참조하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioService {

    private final SimulationScenarioRepository scenarioRepository;
    private final SensorQueryService sensorQueryService;
    private final EquipmentQueryService equipmentQueryService;
    private final ObjectMapper objectMapper;
    private final SimulatorProperties properties;

    /** GET /simulator/scenarios — 활성 시나리오 목록 */
    @Transactional(readOnly = true)
    public PageResponse<ScenarioResponse> getActiveScenarios() {
        List<SimulationScenario> scenarios = scenarioRepository.findByActiveTrueOrderByIdAsc();
        return PageResponse.ofAll(scenarios.stream().map(this::toResponse).toList());
    }

    /** POST /simulator/scenarios — 주입. 동일 센서에 같은 유형이 이미 활성이면 409. */
    @Transactional
    public ScenarioResponse inject(ScenarioCreateRequest request) {
        SensorSpec spec = sensorQueryService.findSpec(request.sensorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "센서를 찾을 수 없습니다: id=" + request.sensorId()));

        if (scenarioRepository.existsBySensorIdAndTypeAndActiveTrue(request.sensorId(), request.type())) {
            throw new BusinessException(ErrorCode.SCENARIO_ALREADY_ACTIVE,
                    "이미 활성인 시나리오가 있습니다: sensorId=%d, type=%s".formatted(request.sensorId(), request.type()));
        }

        Map<String, Object> param = normalize(spec, request.type(), request.param());
        SimulationScenario scenario = scenarioRepository.save(SimulationScenario.builder()
                .sensorId(request.sensorId())
                .type(request.type())
                .param(writeParam(param))
                .active(true)
                .startedAt(Instant.now())
                .build());

        // param은 검증된 숫자만 담긴 정규화 결과지만, 로그 인젝션 방지를 위해 한 번 더 정리한다
        log.info("시나리오 주입: id={}, sensorId={}, type={}, param={}",
                scenario.getId(), scenario.getSensorId(), scenario.getType(), LogSanitizer.clean(String.valueOf(param)));
        return toResponse(scenario);
    }

    /** DELETE /simulator/scenarios/{id} — 해제(정상 복귀). 행은 이력으로 남기고 active만 내린다. */
    @Transactional
    public void release(Long scenarioId) {
        SimulationScenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "시나리오를 찾을 수 없습니다: id=" + scenarioId));
        scenario.deactivate(Instant.now());
        log.info("시나리오 해제: id={}, sensorId={}, type={}", scenarioId, scenario.getSensorId(), scenario.getType());
    }

    /**
     * POST /simulator/demo — 데모 자동 시나리오 시작 (FR-4.5, Should).
     * 지정 설비(생략 시 첫 번째 설비)의 진동 센서에 DRIFT를 주입한다 — 데모용 기본 2분
     * (fabwatch.simulator.demo-duration-min)에 걸쳐 crit에 도달하며 3분 시연 안에
     * 정상 → 드리프트 → WARNING → CRITICAL → 자동 DOWN → 알람 흐름이 순서대로 일어난다.
     * 도달 후에는 목표값에서 고정되고(DRIFT plateau), plateau-hold-minutes(기본 5분)가 지나면 시나리오가 자동 만료된다.
     * 이미 주입돼 있으면 그대로 두고 현재 시나리오를 반환한다(데모 중 중복 클릭 대비).
     */
    @Transactional
    public PageResponse<ScenarioResponse> startDemo(Long equipmentId) {
        List<SensorSpec> specs = equipmentId == null
                ? sensorQueryService.findAllSpecs()
                : sensorQueryService.findSpecsByEquipmentId(equipmentId);
        if (specs.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "시연할 센서가 없습니다.");
        }
        SensorSpec target = specs.stream()
                .filter(spec -> "VIBRATION".equals(spec.type()))
                .findFirst()
                .orElse(specs.get(0));

        if (!scenarioRepository.existsBySensorIdAndTypeAndActiveTrue(target.sensorId(), SimulationScenario.Type.DRIFT)) {
            inject(new ScenarioCreateRequest(target.sensorId(), SimulationScenario.Type.DRIFT,
                    Map.of("durationMin", Math.min(properties.demoDurationMin(), ScenarioParams.MAX_DURATION_MIN))));
        }
        return getActiveScenarios();
    }

    private Map<String, Object> normalize(SensorSpec spec, SimulationScenario.Type type, Map<String, Object> raw) {
        return switch (type) {
            case DRIFT -> ScenarioParams.normalizeDrift(spec, raw);
            case SPIKE -> ScenarioParams.normalizeSpike(raw);
            case STEP -> ScenarioParams.normalizeStep(spec, raw);
        };
    }

    private ScenarioResponse toResponse(SimulationScenario scenario) {
        SensorSpec spec = sensorQueryService.findSpec(scenario.getSensorId()).orElse(null);
        Long equipmentId = spec == null ? null : spec.equipmentId();
        return new ScenarioResponse(
                scenario.getId(),
                scenario.getSensorId(),
                equipmentId,
                equipmentId == null ? null : equipmentQueryService.findCodeById(equipmentId).orElse(null),
                spec == null ? null : spec.type(),
                scenario.getType().name(),
                readParam(scenario.getParam()),
                scenario.isActive(),
                scenario.getStartedAt(),
                scenario.getEndedAt());
    }

    private String writeParam(Map<String, Object> param) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNullElse(param, Map.of()));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "시나리오 파라미터를 직렬화할 수 없습니다.");
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
            log.warn("시나리오 파라미터 파싱 실패 — 빈 값으로 대체: {}", LogSanitizer.clean(json));
            return Map.of();
        }
    }
}
