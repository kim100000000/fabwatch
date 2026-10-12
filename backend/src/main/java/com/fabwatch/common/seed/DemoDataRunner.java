package com.fabwatch.common.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 기동 시 옵트인 데모 데이터 로드 (DEMO_DATA_ENABLED=true). 두 인스턴스가 동시에 기동해 UNIQUE 충돌이 나면
 * 다른 쪽이 먼저 만든 것이므로 오류로 보지 않고 넘어간다(멱등). 설정 오류(IllegalStateException)는 그대로 던져 기동을 실패시킨다.
 */
@Slf4j
@Component
@Profile("!local")
@ConditionalOnProperty(name = "fabwatch.demo.enabled", havingValue = "true")
// ADMIN 부트스트랩(users가 비었을 때만 동작)이 데모 계정보다 먼저 실행돼야 한다 — AdminBootstrapRunner.ORDER 참고
@Order(100)
@RequiredArgsConstructor
public class DemoDataRunner implements CommandLineRunner {

    private final DemoDataService demoDataService;

    @Override
    public void run(String... args) {
        try {
            demoDataService.load();
        } catch (DataIntegrityViolationException e) {
            log.info("데모 데이터 로드 건너뜀 — UNIQUE 충돌(동시에 기동한 다른 인스턴스가 이미 생성했거나, 삭제 처리된 데모 설비 코드와 겹침). 변경 사항은 롤백됨.");
        }
    }
}
