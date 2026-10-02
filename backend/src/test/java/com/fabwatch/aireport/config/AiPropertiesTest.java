package com.fabwatch.aireport.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 설정 기본값/검증 — H-1(max_tokens 4096·timeout 60·effort low), M-1(API 키 정규화).
 */
class AiPropertiesTest {

    private static AiProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("fabwatch.ai", AiProperties.class).orElseGet(() -> null);
    }

    private static AiProperties withKeyAndEffort(String key, String effort) {
        return new AiProperties("claude", key, "m", 4096, 20, 60, "u", "v", 0, 2, 10, 5, effort);
    }

    @Test
    @DisplayName("H-1: 아무것도 설정하지 않으면 maxTokens=4096, timeoutSeconds=60, effort=low")
    void defaults() {
        AiProperties props = bind(Map.of("fabwatch.ai.provider", "claude"));

        assertThat(props.maxTokens()).isEqualTo(4096);
        assertThat(props.timeoutSeconds()).isEqualTo(60);
        assertThat(props.effort()).isEqualTo("low");
        assertThat(props.stuckMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("H-1: 설정값으로 덮어쓸 수 있다 (timeout-seconds / effort / max-tokens)")
    void overridable() {
        AiProperties props = bind(Map.of("fabwatch.ai.timeout-seconds", "90", "fabwatch.ai.effort", "Medium",
                "fabwatch.ai.max-tokens", "6000"));

        assertThat(props.timeoutSeconds()).isEqualTo(90);
        assertThat(props.effort()).isEqualTo("medium"); // 대소문자·공백 무시
        assertThat(props.maxTokens()).isEqualTo(6000);
    }

    @Test
    @DisplayName("H-1: effort는 low/medium/high만 허용 — 그 외는 기동 시 예외, 빈 값은 low")
    void effortValidation() {
        for (String ok : new String[]{"low", "medium", "high", " HIGH "}) {
            assertThat(withKeyAndEffort("k", ok).effort()).isIn("low", "medium", "high");
        }
        assertThat(withKeyAndEffort("k", "").effort()).isEqualTo("low");
        assertThat(withKeyAndEffort("k", null).effort()).isEqualTo("low");
        for (String bad : new String[]{"max", "xhigh", "none", "0"}) {
            assertThatThrownBy(() -> withKeyAndEffort("k", bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("low/medium/high");
        }
        assertThatThrownBy(() -> bind(Map.of("fabwatch.ai.effort", "turbo")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("M-1: API 키는 trim되고, 내부 개행/공백/제어문자가 있으면 '미설정'으로 취급된다")
    void apiKeyNormalization() {
        assertThat(withKeyAndEffort("  sk-ant-abc\n", "low").apiKey()).isEqualTo("sk-ant-abc");
        assertThat(withKeyAndEffort("  sk-ant-abc\n", "low").hasApiKey()).isTrue();

        for (String bad : new String[]{"sk-ant-a\nb", "sk-ant-a\r\nb", "sk-ant-a b", "sk-ant-a\u0000b", "sk-ant-a\tb"}) {
            AiProperties p = withKeyAndEffort(bad, "low");
            assertThat(p.hasApiKey()).as(bad).isFalse();
            assertThat(p.apiKey()).isNull();
            assertThat(p.toString()).doesNotContain("sk-ant");
        }
        for (String blank : new String[]{null, "", "   ", "\n"}) {
            assertThat(withKeyAndEffort(blank, "low").hasApiKey()).isFalse();
        }
    }
}
