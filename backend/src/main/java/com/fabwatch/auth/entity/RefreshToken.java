package com.fabwatch.auth.entity;

import com.fabwatch.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * Refresh 토큰 세션 (docs/05 refresh_tokens, docs/11 §2).
 * 한 행 = 한 기기(로그인) 세션. 사용자당 최대 5행(RefreshTokenService.MAX_SESSIONS_PER_USER).
 *
 * - 토큰 원문은 저장하지 않는다. SHA-256 hex(64자)만 보관한다 — DB가 유출돼도 세션을 탈취할 수 없게.
 * - 회전: 같은 행의 token_hash를 새 토큰 해시로 교체하고, 직전 해시를 previous_token_hash에 둔다.
 *   previous는 rotated_at 기준 유예 시간 동안만 유효하다(병렬 갱신 허용).
 * - 자격 증명 저장소이므로 soft delete 대상이 아니다: 로그아웃·만료·용량 초과 시 행을 실제로 삭제한다
 *   (이력이 아니라 세션 상태이며, 해시를 남겨 둘 이유가 없다).
 *
 * // TENANT: users 기준으로 사용자가 테넌트에 묶이므로 이 테이블에는 tenant_id를 두지 않는다
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user", columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_expires", columnList = "expires_at"),
        @Index(name = "idx_refresh_tokens_prev", columnList = "previous_token_hash")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseEntity {

    /** 도메인 내부 FK지만 다른 곳과 같이 Long ID만 보관한다 (User 엔티티 로딩 없이 일괄 삭제하기 위함) */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    /** 직전 회전 전 해시. 유예 시간(rotated_at 이후) 안에서만 유효. */
    @Column(name = "previous_token_hash", length = 64)
    private String previousTokenHash;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    public static RefreshToken issue(Long userId, String tokenHash, Instant now, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.userId = userId;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        token.lastUsedAt = now;
        return token;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** 제시된 해시가 직전(회전 전) 토큰이고 유예 시간 안인가 */
    public boolean isWithinGrace(String presentedHash, Instant now, Duration grace) {
        return previousTokenHash != null
                && previousTokenHash.equals(presentedHash)
                && rotatedAt != null
                && !grace.isZero()
                && now.isBefore(rotatedAt.plus(grace));
    }

    /** 새 토큰으로 교체 — 현재 해시는 previous로 내리고 만료를 연장한다(슬라이딩). */
    public void rotate(String newTokenHash, Instant now, Instant newExpiresAt) {
        this.previousTokenHash = this.tokenHash;
        this.tokenHash = newTokenHash;
        this.rotatedAt = now;
        this.lastUsedAt = now;
        this.expiresAt = newExpiresAt;
    }
}
