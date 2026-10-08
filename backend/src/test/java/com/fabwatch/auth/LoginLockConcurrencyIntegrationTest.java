package com.fabwatch.auth;

import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 동시 실패 로그인에서도 실패 카운터가 유실되지 않고 정확히 5회째에 잠기는지 (보안 감사 M-2 b).
 * 계정 행을 FOR UPDATE로 잡고 갱신하므로 병렬 요청이 직렬화된다. 계정을 잠그므로 전용 컨텍스트를 쓴다.
 */
@SpringBootTest(properties = {
        "fabwatch.seed.enabled=true",
        "fabwatch.test.context-isolation=login-lock-concurrency"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class LoginLockConcurrencyIntegrationTest {

    private static final String EMAIL = "engineer@fabwatch.dev";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("6개 스레드가 동시에 틀린 비밀번호로 로그인 — 카운터 유실 없이 잠기고 정확히 5건은 실패 처리")
    void 동시_실패_6회는_잠김() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Integer> attempt = () -> {
                ready.countDown();
                go.await();
                return mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"wrong-password\"}"))
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(attempt));
        }
        ready.await();
        go.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : results) {
            statuses.add(future.get());
        }
        pool.shutdown();

        // 1~4번째는 401, 5번째에 잠금(429), 6번째는 이미 잠겨 429 — 직렬화되므로 순서와 무관하게 개수가 정확하다
        assertThat(statuses).filteredOn(code -> code == 401).hasSize(4);
        assertThat(statuses).filteredOn(code -> code == 429).hasSize(2);

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getFailedLoginCount()).isEqualTo(User.MAX_LOGIN_FAILURES);
        assertThat(user.isLocked(java.time.Instant.now())).isTrue();
    }
}
