package com.fabwatch.auth;

import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import com.fabwatch.auth.service.AdminBootstrapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영형 부트스트랩 통합 — local이 아닌 프로파일(test)에서 기동 시 ADMIN 1명이 생기고, 그 계정으로 실제 로그인되며,
 * 재실행해도 늘어나지 않는다(멱등). 시연 시드(fabwatch123)는 쓰이지 않는다.
 */
@SpringBootTest(properties = {
        "fabwatch.bootstrap.admin.email=root@fabwatch.example",
        "fabwatch.bootstrap.admin.password=Bootstrap-Pw-1",
        "fabwatch.bootstrap.admin.name=최초관리자",
        "fabwatch.test.context-isolation=admin-bootstrap"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminBootstrapIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminBootstrapService bootstrapService;
    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("기동 시 ADMIN 1명 생성 → 로그인 가능 → 재실행해도 1명 유지")
    void 기동시_ADMIN_생성_멱등() throws Exception {
        assertThat(userRepository.count()).isEqualTo(1);
        User admin = userRepository.findByEmail("root@fabwatch.example").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getName()).isEqualTo("최초관리자");
        assertThat(admin.getPassword()).startsWith("$2"); // BCrypt 해시, 평문 아님

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"root@fabwatch.example\",\"password\":\"Bootstrap-Pw-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());

        // 재기동/재배포 시뮬레이션: 한 번 더 실행해도 건너뛴다
        assertThat(bootstrapService.bootstrapIfEmpty()).isEqualTo(AdminBootstrapService.Result.SKIPPED_USERS_EXIST);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("시연 시드 계정(admin@fabwatch.dev / fabwatch123)은 만들어지지 않는다")
    void 시드_계정_없음() {
        assertThat(userRepository.findByEmail("admin@fabwatch.dev")).isEmpty();
    }
}
