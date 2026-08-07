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

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 인증 서비스 (docs/03 F-1, docs/06 §1, docs/11 §2).
 * - 로그인: 잠금 확인 → 비밀번호 확인 → 활성 확인 → 토큰 발급
 * - 리프레시: 회전(rotation) + 재사용 감지 시 전체 세션 무효화
 * - 로그아웃: DB Refresh 삭제 (Access 블랙리스트는 만들지 않는다 — 과설계)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    /**
     * noRollbackFor: 로그인 실패 카운트 증가(잠금 판정)는 예외를 던져도 반드시 커밋되어야 한다.
     * 기본 롤백 규칙을 쓰면 5회 실패 잠금이 영원히 동작하지 않는다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse login(LoginRequest request) {
        Instant now = Instant.now();
        User user = userRepository.findByEmail(request.email())
                // 존재하지 않는 이메일도 비밀번호 오류와 동일하게 처리 (계정 존재 여부 노출 방지)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

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
        return issueTokens(user);
    }

    /**
     * Refresh 회전. 저장값과 다른 토큰이 제시되면 탈취로 간주하고 해당 사용자 세션을 전부 무효화한다.
     * 무효화 역시 예외를 던져도 커밋되어야 하므로 noRollbackFor를 지정한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse refresh(TokenRefreshRequest request) {
        String presented = request.refreshToken();
        Long userId = tokenProvider.extractUserIdFromRefreshToken(presented);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));

        String stored = user.getRefreshToken();
        if (stored == null || !constantTimeEquals(stored, presented)) {
            // 이미 사용/폐기된 토큰 재사용 → 탈취 의심 (docs/11 §2)
            user.clearRefreshToken();
            log.warn("Refresh 토큰 재사용 감지 — 전체 세션 무효화: userId={}", userId);
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        if (user.isRefreshTokenExpired(Instant.now())) {
            user.clearRefreshToken();
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "리프레시 토큰이 만료되었습니다. 다시 로그인하세요.");
        }

        if (!user.isEnabled()) {
            user.clearRefreshToken();
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        return issueTokens(user);
    }

    @Transactional
    public void logout(Long userId) {
        userRepository.findById(userId).ifPresent(User::clearRefreshToken);
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = tokenProvider.createAccessToken(user.getId(), user.getName(), user.getRole().name());
        String refreshToken = tokenProvider.createRefreshToken(user.getId());
        user.updateRefreshToken(refreshToken, tokenProvider.refreshTokenExpiresAt());
        return new TokenResponse(
                accessToken,
                refreshToken,
                tokenProvider.accessTokenExpiresInSeconds(),
                UserSummaryResponse.from(user));
    }

    /** 타이밍 공격 방지용 상수 시간 비교 */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
