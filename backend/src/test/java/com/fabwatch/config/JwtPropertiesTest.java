package com.fabwatch.config;

import com.fabwatch.common.config.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JWT_SECRET fail-fast — 미설정/짧은 키로는 기동할 수 없고, 오류 메시지에 값이 노출되지 않는다 (docs/11 §2·P-5). */
class JwtPropertiesTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef"; // 정확히 32바이트

    @Test
    @DisplayName("32바이트 이상이면 통과")
    void 정상_키() {
        JwtProperties props = new JwtProperties(SECRET_32, 30, 14, null);
        assertThat(props.secret()).isEqualTo(SECRET_32);
        assertThat(props.refreshGraceSeconds()).isEqualTo(JwtProperties.DEFAULT_REFRESH_GRACE_SECONDS);
    }

    @Test
    @DisplayName("null/빈 값/공백은 기동 실패")
    void 미설정() {
        assertThatThrownBy(() -> new JwtProperties(null, 30, 14, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties("", 30, 14, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties("   ", 30, 14, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("31바이트(256bit 미만)는 기동 실패, 메시지에 키 값이 들어가지 않는다")
    void 짧은_키() {
        String shortSecret = "short-secret-value-31-bytes-xxx"; // 31자
        assertThat(shortSecret.length()).isEqualTo(31);
        assertThatThrownBy(() -> new JwtProperties(shortSecret, 30, 14, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageNotContaining(shortSecret)
                .hasMessageNotContaining("short-secret");
    }

    @Test
    @DisplayName("멀티바이트 문자는 바이트 기준으로 센다 (한글 11자 = 33바이트 → 통과)")
    void 바이트_기준() {
        assertThat(new JwtProperties("가나다라마바사아자차카", 30, 14, null).secret()).hasSize(11);
        assertThatThrownBy(() -> new JwtProperties("가나다라마바사아자차", 30, 14, null)) // 30바이트
                .isInstanceOf(IllegalStateException.class);
    }

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropsConfig {
    }

    @Test
    @DisplayName("스프링 바인딩: 환경변수 미설정(빈 문자열)이면 컨텍스트 기동이 실패한다")
    void 바인딩_실패() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues("fabwatch.jwt.secret=", "fabwatch.jwt.access-exp-minutes=30", "fabwatch.jwt.refresh-exp-days=14")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("스프링 바인딩: 정상 키면 기동 성공")
    void 바인딩_성공() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues("fabwatch.jwt.secret=" + SECRET_32, "fabwatch.jwt.access-exp-minutes=30", "fabwatch.jwt.refresh-exp-days=14")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
