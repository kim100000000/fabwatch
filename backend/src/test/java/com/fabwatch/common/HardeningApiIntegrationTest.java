package com.fabwatch.common;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배포 전 견고성 수정(보안 감사/리뷰)의 API 수준 검증:
 * 정렬 파라미터 400/무시, 시뮬레이터 파라미터 400, 자동 DOWN 사유 200자 절단(500 방지), 한글 장문 점검 저장.
 * 클래스 @Transactional — 쓰기는 테스트 끝에 롤백된다.
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class HardeningApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------ 정렬 파라미터

    @Test
    @DisplayName("GET /equipments?sort=foo / password,desc → 500이 아니라 400 VALIDATION_ERROR")
    void 설비_목록_잘못된_정렬은_400() throws Exception {
        String token = login("tech@fabwatch.dev");
        for (String sort : new String[]{"foo", "password,desc", "code;drop table x"}) {
            mockMvc.perform(get("/api/v1/equipments").param("sort", sort).header("Authorization", bearer(token)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    @Test
    @DisplayName("GET /equipments — 허용 정렬(code,desc / name)과 기본 정렬은 정상")
    void 설비_목록_허용_정렬() throws Exception {
        String token = login("tech@fabwatch.dev");
        JsonNode desc = json(mockMvc.perform(get("/api/v1/equipments").param("sort", "code,desc")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()));
        assertThat(desc.get("content").get(0).get("code").asText())
                .isGreaterThan(desc.get("content").get(desc.get("content").size() - 1).get("code").asText());
        mockMvc.perform(get("/api/v1/equipments").param("sort", "name").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/equipments").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("정렬이 서버 고정인 목록(알람·상태 이력·임계치 이력·점검·AI 리포트)은 ?sort=foo를 무시하고 200")
    void 고정_정렬_목록은_sort를_무시() throws Exception {
        String token = login("tech@fabwatch.dev");
        long equipmentId = firstEquipment(token).get("id").asLong();
        long sensorId = firstSensorId(token, equipmentId);

        for (String url : new String[]{
                "/api/v1/alarms",
                "/api/v1/equipments/" + equipmentId + "/status-logs",
                "/api/v1/equipments/" + equipmentId + "/sensors/" + sensorId + "/thresholds/logs",
                "/api/v1/inspections",
                "/api/v1/ai-reports"}) {
            mockMvc.perform(get(url).param("sort", "foo,desc").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());
        }
    }

    // ------------------------------------------------------------ 시뮬레이터 파라미터

    @Test
    @DisplayName("POST /simulator/scenarios — NaN 문자열·범위 밖·문자열 숫자는 400 VALIDATION_ERROR, 정상 값은 201")
    void 시나리오_주입_검증() throws Exception {
        String token = login("engineer@fabwatch.dev");
        long sensorId = firstSensorId(token, firstEquipment(token).get("id").asLong());

        for (String param : new String[]{
                "{\"slopePerSec\":\"NaN\"}",
                "{\"slopePerSec\":1e12}",
                "{\"durationMin\":241}",
                "{\"durationMin\":0}"}) {
            postScenario(token, sensorId, "DRIFT", param)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        postScenario(token, sensorId, "SPIKE", "{\"multiplier\":9}").andExpect(status().isBadRequest());
        postScenario(token, sensorId, "SPIKE", "{\"probability\":1.5}").andExpect(status().isBadRequest());
        postScenario(token, sensorId, "STEP", "{\"offset\":\"abc\"}").andExpect(status().isBadRequest());
        postScenario(token, sensorId, "STEP", "{\"offset\":1e9}").andExpect(status().isBadRequest());
        // Jackson이 거부하는 NaN/Infinity 리터럴(비표준 JSON)도 500이 아니라 400
        postScenario(token, sensorId, "STEP", "{\"offset\":NaN}").andExpect(status().isBadRequest());

        postScenario(token, sensorId, "DRIFT", "{\"durationMin\":5}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.param.durationMin").value(5));
    }

    // ------------------------------------------------------------ 자동 DOWN 사유 길이 (500 방지)

    @Test
    @DisplayName("300자 수동 CRITICAL 보고 → 201, 자동 DOWN 사유는 200자(말줄임)로 잘려 상태 로그에 저장 (알람 생성이 500 롤백되지 않는다)")
    void 긴_CRITICAL_메시지는_사유를_절단해_저장() throws Exception {
        String token = login("engineer@fabwatch.dev");
        JsonNode equipment = firstEquipment(token);
        long equipmentId = equipment.get("id").asLong();
        assertThat(equipment.get("status").asText()).isIn("RUN", "IDLE");
        String message = "고".repeat(300);

        mockMvc.perform(post("/api/v1/alarms/manual").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equipmentId\":" + equipmentId + ",\"severity\":\"CRITICAL\",\"message\":\"" + message + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(message)); // 알람 본문은 원문 그대로

        JsonNode logs = json(mockMvc.perform(get("/api/v1/equipments/{id}/status-logs", equipmentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()));
        JsonNode latest = logs.get("content").get(0);
        assertThat(latest.get("toStatus").asText()).isEqualTo("DOWN");
        String reason = latest.get("reason").asText();
        // 본문은 말줄임으로 잘리고, 알람 발생 시각이 사유 끝에 붙는다 — 전체는 varchar(200) 이내
        assertThat(reason).hasSizeLessThanOrEqualTo(200).contains("…").endsWith("]")
                .startsWith("CRITICAL 알람 자동 DOWN — 고").contains("[알람 발생 ");
    }

    // ------------------------------------------------------------ 한글 장문 점검 이력

    @Test
    @DisplayName("점검 content/actionTaken에 한글 20,000자 저장 가능, 20,001자는 400, 체크 결과 201개는 400")
    void 한글_장문_점검_이력() throws Exception {
        String token = login("tech@fabwatch.dev");
        long equipmentId = firstEquipment(token).get("id").asLong();
        Instant ended = Instant.now().minus(30, ChronoUnit.MINUTES);
        Instant started = ended.minus(1, ChronoUnit.HOURS);
        String longKorean = "점".repeat(20_000);

        JsonNode created = json(postInspection(token, body(equipmentId, started, ended, longKorean, longKorean, ""))
                .andExpect(status().isCreated()));
        long id = created.get("id").asLong();

        JsonNode detail = json(mockMvc.perform(get("/api/v1/inspections/{id}", id).header("Authorization", bearer(token)))
                .andExpect(status().isOk()));
        assertThat(detail.get("content").asText()).hasSize(20_000);
        assertThat(detail.get("actionTaken").asText()).hasSize(20_000);

        String tooLong = "점".repeat(20_001);
        postInspection(token, body(equipmentId, started, ended, tooLong, null, ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postInspection(token, body(equipmentId, started, ended, "정상", tooLong, ""))
                .andExpect(status().isBadRequest());

        StringBuilder results = new StringBuilder(",\"checkResults\":[");
        for (int i = 0; i < 201; i++) {
            results.append(i == 0 ? "" : ",").append("{\"checklistItemId\":1,\"result\":\"OK\"}");
        }
        results.append("]");
        postInspection(token, body(equipmentId, started, ended, "정상", null, results.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------ 헬퍼

    private ResultActions postScenario(String token, long sensorId, String type, String paramJson) throws Exception {
        return mockMvc.perform(post("/api/v1/simulator/scenarios").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sensorId\":" + sensorId + ",\"type\":\"" + type + "\",\"param\":" + paramJson + "}"));
    }

    private ResultActions postInspection(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/inspections").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(long equipmentId, Instant started, Instant ended, String content,
                               String actionTaken, String extra) {
        return "{\"equipmentId\":" + equipmentId + ",\"type\":\"PM\",\"startedAt\":\"" + started
                + "\",\"endedAt\":\"" + ended + "\",\"content\":\"" + content + "\""
                + (actionTaken == null ? "" : ",\"actionTaken\":\"" + actionTaken + "\"") + extra + "}";
    }

    /** 코드순 첫 설비(시드: RUN 상태) */
    private JsonNode firstEquipment(String token) throws Exception {
        return json(mockMvc.perform(get("/api/v1/equipments").header("Authorization", bearer(token)))
                .andExpect(status().isOk())).get("content").get(0);
    }

    private long firstSensorId(String token, long equipmentId) throws Exception {
        JsonNode sensors = json(mockMvc.perform(get("/api/v1/equipments/{id}/sensors", equipmentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()));
        JsonNode list = sensors.isArray() ? sensors : sensors.get("content");
        JsonNode first = list.get(0);
        return (first.has("sensorId") ? first.get("sensorId") : first.get("id")).asLong();
    }

    private String login(String email) throws Exception {
        return json(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"fabwatch123\"}"))
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
