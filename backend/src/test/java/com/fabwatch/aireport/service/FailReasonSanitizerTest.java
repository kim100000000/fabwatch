package com.fabwatch.aireport.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailReasonSanitizerTest {

    private static final String KEY = "sk-ant-api03-SECRETKEY_1234567890-abcdef";

    @Test
    @DisplayName("설정된 API 키 문자열과 sk-ant- 패턴은 마스킹된다")
    void masksKeys() {
        String out = FailReasonSanitizer.sanitize("실패: key=" + KEY + " 그리고 sk-ant-other-9999", KEY);

        assertThat(out).doesNotContain(KEY).doesNotContain("SECRETKEY").doesNotContain("sk-ant-other");
        assertThat(out).contains("***");
    }

    @Test
    @DisplayName("x-api-key 헤더 덤프는 마스킹된다")
    void masksHeaderDump() {
        assertThat(FailReasonSanitizer.sanitize("req x-api-key: abcdef123", null)).doesNotContain("abcdef123");
    }

    @Test
    @DisplayName("300자로 자르고, 비어 있으면 기본 문구")
    void truncatesAndDefaults() {
        assertThat(FailReasonSanitizer.sanitize("가".repeat(1_000), null)).hasSize(300);
        assertThat(FailReasonSanitizer.sanitize(" ", null)).isNotBlank();
        assertThat(FailReasonSanitizer.sanitize(null, null)).isNotBlank();
    }
}
