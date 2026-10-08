package com.fabwatch.auth;

import com.fabwatch.auth.entity.RefreshToken;
import com.fabwatch.auth.repository.RefreshTokenRepository;
import com.fabwatch.auth.repository.UserRepository;
import com.fabwatch.auth.service.RefreshTokenHasher;
import com.fabwatch.auth.service.RefreshTokenService;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh 토큰 세션(refresh_tokens) 통합 테스트 — 해시 저장, 다중 세션, 회전, 로그아웃 격리, 비인증 강제 로그아웃 방지.
 * 트랜잭션 경계(행 잠금·noRollbackFor)까지 검증해야 하므로 클래스 @Transactional 없이 실제 커밋으로 돌린다.
 * 전용 컨텍스트(=전용 H2)를 쓰고 매 테스트 전에 세션 테이블을 비운다.
 */
@SpringBootTest(properties = {
        "fabwatch.seed.enabled=true",
        "fabwatch.test.context-isolation=refresh-tokens"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class RefreshTokenApiIntegrationTest {

    private static final String TECH = "tech@fabwatch.dev";
    private static final String ENGINEER = "engineer@fabwatch.dev";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        refreshTokenRepository.deleteAll();
        jdbcTemplate.update("update users set enabled = true");
    }

    // ------------------------------------------------------------ 다중 세션 / 로그아웃 격리

    @Test
    @DisplayName("같은 계정으로 2기기 동시 로그인 — 각각 refresh 성공(서로의 세션을 끊지 않는다)")
    void 두_기기_동시_로그인() throws Exception {
        String deviceA = loginRefresh(TECH);
        String deviceB = loginRefresh(TECH);

        String rotatedA = refresh(deviceA).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String rotatedB = refresh(deviceB).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(rotatedA).get("refreshToken").asText()).isNotEqualTo(deviceA);
        assertThat(objectMapper.readTree(rotatedB).get("refreshToken").asText()).isNotEqualTo(deviceB);
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("한쪽 기기 로그아웃은 다른 기기 세션에 영향 없음")
    void 로그아웃_격리() throws Exception {
        JsonNode a = login(TECH);
        String refreshA = a.get("refreshToken").asText();
        String refreshB = loginRefresh(TECH);

        logout(a.get("accessToken").asText(), "{\"refreshToken\":\"" + refreshA + "\"}")
                .andExpect(status().isNoContent());

        refresh(refreshA).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        refresh(refreshB).andExpect(status().isOk());
    }

    @Test
    @DisplayName("본문 없는 구형 로그아웃 — 안전하게 본인 세션 전체 폐기 (다른 사용자는 유지)")
    void 본문_없는_로그아웃은_본인_전체_세션_폐기() throws Exception {
        JsonNode tech = login(TECH);
        String techSecond = loginRefresh(TECH);
        String engineer = loginRefresh(ENGINEER);

        logout(tech.get("accessToken").asText(), null).andExpect(status().isNoContent());

        refresh(tech.get("refreshToken").asText()).andExpect(status().isUnauthorized());
        refresh(techSecond).andExpect(status().isUnauthorized());
        refresh(engineer).andExpect(status().isOk());
    }

    @Test
    @DisplayName("타인의 refresh 토큰으로 로그아웃을 시도해도 그 세션은 지워지지 않는다")
    void 타인_토큰으로_로그아웃_불가() throws Exception {
        JsonNode tech = login(TECH);
        String engineerRefresh = loginRefresh(ENGINEER);

        logout(tech.get("accessToken").asText(), "{\"refreshToken\":\"" + engineerRefresh + "\"}")
                .andExpect(status().isNoContent());

        refresh(engineerRefresh).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ 비인증 강제 로그아웃 방지 / 위조·만료

    @Test
    @DisplayName("위조 토큰(<userId>.아무거나)은 401만 반환하고 저장된 세션을 지우지 않는다 (비인증 강제 로그아웃 불가)")
    void 위조_토큰은_세션을_지우지_않는다() throws Exception {
        String real = loginRefresh(TECH);
        Long techId = userRepository.findByEmail(TECH).orElseThrow().getId();
        long before = refreshTokenRepository.count();

        for (String forged : List.of(techId + ".anything", techId + ".", "." + real, "garbage", real + "x", real.toUpperCase())) {
            refresh(forged).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        assertThat(refreshTokenRepository.count()).isEqualTo(before);
        refresh(real).andExpect(status().isOk()); // 진짜 토큰은 여전히 유효
    }

    @Test
    @DisplayName("이미 회전된(폐기된) 토큰 재사용 — 401이지만 현재 세션은 유지된다")
    void 회전된_토큰_재사용은_현재_세션을_지우지_않는다() throws Exception {
        String first = loginRefresh(TECH);
        String second = objectMapper.readTree(refresh(first).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("refreshToken").asText();

        refresh(first).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        refresh(second).andExpect(status().isOk());
    }

    @Test
    @DisplayName("만료된 세션 — 401 INVALID_TOKEN, 행은 지우지 않는다")
    void 만료_토큰() throws Exception {
        String token = loginRefresh(TECH);
        jdbcTemplate.update("update refresh_tokens set expires_at = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));

        refresh(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("로그인 시 만료된 세션 행은 기회적으로 정리된다")
    void 만료_행_기회적_정리() throws Exception {
        loginRefresh(TECH);
        jdbcTemplate.update("update refresh_tokens set expires_at = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
        assertThat(refreshTokenRepository.count()).isEqualTo(1);

        loginRefresh(ENGINEER);

        assertThat(refreshTokenRepository.count()).isEqualTo(1); // 만료 행 삭제, 방금 로그인한 세션만 남음
    }

    // ------------------------------------------------------------ 해시 저장

    @Test
    @DisplayName("DB에는 토큰 원문이 없고 SHA-256 해시(64 hex)만 저장된다")
    void 해시만_저장() throws Exception {
        String token = loginRefresh(TECH);

        List<RefreshToken> rows = refreshTokenRepository.findAll();
        assertThat(rows).hasSize(1);
        RefreshToken row = rows.get(0);
        assertThat(row.getTokenHash()).isEqualTo(RefreshTokenHasher.hash(token)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(row.getTokenHash()).isNotEqualTo(token);

        // 모든 컬럼을 문자열로 훑어도 원문이 없다 (회전 후 previous_token_hash 포함)
        String rotated = objectMapper.readTree(refresh(token).andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();
        List<String> dump = new ArrayList<>();
        jdbcTemplate.query("select * from refresh_tokens", rs -> {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                dump.add(String.valueOf(rs.getObject(i)));
            }
        });
        assertThat(dump).noneMatch(value -> value.contains(token) || value.contains(rotated));
    }

    // ------------------------------------------------------------ 세션 한도

    @Test
    @DisplayName("6번째 로그인 — 가장 오래 쓰지 않은 세션이 무효화되고 최근 5개만 남는다")
    void 세션_한도_5개() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < RefreshTokenService.MAX_SESSIONS_PER_USER + 1; i++) {
            tokens.add(loginRefresh(TECH));
        }

        assertThat(refreshTokenRepository.count()).isEqualTo(RefreshTokenService.MAX_SESSIONS_PER_USER);
        refresh(tokens.get(0)).andExpect(status().isUnauthorized()); // 가장 오래된 세션
        for (int i = 1; i < tokens.size(); i++) {
            refresh(tokens.get(i)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("세션 한도는 사용자별 — 다른 사용자의 세션은 밀려나지 않는다")
    void 세션_한도는_사용자별() throws Exception {
        String engineer = loginRefresh(ENGINEER);
        for (int i = 0; i < 7; i++) {
            loginRefresh(TECH);
        }
        refresh(engineer).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ 비활성 사용자

    @Test
    @DisplayName("비활성화된 사용자의 refresh — 403 USER_DISABLED, 그 사용자의 세션 정리")
    void 비활성_사용자_refresh_거부() throws Exception {
        String token = loginRefresh(TECH);
        loginRefresh(TECH);
        jdbcTemplate.update("update users set enabled = false where email = ?", TECH);

        refresh(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_DISABLED"));

        jdbcTemplate.update("update users set enabled = true where email = ?", TECH);
        // 재활성화해도 죽은 세션이 되살아나지 않는다
        refresh(token).andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.count()).isZero();
    }

    @Test
    @DisplayName("soft delete된 사용자의 refresh — 401")
    void 삭제된_사용자_refresh_거부() throws Exception {
        String token = loginRefresh(ENGINEER);
        jdbcTemplate.update("update users set deleted_at = CURRENT_TIMESTAMP where email = ?", ENGINEER);
        try {
            refresh(token).andExpect(status().isUnauthorized());
        } finally {
            jdbcTemplate.update("update users set deleted_at = null where email = ?", ENGINEER);
        }
    }

    // ------------------------------------------------------------ 헬퍼

    private JsonNode login(String email) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"fabwatch123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String loginRefresh(String email) throws Exception {
        return login(email).get("refreshToken").asText();
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private ResultActions logout(String accessToken, String body) throws Exception {
        var request = post("/api/v1/auth/logout").header("Authorization", "Bearer " + accessToken);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }
}
