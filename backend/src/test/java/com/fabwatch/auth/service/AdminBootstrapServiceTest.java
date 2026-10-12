package com.fabwatch.auth.service;

import com.fabwatch.auth.config.AdminBootstrapProperties;
import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 운영 최초 ADMIN 부트스트랩 규칙 — users가 비어 있을 때만, 정책 검증, 비밀번호 비노출. */
class AdminBootstrapServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private AdminBootstrapService service(String email, String password, String name) {
        return new AdminBootstrapService(userRepository, passwordEncoder,
                new AdminBootstrapProperties(email, password, name));
    }

    @Test
    @DisplayName("users가 비어 있고 설정이 정상이면 ADMIN 1명을 BCrypt 해시로 생성한다")
    void 빈_테이블이면_생성() {
        when(userRepository.count()).thenReturn(0L);

        AdminBootstrapService.Result result = service(" root@example.com ", "Passw0rdOk", "운영관리자").bootstrapIfEmpty();

        assertThat(result).isEqualTo(AdminBootstrapService.Result.CREATED);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("root@example.com");   // 공백 제거
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getName()).isEqualTo("운영관리자");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getPassword()).isNotEqualTo("Passw0rdOk");   // 평문 저장 금지
        assertThat(passwordEncoder.matches("Passw0rdOk", saved.getPassword())).isTrue();
    }

    @Test
    @DisplayName("이름을 생략하면 기본 이름 '관리자'")
    void 이름_기본값() {
        when(userRepository.count()).thenReturn(0L);

        service("root@example.com", "Passw0rdOk", null).bootstrapIfEmpty();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("관리자");
    }

    @Test
    @DisplayName("사용자가 이미 있으면 아무것도 하지 않는다 — 잘못된 설정이 남아 있어도 기동에 영향 없음(멱등)")
    void 사용자_있으면_건너뜀() {
        when(userRepository.count()).thenReturn(3L);

        AdminBootstrapService.Result result = service("not-an-email", "weak", null).bootstrapIfEmpty();

        assertThat(result).isEqualTo(AdminBootstrapService.Result.SKIPPED_USERS_EXIST);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("설정이 비어 있으면 생성하지 않고 건너뛴다(경고 로그만, 기동은 계속)")
    void 미설정이면_건너뜀() {
        when(userRepository.count()).thenReturn(0L);

        assertThat(service("", "", null).bootstrapIfEmpty()).isEqualTo(AdminBootstrapService.Result.SKIPPED_NOT_CONFIGURED);
        assertThat(service(null, null, null).bootstrapIfEmpty()).isEqualTo(AdminBootstrapService.Result.SKIPPED_NOT_CONFIGURED);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("비밀번호가 정책 위반이면 기동 실패 — 메시지에 비밀번호가 들어가지 않는다")
    void 약한_비밀번호_거부() {
        when(userRepository.count()).thenReturn(0L);

        for (String weak : new String[]{"short1", "onlyletters", "12345678"}) {
            assertThatThrownBy(() -> service("root@example.com", weak, null).bootstrapIfEmpty())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(weak);
        }
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이메일만 있거나 비밀번호만 있으면(반쪽 설정) 기동 실패")
    void 반쪽_설정_거부() {
        when(userRepository.count()).thenReturn(0L);

        assertThatThrownBy(() -> service("root@example.com", "", null).bootstrapIfEmpty())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service("", "Passw0rdOk", null).bootstrapIfEmpty())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("Passw0rdOk");
    }

    @Test
    @DisplayName("이메일 형식 오류/100자 초과는 기동 실패")
    void 이메일_형식_거부() {
        when(userRepository.count()).thenReturn(0L);

        assertThatThrownBy(() -> service("no-at-sign", "Passw0rdOk", null).bootstrapIfEmpty())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service("a@b@c.com", "Passw0rdOk", null).bootstrapIfEmpty())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service("x".repeat(95) + "@a.com", "Passw0rdOk", null).bootstrapIfEmpty())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("설정 객체 toString에 비밀번호가 노출되지 않는다")
    void toString_마스킹() {
        String text = new AdminBootstrapProperties("root@example.com", "Passw0rdOk", "x").toString();
        assertThat(text).doesNotContain("Passw0rdOk");
    }
}
