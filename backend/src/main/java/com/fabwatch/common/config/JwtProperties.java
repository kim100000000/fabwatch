package com.fabwatch.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

/**
 * JWT 설정 (docs/11 §2, docs/13 §3 환경변수 JWT_*).
 * secret은 환경변수로만 주입한다 — 코드 하드코딩 금지. 비었거나 32바이트 미만이면 기동 실패(local 개발 키는 application-local.yml).
 *
 * @param refreshGraceSeconds 회전 직후 직전 Refresh 토큰을 허용하는 유예 시간(초). 같은 브라우저의 탭 두 개나
 *                            동시 401 재시도처럼 같은 토큰으로 병렬 갱신이 들어와도 한쪽이 로그아웃되지 않게 한다.
 *                            생략하면 10초, 0이면 유예 없음(회전된 토큰은 즉시 무효).
 */
@ConfigurationProperties(prefix = "fabwatch.jwt")
public record JwtProperties(String secret, long accessExpMinutes, long refreshExpDays, Long refreshGraceSeconds) {

    public static final long DEFAULT_REFRESH_GRACE_SECONDS = 10;

    /** HS256 서명 키 최소 길이(바이트) = 256bit. 미만이면 기동 실패 (docs/11 §2) */
    public static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        // fail-fast: 운영에서 JWT_SECRET이 비었거나 짧은 채로 떠서 토큰이 위조 가능해지는 것을 막는다.
        // 오류 메시지에 값(또는 일부)을 절대 넣지 않는다 — 시작 로그로 키가 새는 것 방지 (docs/11 P-5).
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "fabwatch.jwt.secret(환경변수 JWT_SECRET)이 설정되지 않았습니다. 256bit(32바이트) 이상의 랜덤 값이 필요합니다.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "fabwatch.jwt.secret(환경변수 JWT_SECRET)이 너무 짧습니다. 최소 " + MIN_SECRET_BYTES + "바이트(256bit) 이상이어야 합니다.");
        }
        if (refreshGraceSeconds == null || refreshGraceSeconds < 0) {
            refreshGraceSeconds = DEFAULT_REFRESH_GRACE_SECONDS;
        }
    }
}
