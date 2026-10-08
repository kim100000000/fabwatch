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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 인증 서비스 단위 테스트 (docs/03 F-1 예외 표, docs/11 §2).
 * Refresh 세션 저장·회전은 RefreshTokenService(통합 테스트 RefreshTokenApiIntegrationTest)가 검증하고,
 * 여기서는 AuthService의 분기(잠금·더미 비교·비활성·로그아웃 위임)를 본다.
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
    @Mock
    private RefreshTokenService refreshTokenService;

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

        given(userRepository.findWithLockByEmail(EMAIL)).willReturn(Optional.of(user));
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(tokenProvider.createAccessToken(anyLong(), anyString(), anyString())).willReturn("access-token");
        given(tokenProvider.accessTokenExpiresInSeconds()).willReturn(1800L);
        given(refreshTokenService.issue(eq(USER_ID), any())).willReturn("refresh-token");
    }

    @Test
    @DisplayName("로그인 성공 — 토큰 쌍 발급 + Refresh 세션 발급 + 실패 카운트 초기화")
    void 로그인_성공() {
        given(passwordEncoder.matches(any(), any())).willReturn(true);
        user.recordLoginFailure(Instant.now()); // 이전 실패 1회

        TokenResponse response = authService.login(new LoginRequest(EMAIL, "fabwatch123"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.user().role()).isEqualTo("TECHNICIAN");
        assertThat(user.getFailedLoginCount()).isZero();
        verify(refreshTokenService).issue(eq(USER_ID), any());
    }

    @Test
    @DisplayName("존재하지 않는 이메일 — LOGIN_FAILED + 더미 BCrypt 비교를 수행해 응답 시간으로 계정 존재를 알 수 없다")
    void 로그인_없는_이메일_더미_비교() {
        given(userRepository.findWithLockByEmail("nobody@fabwatch.dev")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@fabwatch.dev", "fabwatch123")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);

        // 입력 비밀번호와 고정 더미 해시를 비교했다 (실제 계정 해시가 아님)
        verify(passwordEncoder).matches("fabwatch123", AuthService.DUMMY_PASSWORD_HASH);
        verify(refreshTokenService, never()).issue(any(), any());
    }

    @Test
    @DisplayName("더미 해시는 형식이 올바른 BCrypt(strength 10)여야 한다 — 형식이 틀리면 matches가 즉시 false라 시간 평준화가 무너진다")
    void 더미_해시_형식() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        assertThat(AuthService.DUMMY_PASSWORD_HASH).matches("\\$2a\\$10\\$[./0-9A-Za-z]{53}");
        // 예외 없이 비교가 끝나고(형식 유효) 어떤 일반 비밀번호와도 일치하지 않는다
        assertThat(encoder.matches("fabwatch123", AuthService.DUMMY_PASSWORD_HASH)).isFalse();
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
    @DisplayName("비활성 사용자 — USER_DISABLED(403), 세션 발급 없음")
    void 로그인_비활성_사용자() {
        given(passwordEncoder.matches(any(), any())).willReturn(true);
        ReflectionTestUtils.setField(user, "enabled", false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "fabwatch123")))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_DISABLED);
        verify(refreshTokenService, never()).issue(any(), any());
    }

    @Test
    @DisplayName("리프레시 성공 — 회전 결과의 새 토큰을 응답하고 사용자 세션을 지우지 않는다")
    void 리프레시_회전() {
        given(refreshTokenService.rotate(eq("presented"), any()))
                .willReturn(new RefreshTokenService.Rotation(USER_ID, "rotated-token"));

        TokenResponse response = authService.refresh(new TokenRefreshRequest("presented"));

        assertThat(response.refreshToken()).isEqualTo("rotated-token");
        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(refreshTokenService, never()).revokeAll(any());
    }

    @Test
    @DisplayName("알 수 없는 Refresh(위조·재사용) — INVALID_TOKEN만 반환하고 어떤 세션도 지우지 않는다 (비인증 강제 로그아웃 방지)")
    void 리프레시_불일치_토큰은_아무것도_지우지_않는다() {
        given(refreshTokenService.rotate(eq(USER_ID + ".old-token"), any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_TOKEN));

        assertThatThrownBy(() -> authService.refresh(new TokenRefreshRequest(USER_ID + ".old-token")))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        verify(refreshTokenService, never()).revokeAll(any());
        verify(refreshTokenService, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("비활성 사용자의 Refresh — USER_DISABLED + 그 사용자 세션 정리")
    void 리프레시_비활성_사용자() {
        ReflectionTestUtils.setField(user, "enabled", false);
        given(refreshTokenService.rotate(eq("presented"), any()))
                .willReturn(new RefreshTokenService.Rotation(USER_ID, "rotated-token"));

        assertThatThrownBy(() -> authService.refresh(new TokenRefreshRequest("presented")))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_DISABLED);
        verify(refreshTokenService).revokeAll(USER_ID);
    }

    @Test
    @DisplayName("삭제된(없는) 사용자의 Refresh — INVALID_TOKEN + 세션 정리")
    void 리프레시_삭제된_사용자() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());
        given(refreshTokenService.rotate(eq("presented"), any()))
                .willReturn(new RefreshTokenService.Rotation(USER_ID, "rotated-token"));

        assertThatThrownBy(() -> authService.refresh(new TokenRefreshRequest("presented")))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
        verify(refreshTokenService).revokeAll(USER_ID);
    }

    @Test
    @DisplayName("로그아웃 — 제시된 토큰의 세션만 폐기 (전체 폐기 아님)")
    void 로그아웃_토큰_지정() {
        authService.logout(USER_ID, "presented");

        verify(refreshTokenService).revoke(USER_ID, "presented");
        verify(refreshTokenService, never()).revokeAll(any());
    }

    @Test
    @DisplayName("로그아웃 — 토큰을 안 보낸 구형 요청은 안전 쪽으로 본인 세션 전체 폐기")
    void 로그아웃_토큰_없음() {
        authService.logout(USER_ID, null);
        authService.logout(USER_ID, " ");

        verify(refreshTokenService, org.mockito.Mockito.times(2)).revokeAll(USER_ID);
        verify(refreshTokenService, never()).revoke(any(), any());
    }
}
