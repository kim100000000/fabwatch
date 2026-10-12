package com.fabwatch.common.seed;

import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADMIN 부트스트랩과 데모 계정을 함께 켠 공개 데모 배포 구성 — 실행 순서가 바뀌면(데모 계정이 먼저 생기면)
 * "users가 비었을 때만" 동작하는 부트스트랩이 건너뛰어 ADMIN이 안 생기는 회귀를 막는다.
 */
@SpringBootTest(properties = {
        "fabwatch.bootstrap.admin.email=root@fabwatch.example",
        "fabwatch.bootstrap.admin.password=Bootstrap-Pw-1",
        "fabwatch.demo.enabled=true",
        "fabwatch.demo.accounts-password=Demo-Account-Pw1",
        "fabwatch.test.context-isolation=demo-with-bootstrap"
})
@ActiveProfiles("test")
class DemoDataWithBootstrapIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("부트스트랩 ADMIN 1 + 데모 ENGINEER/TECHNICIAN 2가 모두 생성된다 (ADMIN은 부트스트랩 계정뿐)")
    void ADMIN과_데모_계정_공존() {
        List<User> users = userRepository.findAll();
        assertThat(users).hasSize(3);
        assertThat(users).filteredOn(u -> u.getRole() == Role.ADMIN).extracting(User::getEmail)
                .containsExactly("root@fabwatch.example");
        assertThat(users).filteredOn(u -> u.getRole() != Role.ADMIN).extracting(User::getEmail)
                .containsExactlyInAnyOrder("engineer@fabwatch.dev", "tech@fabwatch.dev");
    }
}
