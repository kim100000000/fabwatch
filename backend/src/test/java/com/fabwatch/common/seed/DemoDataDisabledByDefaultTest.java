package com.fabwatch.common.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** DEMO_DATA_ENABLED 기본값은 false — 로더 빈이 아예 만들어지지 않는다 (운영에 시연 데이터 금지). */
@SpringBootTest
@ActiveProfiles("test")
class DemoDataDisabledByDefaultTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("기본 설정에서는 데모 로더 빈이 없다")
    void 기본_비활성() {
        assertThat(context.getBeanNamesForType(DemoDataService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DemoDataRunner.class)).isEmpty();
    }
}
