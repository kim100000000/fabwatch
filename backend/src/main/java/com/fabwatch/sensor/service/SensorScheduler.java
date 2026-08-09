package com.fabwatch.sensor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 센서 관련 스케줄러 (docs/13 §5 주기표).
 *
 * 스케줄러는 "언제 부를지"만 결정하고 로직은 전부 서비스에 있다 —
 * 테스트에서 실제 시간을 기다리지 않고 서비스 메서드를 고정 시각으로 직접 호출하기 위함.
 *
 * // SCALE: prod 단일 인스턴스 전제. 스케일아웃 시 리더 선출/분산락 필요 (docs/13 §5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensorScheduler {

    private final SensorIngestionService ingestionService;
    private final SensorAggregationService aggregationService;

    /** 센서 데이터 생성·수집 — 2초 (docs/03 F-4.2) */
    @Scheduled(fixedRate = 2000)
    public void ingest() {
        try {
            ingestionService.ingest(Instant.now());
        } catch (Exception e) {
            // 한 틱 실패로 스케줄러 자체가 죽지 않도록 삼킨다 (다음 틱에 자연 복구)
            log.error("센서 수집 틱 실패", e);
        }
    }

    /**
     * 1분 집계 UPSERT — 매분 5초에 "직전 1분" 버킷을 집계한다 (docs/05 §3).
     * 5초 지연을 두는 이유: 경계 시각에 저장 중인 데이터가 누락되지 않도록.
     */
    @Scheduled(cron = "5 * * * * *")
    public void aggregate() {
        try {
            aggregationService.aggregateMinute(Instant.now().minus(1, ChronoUnit.MINUTES));
        } catch (Exception e) {
            log.error("1분 집계 실패", e);
        }
    }

    /** 원본 7일 초과분 삭제 — 매일 새벽 03:30 UTC (docs/05 §3, docs/13 §5) */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeRaw() {
        try {
            aggregationService.purgeRawBefore(Instant.now());
        } catch (Exception e) {
            log.error("센서 원본 보존 배치 실패", e);
        }
    }
}
