package com.fabwatch.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정 (docs/11 §2, docs/13 §3 환경변수 JWT_*).
 * secret은 환경변수로만 주입한다 — 코드 하드코딩 금지.
 */
@ConfigurationProperties(prefix = "fabwatch.jwt")
public record JwtProperties(String secret, long accessExpMinutes, long refreshExpDays) {
}
