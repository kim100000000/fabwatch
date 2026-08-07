package com.fabwatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 컨텍스트 로딩 + 스키마 생성 검증 (H2, test 프로파일).
 * docs/05의 15개 테이블 매핑이 깨지면 여기서 먼저 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class FabWatchApplicationTests {

    @Test
    @DisplayName("스프링 컨텍스트와 JPA 스키마가 정상 생성된다")
    void contextLoads() {
    }
}
