package com.fabwatch.aireport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Locale;

/**
 * AI 리포트 설정 (docs/13 환경변수 ANTHROPIC_API_KEY / AI_PROVIDER / AI_MODEL / AI_MAX_TOKENS / AI_EFFORT / AI_TIMEOUT_SECONDS / AI_DAILY_QUOTA / AI_USER_DAILY_QUOTA).
 *
 * <p>apiKey는 환경변수로만 주입하고 절대 로그·에러 메시지·응답·DB에 노출하지 않는다.
 * 설정 객체가 실수로 로그에 찍혀도 키가 새지 않도록 {@link #toString()}에서 마스킹한다.
 */
@ConfigurationProperties(prefix = "fabwatch.ai")
public record AiProperties(
        /** claude(기본) | mock — 키가 없다고 자동으로 mock이 되지 않는다. mock은 명시 설정했을 때만. */
        @DefaultValue("claude") String provider,
        String apiKey,
        @DefaultValue("claude-sonnet-5-5") String model,
        /** 출력 토큰 상한. adaptive thinking 토큰도 여기에 포함되므로 한글 1,200자 본문 + 사고 여유를 위해 4096 (H-1) */
        @DefaultValue("4096") int maxTokens,
        /** 하루(KST) 전체 호출 시도 수 한도 — 모든 사용자 합산 */
        @DefaultValue("20") int dailyQuota,
        /**
         * 사용자 1명의 하루(KST) 호출 시도 수 한도(AI_USER_DAILY_QUOTA, 기본 5). 공개 데모 계정 한 명이 전체 한도를 독점하는 것을 막는다.
         * 전체 한도({@link #dailyQuota()})와 별개로 둘 다 적용된다. 1 이상이어야 하며 0 이하는 기동 실패 —
         * "무제한"은 지원하지 않는다(개인 한도를 사실상 끄려면 전체 한도 이상의 값을 준다).
         */
        @DefaultValue("5") int userDailyQuota,
        /** 응답 읽기 타임아웃(초). 비스트리밍 + 4096 토큰 출력이라 60초. connect 타임아웃은 10초 고정 */
        @DefaultValue("60") int timeoutSeconds,
        @DefaultValue("https://api.anthropic.com") String baseUrl,
        @DefaultValue("2023-06-01") String anthropicVersion,
        /** 5xx/타임아웃 재시도 전 대기(ms). 테스트에서는 0 */
        @DefaultValue("500") long retryBackoffMillis,
        /** 비동기 생성 워커 스레드 수 (전용 풀) */
        @DefaultValue("2") int workerThreads,
        /** 워커 대기열 크기 — 가득 차면 생성 요청이 FAILED 처리된다 */
        @DefaultValue("10") int queueCapacity,
        /** GENERATING에 이 시간(분) 넘게 갇힌 건은 FAILED로 복구 */
        @DefaultValue("5") int stuckMinutes,
        /** 사고(thinking) 강도 output_config.effort — low | medium | high. 단일 응답 리포트는 low로 사고 토큰을 최소화 */
        @DefaultValue("low") String effort) {

    public static final int DEFAULT_USER_DAILY_QUOTA = 5;
    public static final String PROVIDER_CLAUDE = "claude";
    public static final String PROVIDER_MOCK = "mock";
    public static final List<String> ALLOWED_EFFORTS = List.of("low", "medium", "high");

    /**
     * 설정값 정규화 — API 키는 앞뒤 공백/개행을 제거하고, 내부에 개행·제어문자가 남아 있으면 '유효하지 않음'으로 보고 null로 둔다
     * (HTTP 헤더에 넣으면 JDK가 키 원문이 담긴 예외를 던지므로 호출 자체를 막는다 — M-1).
     * effort는 low/medium/high만 허용하고 잘못된 값이면 기동 시점에 실패시킨다(요청 400을 런타임에 맞는 것보다 낫다).
     */
    @ConstructorBinding
    public AiProperties {
        if (userDailyQuota <= 0) {
            throw new IllegalArgumentException("fabwatch.ai.user-daily-quota(AI_USER_DAILY_QUOTA)는 1 이상이어야 합니다");
        }
        apiKey = normalizeApiKey(apiKey);
        effort = normalizeEffort(effort);
    }

    /** 개인 한도 도입 이전 시그니처(13개 인자) — 개인 한도는 기본 {@value #DEFAULT_USER_DAILY_QUOTA}. 테스트·기존 호출부 호환용 */
    public AiProperties(String provider, String apiKey, String model, int maxTokens, int dailyQuota, int timeoutSeconds,
                        String baseUrl, String anthropicVersion, long retryBackoffMillis, int workerThreads,
                        int queueCapacity, int stuckMinutes, String effort) {
        this(provider, apiKey, model, maxTokens, dailyQuota, DEFAULT_USER_DAILY_QUOTA, timeoutSeconds, baseUrl,
                anthropicVersion, retryBackoffMillis, workerThreads, queueCapacity, stuckMinutes, effort);
    }

    static String normalizeApiKey(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                return null;
            }
        }
        return trimmed;
    }

    static String normalizeEffort(String raw) {
        if (raw == null || raw.isBlank()) {
            return "low";
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED_EFFORTS.contains(value)) {
            throw new IllegalArgumentException("fabwatch.ai.effort는 low/medium/high 중 하나여야 합니다");
        }
        return value;
    }

    public boolean isMock() {
        return PROVIDER_MOCK.equals(normalizedProvider());
    }

    public boolean isClaude() {
        return PROVIDER_CLAUDE.equals(normalizedProvider());
    }

    /** 정규화를 통과한(비어 있지 않고 제어문자 없는) 키가 있을 때만 true */
    public boolean hasApiKey() {
        return apiKey != null;
    }

    public String normalizedProvider() {
        return provider == null ? PROVIDER_CLAUDE : provider.trim().toLowerCase(Locale.ROOT);
    }

    /** 키는 존재 여부만 표시한다. */
    @Override
    public String toString() {
        return "AiProperties[provider=" + provider
                + ", apiKey=" + (hasApiKey() ? "****" : "(unset)")
                + ", model=" + model
                + ", maxTokens=" + maxTokens
                + ", dailyQuota=" + dailyQuota
                + ", userDailyQuota=" + userDailyQuota
                + ", timeoutSeconds=" + timeoutSeconds
                + ", baseUrl=" + baseUrl
                + ", anthropicVersion=" + anthropicVersion
                + ", retryBackoffMillis=" + retryBackoffMillis
                + ", workerThreads=" + workerThreads
                + ", queueCapacity=" + queueCapacity
                + ", stuckMinutes=" + stuckMinutes
                + ", effort=" + effort + "]";
    }
}
