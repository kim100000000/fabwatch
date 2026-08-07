package com.fabwatch.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 비밀번호 정책 — 8자 이상 + 영문/숫자 포함 (docs/03 F-1) */
class PasswordPolicyTest {

    @Test
    @DisplayName("8자 이상 + 영문·숫자 포함이면 통과")
    void 유효한_비밀번호() {
        assertThat(PasswordPolicy.isValid("fabwatch123")).isTrue();
    }

    @Test
    @DisplayName("8자 미만 / 숫자 없음 / 영문 없음 / null은 거부")
    void 유효하지_않은_비밀번호() {
        assertThat(PasswordPolicy.isValid("fab12")).isFalse();
        assertThat(PasswordPolicy.isValid("fabwatchpass")).isFalse();
        assertThat(PasswordPolicy.isValid("12345678")).isFalse();
        assertThat(PasswordPolicy.isValid(null)).isFalse();
    }
}
