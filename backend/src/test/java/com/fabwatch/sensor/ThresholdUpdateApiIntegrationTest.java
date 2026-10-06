package com.fabwatch.sensor;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 임계치 수정 API 통합 테스트 (QA f2f3: 자릿수 검증·전부 비우기 차단·사유 trim·권한).
 * 클래스 단위 @Transactional — 쓰기는 테스트가 끝나면 롤백되어 시드 임계치를 오염시키지 않는다.
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class ThresholdUpdateApiIntegrationTest {

    private static final String URL = "/api/v1/equipments/1/sensors/1/thresholds";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("ADMIN: 정상 변경 → 200, 응답 값이 저장 값과 같고 사유는 trim되어 이력에 남는다")
    void update_ok_andReasonTrimmed() throws Exception {
        String admin = login("admin@fabwatch.dev");
        mockMvc.perform(put(URL).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warnLow\":38,\"warnHigh\":52.5,\"critLow\":35,\"critHigh\":57,\"reason\":\"  현장 기준 조정  \"}"))
                .andExpect(status().isOk());

        JsonNode logs = json(mockMvc.perform(get(URL + "/logs").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(logs.get("content").get(0).get("reason").asText()).isEqualTo("현장 기준 조정");
        assertThat(logs.get("content").get(0).get("newWarnHigh").decimalValue()).isEqualByComparingTo("52.5");
    }

    @Test
    @DisplayName("소수 3자리 이상은 400 VALIDATION_ERROR — DB가 조용히 반올림해 응답과 저장값이 달라지는 것을 막는다")
    void update_tooManyDecimals() throws Exception {
        String admin = login("admin@fabwatch.dev");
        MvcResult result = mockMvc.perform(put(URL).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warnLow\":38,\"warnHigh\":50.123,\"critLow\":35,\"critHigh\":55,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(json(result).get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("정수 8자리 초과(decimal(10,2) 범위 밖)는 400 — 500으로 새지 않는다")
    void update_tooManyIntegerDigits() throws Exception {
        String admin = login("admin@fabwatch.dev");
        mockMvc.perform(put(URL).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warnLow\":38,\"warnHigh\":50,\"critLow\":35,\"critHigh\":123456789,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("4값을 전부 비우거나 키를 빠뜨리면 400 INVALID_THRESHOLD_RANGE — 센서 감시가 꺼지지 않는다")
    void update_allNull_rejected() throws Exception {
        String admin = login("admin@fabwatch.dev");
        for (String body : new String[]{
                "{\"warnLow\":null,\"warnHigh\":null,\"critLow\":null,\"critHigh\":null,\"reason\":\"x\"}",
                "{\"reason\":\"x\"}"}) {
            MvcResult result = mockMvc.perform(put(URL).header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(json(result).get("code").asText()).isEqualTo("INVALID_THRESHOLD_RANGE");
        }
    }

    @Test
    @DisplayName("TECHNICIAN/ENGINEER는 임계치를 수정할 수 없다(403), 이력 조회는 가능(200)")
    void update_forbiddenForNonAdmin() throws Exception {
        String valid = "{\"warnLow\":38,\"warnHigh\":50,\"critLow\":35,\"critHigh\":55,\"reason\":\"x\"}";
        for (String email : new String[]{"tech@fabwatch.dev", "engineer@fabwatch.dev"}) {
            String token = login(email);
            mockMvc.perform(put(URL).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(valid))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(URL + "/logs").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"fabwatch123\"}"))
                .andExpect(status().isOk()).andReturn();
        return json(result).get("accessToken").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
