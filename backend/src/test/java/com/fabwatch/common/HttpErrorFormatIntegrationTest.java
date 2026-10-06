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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프레임워크 레벨 HTTP 오류(405/415/406/파라미터 누락)도 500이 아니라 공통 포맷({code,message,timestamp})으로 내려가는지 검증한다.
 * 클라이언트 실수가 서버 오류(500)로 보이면 모니터링이 오염되고 API 사용자가 원인을 알 수 없다.
 */
@SpringBootTest(properties = "fabwatch.seed.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class HttpErrorFormatIntegrationTest {

    private static final String PASSWORD = "fabwatch123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("허용되지 않는 메서드(DELETE /equipments/{id}) → 405 METHOD_NOT_ALLOWED, 공통 포맷")
    void methodNotAllowed() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v1/equipments/1").header("Authorization", bearer(login("tech@fabwatch.dev"))))
                .andExpect(status().isMethodNotAllowed()).andReturn();
        assertErrorBody(result, "METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type(text/plain) → 415 UNSUPPORTED_MEDIA_TYPE")
    void unsupportedMediaType() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andReturn();
        assertErrorBody(result, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("지원하지 않는 Accept(application/xml) → 406 NOT_ACCEPTABLE")
    void notAcceptable() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/equipments/1")
                        .header("Authorization", bearer(login("tech@fabwatch.dev")))
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable()).andReturn();
        assertErrorBody(result, "NOT_ACCEPTABLE");
    }

    @Test
    @DisplayName("응답 본문에 스택트레이스·예외 클래스명이 없다")
    void noInternalDetailsLeaked() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v1/equipments/1").header("Authorization", bearer(login("tech@fabwatch.dev"))))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("org.springframework");
    }

    private void assertErrorBody(MvcResult result, String expectedCode) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("code", "message", "timestamp");
        assertThat(body.get("code").asText()).isEqualTo(expectedCode);
        assertThat(body.get("message").asText()).isNotBlank();
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
