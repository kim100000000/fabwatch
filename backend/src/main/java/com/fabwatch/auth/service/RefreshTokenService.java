package com.fabwatch.auth.service;

import com.fabwatch.auth.entity.RefreshToken;
import com.fabwatch.auth.repository.RefreshTokenRepository;
import com.fabwatch.common.config.JwtProperties;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.common.security.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Refresh 토큰 세션 관리 (docs/11 §2). 호출하는 AuthService의 트랜잭션에 참여한다.
 *
 * - 발급: 새 행 저장(해시만), 사용자당 최대 5세션 — 초과 시 가장 오래 쓰지 않은 세션 삭제, 만료 행 기회적 정리
 * - 회전: 제시된 토큰의 해시로 행 조회 → 같은 행의 해시를 새 토큰으로 교체. 직전 해시는 유예 시간 동안만 허용
 * - 폐기: 로그아웃은 제시된 토큰의 행만 삭제
 *
 * ★ 알 수 없는 토큰·만료 토큰은 401만 반환하고 어떤 행도 지우지 않는다.
 *   (이전 구현은 "재사용 감지"로 사용자 세션 전체를 지워, 비인증 요청으로 타인을 로그아웃시킬 수 있었다 — 보안 감사 H-1)
 *
 * // SCALE: 세션 제한·정리는 요청 시 수행한다(단일 인스턴스·소규모 전제). 대규모에서는 배치 정리로 분리.
 */
@Slf4j
@Service
public class RefreshTokenService {

    /** 사용자당 동시 세션 수 */
    public static final int MAX_SESSIONS_PER_USER = 5;

    private final RefreshTokenRepository repository;
    private final JwtTokenProvider tokenProvider;
    private final Duration grace;

    public RefreshTokenService(RefreshTokenRepository repository, JwtTokenProvider tokenProvider,
                               JwtProperties properties) {
        this.repository = repository;
        this.tokenProvider = tokenProvider;
        this.grace = Duration.ofSeconds(properties.refreshGraceSeconds());
    }

    /** 새 세션 발급 → 토큰 원문(응답에만 사용, 저장·로그 금지) */
    @Transactional
    public String issue(Long userId, Instant now) {
        repository.deleteExpired(now);

        String token = tokenProvider.createRefreshToken();
        repository.save(RefreshToken.issue(userId, RefreshTokenHasher.hash(token), now,
                tokenProvider.refreshTokenExpiresAt(now)));

        // 용량 초과 — 가장 오래 쓰지 않은 세션부터 삭제 (방금 만든 행은 last_used_at이 가장 최신이라 남는다)
        List<RefreshToken> sessions = repository.findByUserIdOrderByLastUsedAtAscIdAsc(userId);
        int excess = sessions.size() - MAX_SESSIONS_PER_USER;
        if (excess > 0) {
            repository.deleteAll(sessions.subList(0, excess));
            log.info("Refresh 세션 한도 초과로 오래된 세션 {}건 폐기: userId={}", excess, userId);
        }
        return token;
    }

    /** 회전 결과 — 소유 사용자와 새 토큰 원문 */
    public record Rotation(Long userId, String newToken) {
    }

    /**
     * 제시된 토큰을 새 토큰으로 회전한다.
     *
     * @throws BusinessException INVALID_TOKEN — 알 수 없는 토큰(위조·폐기·유예 초과 포함) 또는 만료. 부작용 없음.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Rotation rotate(String presentedToken, Instant now) {
        String hash = RefreshTokenHasher.hash(presentedToken);
        RefreshToken row = repository.findForRefresh(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));

        boolean current = row.getTokenHash().equals(hash);
        if (!current && !row.isWithinGrace(hash, now, grace)) {
            // 직전 토큰이지만 유예 시간이 지났다 — 폐기된 토큰과 같은 취급(아무것도 지우지 않는다)
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        if (row.isExpired(now)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "리프레시 토큰이 만료되었습니다. 다시 로그인하세요.");
        }

        String newToken = tokenProvider.createRefreshToken();
        row.rotate(RefreshTokenHasher.hash(newToken), now, tokenProvider.refreshTokenExpiresAt(now));
        return new Rotation(row.getUserId(), newToken);
    }

    /** 로그아웃 — 제시된 토큰이 본인 소유일 때 그 세션 행만 삭제. 다른 기기의 세션은 그대로. */
    @Transactional
    public boolean revoke(Long userId, String presentedToken) {
        return repository.deleteByUserIdAndHash(userId, RefreshTokenHasher.hash(presentedToken)) > 0;
    }

    /** 사용자의 모든 세션 폐기(비활성화·토큰 없는 구형 로그아웃 요청) */
    @Transactional
    public int revokeAll(Long userId) {
        return repository.deleteAllByUserId(userId);
    }
}
