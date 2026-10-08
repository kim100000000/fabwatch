package com.fabwatch.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 요청 방어 설정 (fabwatch.security.*, docs/11 §4·docs/13 §3).
 *
 * @param rateLimit       로그인·리프레시 IP별 레이트 리밋
 * @param maxRequestBytes 요청 본문 크기 상한(바이트). 기본 1MB. 초과 시 413 PAYLOAD_TOO_LARGE
 */
@ConfigurationProperties(prefix = "fabwatch.security")
public record SecurityProperties(RateLimit rateLimit, Long maxRequestBytes) {

    public static final int DEFAULT_AUTH_PER_MINUTE = 30;
    public static final long DEFAULT_MAX_REQUEST_BYTES = 1024 * 1024;

    public SecurityProperties {
        if (rateLimit == null) {
            rateLimit = new RateLimit(null);
        }
        if (maxRequestBytes == null || maxRequestBytes <= 0) {
            maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES;
        }
    }

    /** @param authPerMinute /auth/login, /auth/refresh 각각에 대해 IP당 분당 허용 횟수 (기본 30) */
    public record RateLimit(Integer authPerMinute) {

        public RateLimit {
            if (authPerMinute == null || authPerMinute <= 0) {
                authPerMinute = DEFAULT_AUTH_PER_MINUTE;
            }
        }
    }
}
