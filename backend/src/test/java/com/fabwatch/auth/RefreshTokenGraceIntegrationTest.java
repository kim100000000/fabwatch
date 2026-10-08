package com.fabwatch.auth;

import com.fabwatch.auth.repository.RefreshTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회전 직후 병렬 갱신 대비 유예(grace) — 직전 토큰이 유예 시간 안에는 허용되고 지나면 거부된다.
 * (기본 테스트 프로파일은 grace=0이라 "회전된 토큰 즉시 무효"를 검증하고, 여기서만 10초로 켠다.)
 */
@SpringBootTest(properties = {
        "fabwatch.seed.enabled=true",
        "fabwatch.jwt.refresh-grace-seconds=10",
        "fabwatch.test.context-isolation=refresh-grace"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class RefreshTokenGraceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        refreshTokenRepository.deleteAll();
    }

    @Test
    @DisplayName("유예 안: 같은 토큰으로 병렬 갱신 두 번 모두 성공하고, 두 결과 토큰이 모두 유효하다")
    void 유예_안에서는_두번째_갱신도_성공() throws Exception {
        String original = login();

        String firstResult = refreshToken(original);
        String secondResult = refreshToken(original); // 같은 탭이 동시에 보낸 두 번째 갱신 (유예)

        assertThat(secondResult).isNotEqualTo(firstResult);
        refresh(firstResult).andExpect(status().isOk());   // 직전 결과도 유예 내에서 유효
        refresh(secondResult).andExpect(status().isOk());
        assertThat(refreshTokenRepository.count()).isEqualTo(1); // 한 기기=한 행
    }

    @Test
    @DisplayName("유예 지난 뒤: 회전된 직전 토큰은 401")
    void 유예_지나면_거부() throws Exception {
        String original = login();
        refreshToken(original);
        jdbcTemplate.update("update refresh_tokens set rotated_at = ?",
                java.sql.Timestamp.from(Instant.now().minus(11, ChronoUnit.SECONDS)));

        refresh(original).andExpect(status().isUnauthorized());
    }

    private String login() throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"tech@fabwatch.dev\",\"password\":\"fabwatch123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();
    }

    private String refreshToken(String token) throws Exception {
        return objectMapper.readTree(refresh(token).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("refreshToken").asText();
    }

    private ResultActions refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + token + "\"}"));
    }
}
