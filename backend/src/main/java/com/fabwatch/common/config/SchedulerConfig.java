package com.fabwatch.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 활성화 (docs/13 §5 스케줄러 주기표).
 *
 * | 작업 | 주기 |
 * | 센서 데이터 생성(SensorDataSource pull) | 2초 |
 * | 1분 집계 UPSERT(sensor_data_1m)         | 매분 :05 |
 * | 원본 sensor_data 7일 초과 삭제 배치      | 매일 03:30 UTC |
 * | SSE heartbeat                            | 30초 |
 *
 * 테스트에서는 `fabwatch.scheduler.enabled=false`로 꺼서 시간 기반 로직이 테스트를 흔들지 않게 한다
 * (스케줄러가 하는 일은 서비스 메서드를 고정 시각으로 직접 호출해 검증한다).
 *
 * // SCALE: prod 단일 인스턴스 전제. 스케일아웃 시 리더 선출 또는 분산락 필요 (docs/13 §5).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "fabwatch.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerConfig {
}
