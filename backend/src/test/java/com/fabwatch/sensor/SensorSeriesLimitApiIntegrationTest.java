package com.fabwatch.sensor;

import com.fabwatch.equipment.repository.EquipmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /equipments/{id}/sensor-data 기간·미래·역전 검증이 HTTP 400 VALIDATION_ERROR 포맷으로 나오는지 (안정성 감사 M-7) */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class SensorSeriesLimitApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EquipmentRepository equipmentRepository;

    private String token() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"tech@fabwatch.dev\",\"password\":\"fabwatch123\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private long equipmentId() {
        return equipmentRepository.findByCode("LAMI-01").orElseThrow().getId();
    }

    @Test
    @DisplayName("from=2020-01-01 같은 넓은 기간은 400 VALIDATION_ERROR + 최대 기간 안내")
    void 넓은_기간_400() throws Exception {
        mockMvc.perform(get("/api/v1/equipments/{id}/sensor-data", equipmentId())
                        .param("from", "2020-01-01T00:00:00Z")
                        .param("to", Instant.now().toString())
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("최대 31일")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("미래 to·from>=to 는 400, 31일 이내 장기 조회는 200 (granularity 5M)")
    void 미래_역전_400_장기_200() throws Exception {
        String token = token();
        Instant now = Instant.now();

        mockMvc.perform(get("/api/v1/equipments/{id}/sensor-data", equipmentId())
                        .param("from", now.minus(Duration.ofHours(1)).toString())
                        .param("to", now.plus(Duration.ofDays(1)).toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/equipments/{id}/sensor-data", equipmentId())
                        .param("from", now.toString())
                        .param("to", now.minus(Duration.ofHours(1)).toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/equipments/{id}/sensor-data", equipmentId())
                        .param("from", now.minus(Duration.ofDays(30)).toString())
                        .param("to", now.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("5M"))
                .andExpect(jsonPath("$.series").isArray());
    }
}
