package com.fabwatch.common.security;

import com.fabwatch.common.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * JWT 발급/검증 (docs/11 §2).
 * - Access: JWT(HS256), 30분, claims = sub(userId) / role / name
 * - Refresh: 불투명 랜덤 문자열(UUID + SecureRandom), 14일, DB 저장
 *   형식은 "{userId}.{random}" — 재사용 감지 시 어떤 사용자의 세션을 무효화할지 식별하기 위함.
 */
@Component
public class JwtTokenProvider {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final Duration accessExp;
    private final Duration refreshExp;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessExp = Duration.ofMinutes(properties.accessExpMinutes());
        this.refreshExp = Duration.ofDays(properties.refreshExpDays());
    }

    public String createAccessToken(Long userId, String name, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("name", name)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(accessExp)))
                .signWith(key)
                .compact();
    }

    /** 서명·만료 검증 후 클레임 반환. 만료 시 ExpiredJwtException, 그 외 위조는 JwtException. */
    public Claims parseAccessToken(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public AuthPrincipal toPrincipal(Claims claims) {
        return new AuthPrincipal(
                Long.valueOf(claims.getSubject()),
                claims.get("name", String.class),
                claims.get("role", String.class));
    }

    /** 불투명 Refresh 토큰 생성. "{userId}.{random}" */
    public String createRefreshToken(Long userId) {
        byte[] buffer = new byte[32];
        RANDOM.nextBytes(buffer);
        String random = UUID.randomUUID() + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
        return userId + "." + random;
    }

    /** Refresh 토큰에서 사용자 ID 추출. 형식이 깨졌으면 null. */
    public Long extractUserIdFromRefreshToken(String refreshToken) {
        if (refreshToken == null) {
            return null;
        }
        int dot = refreshToken.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            return Long.valueOf(refreshToken.substring(0, dot));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Instant refreshTokenExpiresAt() {
        return Instant.now().plus(refreshExp);
    }

    public long accessTokenExpiresInSeconds() {
        return accessExp.toSeconds();
    }
}
