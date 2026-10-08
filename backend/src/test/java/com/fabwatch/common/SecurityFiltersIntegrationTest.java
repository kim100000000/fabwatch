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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보안 필터 체인 통합 — 실제 Spring Security 체인 안에서 레이트 리밋(429)·본문 상한(413)·SSE 연결 상한이 동작하는지.
 * 한도를 낮춘 전용 컨텍스트를 쓴다 (기본 테스트 프로파일은 레이트 리밋을 사실상 끈다).
 * 레이트 리밋은 같은 클라이언트 주소의 버킷을 공유하므로 로그인/리프레시를 호출하는 테스트 메서드는 하나로 모은다.
 */
@SpringBootTest(properties = {
        "fabwatch.seed.enabled=true",
        "fabwatch.security.rate-limit.auth-per-minute=3",
        "fabwatch.security.max-request-bytes=2048",
        "fabwatch.sse.max-connections=1",
        "fabwatch.sse.max-per-user=5",
        "fabwatch.test.context-isolation=security-filters"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class SecurityFiltersIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("로그인 3회까지 처리(401), 4번째는 429 RATE_LIMITED + Retry-After + 공통 포맷. 리프레시는 별도 버킷")
    void 로그인_레이트_리밋() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(login("nobody@fabwatch.dev"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        }
        MvcResult denied = mockMvc.perform(login("nobody@fabwatch.dev"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn();
        assertThat(Integer.parseInt(denied.getResponse().getHeader("Retry-After"))).isBetween(1, 60);

        // /auth/refresh는 따로 센다 — 로그인 버킷이 찼어도 첫 호출은 처리(위조 토큰이라 401 INVALID_TOKEN)
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"forged\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("본문 크기 상한 초과(Content-Length) — 비인증 요청도 인증 전에 413 PAYLOAD_TOO_LARGE")
    void 본문_상한_413() throws Exception {
        String big = "{\"email\":\"a@b.co\",\"password\":\"" + "x".repeat(3000) + "\"}";

        mockMvc.perform(post("/api/v1/alarms/manual").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("SSE 전체 연결 상한 — 초과 연결은 429 RATE_LIMITED JSON 본문 (Accept: text/event-stream이어도)")
    void sse_전체_상한() throws Exception {
        // 레이트 리밋(로그인 3회/분)을 쓰지 않도록 토큰은 JWT를 직접 만들지 않고 첫 로그인 1회만 사용
        // (이 클래스에서 로그인은 위 테스트와 합쳐 버킷을 공유하므로 여기서는 로그인하지 않고 provider로 발급한다)
        String token = accessToken(1L, "ENGINEER");
        mockMvc.perform(get("/api/v1/stream/sensors").header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        MvcResult second = mockMvc.perform(get("/api/v1/stream/sensors").header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(second.getResponse().getContentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
    }

    @Autowired
    private com.fabwatch.common.security.JwtTokenProvider tokenProvider;

    private String accessToken(Long userId, String role) {
        return tokenProvider.createAccessToken(userId, "테스트", role);
    }

    private static org.springframework.test.web.servlet.RequestBuilder login(String email) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}");
    }
}
