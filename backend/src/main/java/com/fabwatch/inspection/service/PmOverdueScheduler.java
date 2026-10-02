package com.fabwatch.inspection.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * PM 지연 점검 스케줄러 — 매시 정각 +10분 (docs/13 §5 "시간당 or 일 1회" 중 시간당).
 * 지연 기준이 "일" 단위라 분 단위 정밀도는 필요 없고, 서버 기동 직후 몰리는 다른 배치와 겹치지 않게 :10에 둔다.
 *
 * // SCALE: prod 단일 인스턴스 전제. 스케일아웃 시 중복 발행 방지를 위해 분산락 필요 (docs/13 §5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmOverdueScheduler {

    private final PmOverdueService pmOverdueService;

    @Scheduled(cron = "0 10 * * * *", zone = "UTC")
    public void checkOverdue() {
        try {
            pmOverdueService.publishOverdueEvents(Instant.now());
        } catch (Exception e) {
            // 한 번 실패해도 다음 정각에 재시도된다 (플래그는 롤백되어 있음)
            log.error("PM 지연 점검 실패", e);
        }
    }
}
