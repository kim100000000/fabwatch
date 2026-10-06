package com.fabwatch.config;

import com.fabwatch.aireport.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml이 AiProperties의 @DefaultValue를 덮어쓰지 않는지 검증한다.
 * (QA f2f3 X-1: yml에 max-tokens 2000 / timeout 30이 남아 있어 코드 기본값 4096/60이 무효였고,
 * 기본값만 보는 AiPropertiesTest로는 잡히지 않았다 — 실제 바인딩 결과를 확인해야 한다.)
 * 환경변수 AI_MAX_TOKENS 등이 설정된 환경에서는 그 값이 우선하므로 이 테스트는 기본 환경 기준이다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AiPropertiesYamlBindingTest {

    @Autowired
    private AiProperties properties;

    @Test
    @DisplayName("yml 바인딩 결과의 max-tokens는 4096, 읽기 타임아웃은 60초, effort는 low다 (docs/13 기본값과 일치)")
    void effectiveDefaultsMatchDocs() {
        assertThat(properties.maxTokens()).isEqualTo(4096);
        assertThat(properties.timeoutSeconds()).isEqualTo(60);
        assertThat(properties.effort()).isEqualTo("low");
    }
}
