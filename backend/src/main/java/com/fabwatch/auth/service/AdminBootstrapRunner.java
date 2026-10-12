package com.fabwatch.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 기동 시 최초 ADMIN 부트스트랩 실행. local 프로파일은 LocalDataSeeder가 사용자를 채우므로 제외한다.
 *
 * 두 인스턴스가 동시에 기동해 같은 이메일을 넣으려 하면 UNIQUE 위반이 나는데, 이는 "다른 쪽이 먼저 만들었다"는 뜻이라
 * 오류로 보지 않고 넘어간다(멱등). 그 외 설정 오류(IllegalStateException)는 그대로 던져 기동을 실패시킨다.
 */
@Slf4j
@Component
@Profile("!local")
@Order(AdminBootstrapRunner.ORDER)
@RequiredArgsConstructor
public class AdminBootstrapRunner implements CommandLineRunner {

    /**
     * 실행 순서: 데모 로더(DemoDataRunner, 더 큰 값)보다 먼저. 부트스트랩은 "users가 비었을 때만" 동작하므로,
     * 데모 계정이 먼저 만들어지면 ADMIN이 영영 안 생긴다.
     */
    public static final int ORDER = 10;

    private final AdminBootstrapService bootstrapService;

    @Override
    public void run(String... args) {
        try {
            bootstrapService.bootstrapIfEmpty();
        } catch (DataIntegrityViolationException e) {
            log.info("ADMIN 부트스트랩 건너뜀 — 동시에 기동한 다른 인스턴스가 이미 생성했습니다.");
        }
    }
}
