package com.fabwatch.auth.service;

import com.fabwatch.auth.dto.LoginRequest;
import com.fabwatch.auth.dto.TokenRefreshRequest;
import com.fabwatch.auth.dto.TokenResponse;
import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 인증 서비스 단위 테스트 (docs/03 F-1 예외 표, docs/11 §2).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final Long USER_ID = 1L;
    private static final String EMAIL = "tech@fabwatch.dev";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider tokenProvider;

    @InjectMocks
    private AuthService authService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .email(EMAIL)
                .password("{bcrypt}hashed")
                .name("이테크니션")
                .role(Role.TECHNICIAN)
                .enabled(true)
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(tokenProvider.createAccessToken(anyLong(), anyString(), anyString())).willReturn("access-token");
        given(tokenProvider.createRefreshToken(anyLong())).willReturn(USER_ID + ".refresh-token");
        given(tokenProvider.refreshTokenExpiresAt()).willReturn(Instant.now().plus(14, ChronoUnit.DAYS));
        given(tokenProvider.accessTokenExpiresInSeconds()).willReturn(1800L);
        given(tokenProvider.extractUserIdFromRefreshToken(anyString())).willReturn(USER_ID);
    }

    @Test
    @DisplayName("로그인 성공 — 토큰 쌍 발급 + Refresh DB 저장 + 실패 카운트 초기화")
    void 로그인_성공() {
        given(passwordEncoder.matches(any(), any())).willReturn(true);
        user.recordLoginFailure(Instant.now()); // 이전 실패 1회

        TokenResponse response = authService.login(new LoginRequest(EMAIL, "fabwatch123"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo(USER_ID + ".refresh-token");
        assertThat(response.user().role()).isEqualTo("TECHNICIAN");
        assertThat(user.getRefreshToken()).isEqualTo(USER_ID + ".refresh-token");
        assertThat(user.getFailedLoginCount()).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 이메일 — 계정 존재 여부를 노출하지 않고 LOGIN_FAILED")
    void 로그인_없는_이메일() {
        given(userRepository.findByEmail("nobody@fabwatch.dev")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@fabwatch.dev", "fabwatch123")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("비밀번호 불일치 — LOGIN_FAILED + 실패 카운트 증가")
    void 로그인_비밀번호_불일치() {
        given(passwordEncoder.matches(any(), any())).willReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong-pass")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
        assertThat(user.getFailedLoginCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("5회 연속 실패 — 5번째 시도부터 ACCOUNT_LOCKED(429)")
    void 로그인_5회_실패시_잠금() {
        given(passwordEncoder.matches(any(), any())).willReturn(false);
        LoginRequest request = new LoginRequest(EMAIL, "wrong-pass");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(request))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.LOGIN_FAILED);
        }

        assertThatThrownBy(() -> authService.login(request))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        // 잠금 이후에는 올바른 비밀번호여도 거부된다
        given(passwordEncoder.matches(any(), any())).willReturn(true);
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "fabwatch123")))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    @DisplayName("비활성 사용자 — USER_DISABLED(403)")
    void 로그인_비활성_사용자() {
        given(passwordEncoder.matches(any(), any())).willReturn(true);
        ReflectionTestUtils.setField(user, "enabled", false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "fabwatch123")))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_DISABLED);
    }

    @Test
    @DisplayName("리프레시 성공 — 토큰 회전(기존 Refresh는 더 이상 유효하지 않다)")
    void 리프레시_회전() {
        String issued = USER_ID + ".refresh-token";
        user.updateRefreshToken(issued, Instant.now().plus(14, ChronoUnit.DAYS));
        given(tokenProvider.createRefreshToken(anyLong())).willReturn(USER_ID + ".rotated-token");

        TokenResponse response = authService.refresh(new TokenRefreshRequest(issued));

        assertThat(response.refreshToken()).isEqualTo(USER_ID + ".rotated-token");
        assertThat(user.getRefreshToken()).isEqualTo(USER_ID + ".rotated-token");
    }

    @Test
    @DisplayName("이미 회전된 Refresh 재사용 — INVALID_TOKEN + 해당 사용자 전체 세션 무효화")
    void 리프레시_재사용_감지() {
        user.updateRefreshToken(USER_ID + ".rotated-token", Instant.now().plus(14, ChronoUnit.DAYS));

        assertThatThrownBy(() -> authService.refresh(new TokenRefreshRequest(USER_ID + ".old-token")))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
        assertThat(user.getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("만료된 Refresh — INVALID_TOKEN + 저장된 토큰 제거")
    void 리프레시_만료() {
        String issued = USER_ID + ".refresh-token";
        user.updateRefreshToken(issued, Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> authService.refresh(new TokenRefreshRequest(issued)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
        assertThat(user.getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("로그아웃 — DB의 Refresh 무효화")
    void 로그아웃() {
        user.updateRefreshToken(USER_ID + ".refresh-token", Instant.now().plus(14, ChronoUnit.DAYS));

        authService.logout(USER_ID);

        assertThat(user.getRefreshToken()).isNull();
    }
}
