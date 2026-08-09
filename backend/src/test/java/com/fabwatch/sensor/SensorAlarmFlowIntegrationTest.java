package com.fabwatch.sensor;

import com.fabwatch.alarm.entity.Alarm;
import com.fabwatch.alarm.repository.AlarmRepository;
import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.entity.EquipmentStatus;
import com.fabwatch.equipment.repository.EquipmentRepository;
import com.fabwatch.sensor.entity.Sensor;
import com.fabwatch.sensor.repository.SensorData1mRepository;
import com.fabwatch.sensor.repository.SensorDataRepository;
import com.fabwatch.sensor.repository.SensorRepository;
import com.fabwatch.sensor.service.SensorAggregationService;
import com.fabwatch.sensor.service.SensorIngestionService;
import com.fabwatch.simulator.dto.ScenarioCreateRequest;
import com.fabwatch.simulator.dto.ScenarioResponse;
import com.fabwatch.simulator.entity.SimulationScenario;
import com.fabwatch.simulator.service.ScenarioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2주차 통합 테스트 — 시뮬레이터 → 수집 → 임계치 판정 → 알람 → 자동 DOWN 전 구간 + API 경계.
 *
 * 시간 기반 로직(2초 수집·1분 집계·새벽 배치)은 스케줄러를 끄고(application-test.yml)
 * 서비스 메서드를 고정 시각으로 직접 호출해 검증한다 — 실제 시간을 기다리는 테스트를 만들지 않는다.
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class SensorAlarmFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SensorIngestionService ingestionService;
    @Autowired
    private SensorAggregationService aggregationService;
    @Autowired
    private ScenarioService scenarioService;
    @Autowired
    private SensorRepository sensorRepository;
    @Autowired
    private SensorDataRepository sensorDataRepository;
    @Autowired
    private SensorData1mRepository sensorData1mRepository;
    @Autowired
    private AlarmRepository alarmRepository;
    @Autowired
    private EquipmentRepository equipmentRepository;

    // ---------------------------------------------------------------- 수집 파이프라인

    @Test
    @DisplayName("시뮬레이터 한 틱이 시드 센서 18개 전부에 대해 기준값 근처 값을 생성·저장한다")
    void 수집_한틱() {
        Instant at = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        int saved = ingestionService.ingest(at);

        assertThat(saved).isEqualTo(18); // docs/03 4.1 — 설비 5대 / 센서 18개
        Sensor lami01Temp = sensor("LAMI-01", Sensor.Type.TEMP);
        BigDecimal value = sensorDataRepository.findFirstBySensorIdOrderByMeasuredAtDesc(lami01Temp.getId())
                .orElseThrow().getValue();
        // 평균 45℃ / σ 0.8 → 노이즈만으로는 정상 범위(38~50)를 벗어나지 않는다
        assertThat(value.doubleValue()).isBetween(40.0, 50.0);
        assertThat(value.scale()).isEqualTo(2); // DECIMAL(10,2)
    }

    @Test
    @DisplayName("1분 집계는 UPSERT — 같은 버킷을 두 번 집계해도 행이 늘지 않고 표본 수만 갱신된다")
    void 집계_UPSERT() {
        Instant bucket = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        Long sensorId = sensor("SCRB-01", Sensor.Type.VIBRATION).getId();

        ingestionService.ingest(bucket.plusSeconds(2));
        ingestionService.ingest(bucket.plusSeconds(4));
        aggregationService.aggregateMinute(bucket);

        var first = sensorData1mRepository.findBySensorIdAndBucketAt(sensorId, bucket).orElseThrow();
        assertThat(first.getSampleCount()).isEqualTo(2);
        assertThat(first.getMinV()).isLessThanOrEqualTo(first.getMaxV());
        assertThat(first.getAvgV()).isBetween(first.getMinV(), first.getMaxV());

        // 같은 버킷에 한 건 더 쌓고 재집계 → 새 행이 생기지 않고 기존 행이 갱신된다
        ingestionService.ingest(bucket.plusSeconds(6));
        aggregationService.aggregateMinute(bucket);

        assertThat(sensorData1mRepository.findBySensorIdAndBucketAt(sensorId, bucket).orElseThrow().getSampleCount())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("원본 7일 보존 배치는 기준 시각 이전 데이터만 지운다")
    void 원본_보존_배치() {
        Instant old = Instant.now().minus(9, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
        Long sensorId = sensor("AOI-01", Sensor.Type.CURRENT).getId();

        ingestionService.ingest(old);
        ingestionService.ingest(recent);

        aggregationService.purgeRawBefore(Instant.now());

        assertThat(sensorDataRepository
                .findBySensorIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                        sensorId, old.minusSeconds(1), old.plusSeconds(1)))
                .isEmpty();
        assertThat(sensorDataRepository
                .findBySensorIdAndMeasuredAtGreaterThanEqualAndMeasuredAtLessThanOrderByMeasuredAtAsc(
                        sensorId, recent.minusSeconds(1), recent.plusSeconds(1)))
                .hasSize(1);
    }

    // ---------------------------------------------------------------- 시나리오 → 알람 → 자동 DOWN

    @Test
    @DisplayName("★ STEP 시나리오 주입 → CRITICAL 알람 1건 + 설비 자동 DOWN, 반복 수집해도 알람은 늘지 않는다(중복 억제)")
    void 시나리오_알람_자동DOWN() {
        Equipment oven = equipment("OVEN-01");
        assertThat(oven.getStatus()).isEqualTo(EquipmentStatus.RUN); // 시드 상태
        Sensor temp = sensor("OVEN-01", Sensor.Type.TEMP);          // base 120℃, crit_high 133

        ScenarioResponse scenario = scenarioService.inject(new ScenarioCreateRequest(
                temp.getId(), SimulationScenario.Type.STEP, Map.of("offset", 100)));
        try {
            ingestionService.ingest(Instant.now());
            ingestionService.ingest(Instant.now());
            ingestionService.ingest(Instant.now());

            List<Alarm> critical = alarmRepository.findAll().stream()
                    .filter(alarm -> alarm.getSensorId() != null && alarm.getSensorId().equals(temp.getId()))
                    .filter(alarm -> alarm.getSeverity() == Alarm.Severity.CRITICAL)
                    .toList();
            assertThat(critical).hasSize(1); // 3틱을 돌려도 중복 억제로 1건
            assertThat(critical.get(0).getStatus()).isEqualTo(Alarm.Status.OPEN);
            assertThat(critical.get(0).getAlarmType()).isEqualTo(Alarm.Type.SENSOR_THRESHOLD);
            assertThat(critical.get(0).getThresholdValue()).isEqualByComparingTo("133");
            assertThat(critical.get(0).getMessage()).contains("OVEN-01").contains("온도");

            // CRITICAL → 설비 자동 DOWN (DB status 필드 변경 + 상태 로그. 물리 제어 아님 — docs/11 §10)
            assertThat(equipment("OVEN-01").getStatus()).isEqualTo(EquipmentStatus.DOWN);
        } finally {
            scenarioService.release(scenario.id());
        }
    }

    // ---------------------------------------------------------------- 센서 조회 API

    @Test
    @DisplayName("GET /equipments/{id}/sensor-data/latest — 센서별 최신 1건 + 임계치 동봉, 페이지 래핑")
    void 최신값_조회() throws Exception {
        Equipment lami = equipment("LAMI-01");
        ingestionService.ingest(Instant.now());

        mockMvc.perform(get("/api/v1/equipments/{id}/sensor-data/latest", lami.getId())
                        .header("Authorization", "Bearer " + login("tech@fabwatch.dev")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].sensorId").exists())
                .andExpect(jsonPath("$.content[0].sensorType").exists())
                .andExpect(jsonPath("$.content[0].unit").exists())
                .andExpect(jsonPath("$.content[0].value").exists())
                .andExpect(jsonPath("$.content[0].measuredAt").exists())
                .andExpect(jsonPath("$.content[0].level").exists());
    }

    @Test
    @DisplayName("GET /equipments/{id}/sensor-data — 1시간 이내는 RAW, 초과하면 1M을 자동 선택하고 granularity로 알린다")
    void 기간_조회_granularity() throws Exception {
        Equipment lami = equipment("LAMI-01");
        String token = login("tech@fabwatch.dev");
        Instant now = Instant.now();

        mockMvc.perform(get("/api/v1/equipments/{id}/sensor-data", lami.getId())
                        .param("from", now.minus(30, ChronoUnit.MINUTES).toString())
                        .param("to", now.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("RAW"))
                .andExpect(jsonPath("$.series.length()").value(4))
                .andExpect(jsonPath("$.series[0].points").isArray());

        mockMvc.perform(get("/api/v1/equipments/{id}/sensor-data", lami.getId())
                        .param("sensorType", "TEMP")
                        .param("from", now.minus(24, ChronoUnit.HOURS).toString())
                        .param("to", now.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("1M"))
                .andExpect(jsonPath("$.series.length()").value(1))
                .andExpect(jsonPath("$.series[0].sensorType").value("TEMP"));
    }

    // ---------------------------------------------------------------- 임계치 API (FR-2.3)

    @Test
    @DisplayName("PUT thresholds — 순서 위반은 400 INVALID_THRESHOLD_RANGE, 정상 변경은 이력이 남는다")
    void 임계치_변경() throws Exception {
        Equipment scrb = equipment("SCRB-01");
        Sensor temp = sensor("SCRB-01", Sensor.Type.TEMP);
        String admin = login("admin@fabwatch.dev");

        // crit_low(31) > warn_low(30) → 위반
        mockMvc.perform(put("/api/v1/equipments/{eid}/sensors/{sid}/thresholds", scrb.getId(), temp.getId())
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warnLow":30,"warnHigh":44,"critLow":31,"critHigh":48,"reason":"잘못된 순서"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_THRESHOLD_RANGE"));

        // reason 누락 → 400 VALIDATION_ERROR
        mockMvc.perform(put("/api/v1/equipments/{eid}/sensors/{sid}/thresholds", scrb.getId(), temp.getId())
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warnLow":30,"warnHigh":44,"critLow":28,"critHigh":48}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 정상 변경
        mockMvc.perform(put("/api/v1/equipments/{eid}/sensors/{sid}/thresholds", scrb.getId(), temp.getId())
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warnLow":30,"warnHigh":44,"critLow":28,"critHigh":48,"reason":"여름철 실내온도 상승 반영"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorId").value(temp.getId()))
                .andExpect(jsonPath("$.warnHigh").value(44))
                .andExpect(jsonPath("$.critHigh").value(48));

        // 이력에 누가·언제·왜가 남는다
        mockMvc.perform(get("/api/v1/equipments/{eid}/sensors/{sid}/thresholds/logs", scrb.getId(), temp.getId())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reason").value("여름철 실내온도 상승 반영"))
                .andExpect(jsonPath("$.content[0].changedByName").value("김관리"))
                .andExpect(jsonPath("$.content[0].changedAt").exists())
                .andExpect(jsonPath("$.content[0].newCritHigh").value(48));
    }

    @Test
    @DisplayName("임계치 수정은 ADMIN 전용 — ENGINEER는 403")
    void 임계치_권한() throws Exception {
        Equipment scrb = equipment("SCRB-01");
        Sensor temp = sensor("SCRB-01", Sensor.Type.TEMP);

        mockMvc.perform(put("/api/v1/equipments/{eid}/sensors/{sid}/thresholds", scrb.getId(), temp.getId())
                        .header("Authorization", "Bearer " + login("engineer@fabwatch.dev"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warnLow":30,"warnHigh":44,"critLow":28,"critHigh":48,"reason":"권한 확인"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------------------------------------------------------------- 알람 API

    @Test
    @DisplayName("GET /alarms — OPEN 우선 정렬, 페이지 래핑, equipmentCode 포함")
    void 알람_목록() throws Exception {
        mockMvc.perform(get("/api/v1/alarms")
                        .header("Authorization", "Bearer " + login("tech@fabwatch.dev")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].equipmentCode").exists())
                .andExpect(jsonPath("$.content[0].severity").exists())
                .andExpect(jsonPath("$.content[0].occurredAt").exists())
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    @DisplayName("회귀: 담당자 없는(ack_by=null) OPEN 알람만 있는 페이지도 500이 나지 않는다")
    void 알람_목록_담당자없음() throws Exception {
        mockMvc.perform(get("/api/v1/alarms")
                        .param("status", "OPEN")
                        .header("Authorization", "Bearer " + login("tech@fabwatch.dev")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ackBy").doesNotExist())
                .andExpect(jsonPath("$.content[0].ackByName").doesNotExist());
    }

    @Test
    @DisplayName("회귀: 자동 DOWN 상태 로그(changed_by=null)가 섞여도 status-logs 조회가 500이 나지 않는다")
    void 상태로그_시스템전환() throws Exception {
        Equipment lami = equipment("LAMI-02");
        Sensor temp = sensor("LAMI-02", Sensor.Type.TEMP);
        var scenario = scenarioService.inject(new ScenarioCreateRequest(
                temp.getId(), SimulationScenario.Type.STEP, Map.of("offset", 100)));
        try {
            ingestionService.ingest(Instant.now());
            assertThat(equipment("LAMI-02").getStatus()).isEqualTo(EquipmentStatus.DOWN);

            mockMvc.perform(get("/api/v1/equipments/{id}/status-logs", lami.getId())
                            .header("Authorization", "Bearer " + login("tech@fabwatch.dev")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].toStatus").value("DOWN"))
                    .andExpect(jsonPath("$.content[0].changedByName").doesNotExist());
        } finally {
            scenarioService.release(scenario.id());
        }
    }

    @Test
    @DisplayName("수동 보고 → ACK 없이 해제 시도 400 ACK_REQUIRED_FIRST → ACK 후 해제 성공")
    void 알람_ACK_해제_흐름() throws Exception {
        Equipment aoi = equipment("AOI-01");
        String token = login("tech@fabwatch.dev");

        String created = mockMvc.perform(post("/api/v1/alarms/manual")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"equipmentId":%d,"severity":"WARNING","message":"이송부 이음 — 수동 보고"}"""
                                .formatted(aoi.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alarmType").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.sensorId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long alarmId = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(patch("/api/v1/alarms/{id}/resolve", alarmId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resolveNote":"ACK 없이 해제 시도"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACK_REQUIRED_FIRST"));

        mockMvc.perform(patch("/api/v1/alarms/{id}/ack", alarmId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACK"))
                .andExpect(jsonPath("$.ackByName").value("이테크니션"));

        // 해제 사유 누락 → 400 VALIDATION_ERROR
        mockMvc.perform(patch("/api/v1/alarms/{id}/resolve", alarmId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolveNote\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/v1/alarms/{id}/resolve", alarmId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resolveNote":"이송 벨트 장력 재조정 완료"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolveNote").value("이송 벨트 장력 재조정 완료"))
                .andExpect(jsonPath("$.resolvedByName").value("이테크니션"));
    }

    // ---------------------------------------------------------------- 시뮬레이터 API

    @Test
    @DisplayName("시나리오 주입/조회/해제 + 동일 유형 중복 주입은 409")
    void 시나리오_CRUD() throws Exception {
        Sensor sensor = sensor("LAMI-02", Sensor.Type.CURRENT);
        String engineer = login("engineer@fabwatch.dev");

        String body = """
                {"sensorId":%d,"type":"SPIKE","param":{"probability":0,"multiplier":1.8}}""".formatted(sensor.getId());

        String created = mockMvc.perform(post("/api/v1/simulator/scenarios")
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SPIKE"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.equipmentCode").value("LAMI-02"))
                .andExpect(jsonPath("$.sensorType").value("CURRENT"))
                .andExpect(jsonPath("$.param.probability").value(0.0))
                .andExpect(jsonPath("$.param.multiplier").value(1.8))
                .andReturn().getResponse().getContentAsString();
        long scenarioId = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(post("/api/v1/simulator/scenarios")
                        .header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCENARIO_ALREADY_ACTIVE"));

        JsonNode list = objectMapper.readTree(mockMvc.perform(get("/api/v1/simulator/scenarios")
                        .header("Authorization", "Bearer " + engineer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(list.get("content").findValuesAsText("type")).contains("SPIKE");

        mockMvc.perform(delete("/api/v1/simulator/scenarios/{id}", scenarioId)
                        .header("Authorization", "Bearer " + engineer))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/simulator/scenarios")
                        .header("Authorization", "Bearer " + engineer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(scenarioId)).isEmpty());
    }

    @Test
    @DisplayName("시뮬레이터 제어는 ENGINEER+ 전용 — TECHNICIAN은 403")
    void 시뮬레이터_권한() throws Exception {
        mockMvc.perform(get("/api/v1/simulator/scenarios")
                        .header("Authorization", "Bearer " + login("tech@fabwatch.dev")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------------------------------------------------------------- SSE 인증

    @Test
    @DisplayName("SSE는 쿼리 파라미터 토큰으로만 인증한다 (docs/11 §4) — 토큰 없으면 401")
    void SSE_토큰_인증() throws Exception {
        mockMvc.perform(get("/api/v1/stream/sensors"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/stream/sensors").param("token", "invalid.token.value"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/stream/sensors")
                        .param("token", login("tech@fabwatch.dev"))
                        .param("equipmentId", String.valueOf(equipment("LAMI-01").getId())))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("SSE payload shape — equipmentId를 지정하면 해당 설비 센서 이벤트만 event:sensor로 흐른다")
    void SSE_payload_shape() throws Exception {
        Equipment lami = equipment("LAMI-01");

        var result = mockMvc.perform(get("/api/v1/stream/sensors")
                        .param("token", login("engineer@fabwatch.dev"))
                        .param("equipmentId", String.valueOf(lami.getId())))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        ingestionService.ingest(Instant.now());

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:sensor");
        assertThat(body.split("event:sensor", -1).length - 1).isEqualTo(4); // LAMI-01 센서 4개만
        assertThat(body).contains("\"equipmentId\":" + lami.getId())
                .contains("\"sensorId\":")
                .contains("\"type\":")
                .contains("\"unit\":")
                .contains("\"value\":")
                .contains("\"measuredAt\":")
                .contains("\"level\":");
    }

    @Test
    @DisplayName("쿼리 파라미터 토큰은 SSE 경로 밖에서는 통하지 않는다")
    void 쿼리토큰은_SSE_전용() throws Exception {
        mockMvc.perform(get("/api/v1/alarms").param("token", login("tech@fabwatch.dev")))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helper

    private Equipment equipment(String code) {
        return equipmentRepository.findByCode(code).orElseThrow();
    }

    private Sensor sensor(String equipmentCode, Sensor.Type type) {
        return sensorRepository.findByEquipmentIdAndType(equipment(equipmentCode).getId(), type).orElseThrow();
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"fabwatch123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
