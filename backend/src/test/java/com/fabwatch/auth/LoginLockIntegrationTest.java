package com.fabwatch.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 5회 실패 → 15분 잠금(429)이 DB에 실제로 반영되는지 검증한다 (docs/03 F-1, docs/11 §2).
 * 실패 카운트가 예외와 함께 롤백되면 잠금이 영원히 동작하지 않으므로, 단위 테스트가 아니라
 * 트랜잭션 경계를 포함한 통합 테스트가 필요하다.
 *
 * 계정을 실제로 잠그므로 다른 통합 테스트와 컨텍스트/DB가 섞이면 안 된다 — 테스트 프로퍼티로 전용 컨텍스트를 만들고,
 * application-test.yml의 H2 URL(${random.uuid})이 컨텍스트별 고유 DB를 보장한다.
 */
@SpringBootTest(properties = {
        "fabwatch.seed.enabled=true",
        // 컨텍스트 캐시 키를 다른 통합 테스트와 다르게 만들어 전용 컨텍스트(=전용 H2 DB)를 쓰게 한다.
        // (설정이 같으면 컨텍스트가 공유되어 여기서 잠근 계정이 다른 테스트 로그인을 429로 막는다)
        "fabwatch.test.context-isolation=login-lock"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class LoginLockIntegrationTest {

    private static final String EMAIL = "tech@fabwatch.dev";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("비밀번호 5회 연속 실패 시 429 ACCOUNT_LOCKED — 이후 올바른 비밀번호도 거부")
    void 로그인_5회_실패시_계정_잠금() throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(loginRequest("wrong-password"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        }

        mockMvc.perform(loginRequest("wrong-password"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        mockMvc.perform(loginRequest("fabwatch123"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    private org.springframework.test.web.servlet.RequestBuilder loginRequest(String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}");
    }
}
