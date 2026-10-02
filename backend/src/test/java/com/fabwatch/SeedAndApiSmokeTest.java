package com.fabwatch;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시드 + 인증/설비 API 통합 스모크 테스트.
 * 프로파일 순서 local,test — 시더(@Profile("local"))는 켜고 DataSource는 test(H2)가 덮어쓴다.
 * 로컬 MySQL 없이도 시드 로직과 API 경계(권한·에러 포맷·상태 머신)를 검증한다.
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class SeedAndApiSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("시드 데이터가 라인 1개 / 공정 3개 / 설비 5대로 생성되고 트리 API로 조회된다")
    void 시드_트리_조회() throws Exception {
        String token = login("admin@fabwatch.dev");

        mockMvc.perform(get("/api/v1/lines").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("CELL-1라인"))
                .andExpect(jsonPath("$.content[0].processes.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/equipments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[0].code").value("AOI-01"))
                .andExpect(jsonPath("$.number").value(0))
                // 프론트 설비 목록 테이블이 모델/제조사 컬럼을 그린다 — 목록 응답에 반드시 포함되어야 한다
                .andExpect(jsonPath("$.content[0].modelName").exists())
                .andExpect(jsonPath("$.content[0].maker").exists());
    }

    @Test
    @DisplayName("리프레시 응답은 로그인과 동일 shape — user{id,name,role} 포함 (프론트 세션 복구가 이 필드에 의존)")
    void 리프레시_응답에_user_포함() throws Exception {
        JsonNode loginBody = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"engineer@fabwatch.dev","password":"fabwatch123"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + loginBody.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").exists())
                .andExpect(jsonPath("$.user.name").value("박엔지니어"))
                .andExpect(jsonPath("$.user.role").value("ENGINEER"));
    }

    @Test
    @DisplayName("로그아웃은 요청 바디 없이 Authorization 헤더만으로 동작하고 Refresh가 무효화된다")
    void 로그아웃_바디_없음() throws Exception {
        JsonNode loginBody = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@fabwatch.dev","password":"fabwatch123"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String accessToken = loginBody.get("accessToken").asText();
        String refreshToken = loginBody.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("인증 없는 요청은 401, 공통 에러 포맷 { code, message, timestamp }")
    void 인증_없으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/equipments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("잘못된 비밀번호는 401 LOGIN_FAILED")
    void 로그인_실패() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@fabwatch.dev","password":"wrong-password"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    @DisplayName("TECHNICIAN은 설비 등록 불가 — 403 FORBIDDEN (docs/11 §3 권한 매트릭스)")
    void 권한_부족시_403() throws Exception {
        String token = login("tech@fabwatch.dev");

        mockMvc.perform(post("/api/v1/equipments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"processId":1,"code":"TEST-01","name":"테스트 설비"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("상태 전환 — 허용 전이는 200 + 로그 기록, 비허용 전이는 400 INVALID_STATUS_TRANSITION")
    void 상태전환_경계() throws Exception {
        String token = login("engineer@fabwatch.dev");
        Long equipmentId = findEquipmentId(token, "SCRB-01"); // 시드 상태 IDLE

        mockMvc.perform(patch("/api/v1/equipments/{id}/status", equipmentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toStatus":"DOWN","reason":"수동 고장 보고"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"));

        // PM → RUN 은 허용되지 않는다 (시운전 경유 원칙). DOWN → PM → RUN 시도
        patchStatus(token, equipmentId, "PM", "BM 병행 정비").andExpect(status().isOk());
        patchStatus(token, equipmentId, "RUN", "임의 복구")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        mockMvc.perform(get("/api/v1/equipments/{id}/status-logs", equipmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].toStatus").value("PM"))
                .andExpect(jsonPath("$.content[1].toStatus").value("DOWN"))
                .andExpect(jsonPath("$.content[0].changedByName").value("박엔지니어"));
    }

    @Test
    @DisplayName("SM-3a/3b: DOWN→IDLE은 TECHNICIAN도 가능, reason 공백이면 400 VALIDATION_ERROR")
    void DOWN_IDLE_전_역할_사유필수() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        String tech = login("tech@fabwatch.dev");
        Long equipmentId = findEquipmentId(engineer, "AOI-01");

        // 상태가 무엇이든 DOWN으로 만든다 (RUN/IDLE → DOWN 모두 허용, 이미 DOWN이면 건너뜀)
        if (!"DOWN".equals(currentStatus(engineer, equipmentId))) {
            patchStatus(engineer, equipmentId, "DOWN", "수동 고장 보고").andExpect(status().isOk());
        }

        // TECHNICIAN 이라도 reason 공백이면 400 VALIDATION_ERROR (권한은 통과)
        patchStatus(tech, equipmentId, "IDLE", "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(patch("/api/v1/equipments/{id}/status", equipmentId)
                        .header("Authorization", "Bearer " + tech)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toStatus\":\"IDLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // TECHNICIAN DOWN→IDLE 성공
        patchStatus(tech, equipmentId, "IDLE", "센서 교체 완료, 시운전 대기")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"));
        mockMvc.perform(get("/api/v1/equipments/{id}/status-logs", equipmentId)
                        .header("Authorization", "Bearer " + tech))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fromStatus").value("DOWN"))
                .andExpect(jsonPath("$.content[0].toStatus").value("IDLE"))
                .andExpect(jsonPath("$.content[0].changedByName").value("이테크니션"));
    }

    @Test
    @DisplayName("SM-6: DOWN→RUN은 ENGINEER 허용(사유 필수), TECHNICIAN은 403 FORBIDDEN")
    void DOWN_RUN_권한과_사유() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        String tech = login("tech@fabwatch.dev");
        Long equipmentId = findEquipmentId(engineer, "LAMI-01");

        if (!"DOWN".equals(currentStatus(engineer, equipmentId))) {
            patchStatus(engineer, equipmentId, "DOWN", "수동 고장 보고").andExpect(status().isOk());
        }

        patchStatus(tech, equipmentId, "RUN", "시운전 생략")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.timestamp").exists());
        patchStatus(engineer, equipmentId, "RUN", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        patchStatus(engineer, equipmentId, "RUN", "긴급 생산 재개, 시운전 생략")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUN"));
    }

    @Test
    @DisplayName("TECHNICIAN은 DOWN→IDLE 외 상태 전환 불가 — RUN→DOWN은 403 FORBIDDEN")
    void 테크니션_일반_전환_거부() throws Exception {
        String engineer = login("engineer@fabwatch.dev");
        String tech = login("tech@fabwatch.dev");
        Long equipmentId = findEquipmentId(engineer, "OVEN-01");

        patchStatus(tech, equipmentId, "DOWN", "수동 고장 보고")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("리프레시 토큰 회전 — 새 토큰 발급 후 이전 토큰은 401")
    void 리프레시_회전() throws Exception {
        JsonNode loginBody = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"tech@fabwatch.dev","password":"fabwatch123"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String oldRefresh = loginBody.get("refreshToken").asText();

        String rotated = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(rotated).isNotEqualTo(oldRefresh);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(
            String token, Long equipmentId, String toStatus, String reason) throws Exception {
        return mockMvc.perform(patch("/api/v1/equipments/{id}/status", equipmentId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("toStatus", toStatus, "reason", reason))));
    }

    private String currentStatus(String token, Long equipmentId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/equipments/{id}", equipmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("status").asText();
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"fabwatch123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").exists())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private Long findEquipmentId(String token, String code) throws Exception {
        String body = mockMvc.perform(get("/api/v1/equipments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode node : objectMapper.readTree(body).get("content")) {
            if (code.equals(node.get("code").asText())) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException("시드에 " + code + " 설비가 없습니다.");
    }
}
