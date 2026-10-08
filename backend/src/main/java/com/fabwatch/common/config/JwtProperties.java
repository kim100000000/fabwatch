package com.fabwatch.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정 (docs/11 §2, docs/13 §3 환경변수 JWT_*).
 * secret은 환경변수로만 주입한다 — 코드 하드코딩 금지.
 *
 * @param refreshGraceSeconds 회전 직후 직전 Refresh 토큰을 허용하는 유예 시간(초). 같은 브라우저의 탭 두 개나
 *                            동시 401 재시도처럼 같은 토큰으로 병렬 갱신이 들어와도 한쪽이 로그아웃되지 않게 한다.
 *                            생략하면 10초, 0이면 유예 없음(회전된 토큰은 즉시 무효).
 */
@ConfigurationProperties(prefix = "fabwatch.jwt")
public record JwtProperties(String secret, long accessExpMinutes, long refreshExpDays, Long refreshGraceSeconds) {

    public static final long DEFAULT_REFRESH_GRACE_SECONDS = 10;

    public JwtProperties {
        if (refreshGraceSeconds == null || refreshGraceSeconds < 0) {
            refreshGraceSeconds = DEFAULT_REFRESH_GRACE_SECONDS;
        }
    }
}
