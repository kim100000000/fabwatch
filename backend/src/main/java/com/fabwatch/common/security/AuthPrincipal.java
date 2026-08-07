package com.fabwatch.common.security;

/**
 * 인증 주체 — JWT 클레임에서 복원한 최소 정보. DB 조회 없이 필터에서 구성한다(무상태).
 */
public record AuthPrincipal(Long userId, String name, String role) {
}
