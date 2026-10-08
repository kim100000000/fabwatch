package com.fabwatch.auth.service;

import com.fabwatch.auth.dto.LoginRequest;
import com.fabwatch.auth.dto.TokenRefreshRequest;
import com.fabwatch.auth.dto.TokenResponse;
import com.fabwatch.auth.dto.UserSummaryResponse;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 인증 서비스 (docs/03 F-1, docs/06 §1, docs/11 §2).
 * - 로그인: 계정 행 잠금 → 잠금 확인 → 비밀번호 확인 → 활성 확인 → 토큰 발급(세션 1개 추가)
 * - 리프레시: 제시된 토큰의 해시로 세션 조회 → 회전. 알 수 없는/만료 토큰은 401만(아무것도 지우지 않음)
 * - 로그아웃: 제시된 Refresh의 세션 행만 삭제 (Access 블랙리스트는 만들지 않는다 — 과설계)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * 존재하지 않는 이메일에도 BCrypt 비교를 한 번 수행하기 위한 고정 더미 해시(strength 10, SecurityConfig와 동일).
     * 계정이 없을 때만 응답이 빨라(약 6ms vs 140ms) 이메일 존재 여부가 드러나는 것을 막는다.
     * 어떤 비밀번호와도 의미 있게 대응하지 않는 값이며 실제 계정과 무관하다.
     */
    static final String DUMMY_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;

    /**
     * noRollbackFor: 로그인 실패 카운트 증가(잠금 판정)는 예외를 던져도 반드시 커밋되어야 한다.
     * 기본 롤백 규칙을 쓰면 5회 실패 잠금이 영원히 동작하지 않는다.
     *
     * 계정 행을 FOR UPDATE로 잡고 카운트를 갱신하므로 동시 실패 요청이 직렬화되어 정확히 5회째에 잠긴다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse login(LoginRequest request) {
        Instant now = Instant.now();
        Optional<User> found = userRepository.findWithLockByEmail(request.email());
        if (found.isEmpty()) {
            // 없는 이메일도 같은 시간이 걸리게 더미 비교를 수행한 뒤 비밀번호 오류와 동일하게 응답 (계정 존재 여부 노출 방지)
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        User user = found.get();

        if (user.isLocked(now)) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            user.recordLoginFailure(now);
            log.warn("로그인 실패 (누적 {}회): userId={}", user.getFailedLoginCount(), user.getId());
            if (user.isLocked(now)) {
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            }
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        if (!user.isEnabled()) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        user.resetLoginFailure();
        return issueTokens(user, refreshTokenService.issue(user.getId(), now));
    }

    /**
     * Refresh 회전. 제시된 토큰의 해시로 세션을 찾아 같은 행을 새 토큰으로 교체한다.
     * 알 수 없는/만료/폐기된 토큰은 401 INVALID_TOKEN만 반환하고 저장된 세션을 절대 지우지 않는다
     * (비인증 요청으로 타인 세션을 끊을 수 없게 — 보안 감사 H-1).
     * 비활성·삭제된 사용자는 거부하고 그 사용자의 세션을 정리한다(토큰을 쥔 본인 요청이므로 안전).
     * 정리가 예외와 함께 커밋되어야 하므로 noRollbackFor를 지정한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse refresh(TokenRefreshRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(request.refreshToken(), Instant.now());

        Optional<User> found = userRepository.findById(rotation.userId());
        if (found.isEmpty()) {
            refreshTokenService.revokeAll(rotation.userId());
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        User user = found.get();
        if (!user.isEnabled()) {
            refreshTokenService.revokeAll(user.getId());
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        return issueTokens(user, rotation.newToken());
    }

    /**
     * 로그아웃 — 제시된 Refresh 토큰의 세션 행만 삭제한다(다른 기기 세션 유지).
     * 토큰을 보내지 않은 구형 요청은 어느 세션인지 알 수 없으므로 안전 쪽으로 그 사용자의 세션을 전부 폐기한다.
     */
    @Transactional
    public void logout(Long userId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshTokenService.revokeAll(userId);
            return;
        }
        refreshTokenService.revoke(userId, refreshToken);
    }

    private TokenResponse issueTokens(User user, String refreshToken) {
        String accessToken = tokenProvider.createAccessToken(user.getId(), user.getName(), user.getRole().name());
        return new TokenResponse(
                accessToken,
                refreshToken,
                tokenProvider.accessTokenExpiresInSeconds(),
                UserSummaryResponse.from(user));
    }
}
