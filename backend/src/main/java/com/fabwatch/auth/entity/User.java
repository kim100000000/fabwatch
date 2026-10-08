package com.fabwatch.auth.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Duration;
import java.time.Instant;

/**
 * 사용자 (docs/05 users).
 * // TENANT: 멀티테넌트 전환 시 tenant_id 추가 지점
 *
 * docs/05 대비 추가 컬럼 (docs/11 §2 요구를 저장할 자리가 docs/05에 없어 추가):
 * - failed_login_count / locked_until : 로그인 5회 실패 → 15분 잠금
 * Refresh 토큰은 users 컬럼이 아니라 별도 refresh_tokens 테이블(RefreshToken)에 해시로 저장한다(다중 세션).
 * 기존 DB의 users.refresh_token* 컬럼은 더 이상 읽고 쓰지 않는다(docs/13 마이그레이션 메모).
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class User extends SoftDeletableEntity {

    /** 로그인 실패 잠금 기준 (docs/03 F-1, docs/11 §2) */
    public static final int MAX_LOGIN_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    @Column(name = "email", length = 100, nullable = false, unique = true)
    private String email;

    /** BCrypt 해시. 평문·로그 출력 금지 (docs/11 P-5) */
    @Column(name = "password", length = 255, nullable = false)
    private String password;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Builder
    private User(String email, String password, String name, Role role, boolean enabled) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.role = role;
        this.enabled = enabled;
    }

    /** 잠금 상태 여부 (15분 경과 시 자동 해제) */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 로그인 실패 기록. 5회 도달 시 15분 잠금. */
    public void recordLoginFailure(Instant now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            // 잠금이 이미 만료된 상태면 카운트를 새로 시작한다
            this.failedLoginCount = 0;
            this.lockedUntil = null;
        }
        this.failedLoginCount++;
        if (this.failedLoginCount >= MAX_LOGIN_FAILURES) {
            this.lockedUntil = now.plus(LOCK_DURATION);
        }
    }

    public void resetLoginFailure() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }
}
