package com.fabwatch.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CORS 허용 Origin — 환경변수 CORS_ALLOWED_ORIGINS로 주입. `*` 금지 (docs/11 §4).
 */
@ConfigurationProperties(prefix = "fabwatch.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
