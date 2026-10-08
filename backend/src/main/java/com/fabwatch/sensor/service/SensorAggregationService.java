package com.fabwatch.sensor.service;

import com.fabwatch.sensor.config.SensorRetentionProperties;
import com.fabwatch.sensor.entity.SensorData1m;
import com.fabwatch.sensor.repository.SensorData1mRepository;
import com.fabwatch.sensor.repository.SensorDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 1분 집계 + 원본 보존 배치 (docs/05 §3, docs/13 §5).
 *
 * 시각을 인자로 받는 이유: 스케줄러가 호출하지만 테스트는 고정 시각으로 직접 호출해 검증한다.
 */
@Slf4j
@Service
public class SensorAggregationService {

    /** 원본 보존 기간 (docs/05 §3) */
    public static final Duration RAW_RETENTION = Duration.ofDays(7);

    private final SensorDataRepository sensorDataRepository;
    private final SensorData1mRepository sensorData1mRepository;
    private final SensorRetentionProperties retentionProperties;
    /** 청크 삭제마다 새 트랜잭션을 여는 용도 — 배치 전체를 하나의 트랜잭션으로 묶지 않는다 */
    private final TransactionTemplate chunkTransaction;

    public SensorAggregationService(SensorDataRepository sensorDataRepository,
                                    SensorData1mRepository sensorData1mRepository,
                                    SensorRetentionProperties retentionProperties,
                                    PlatformTransactionManager transactionManager) {
        this.sensorDataRepository = sensorDataRepository;
        this.sensorData1mRepository = sensorData1mRepository;
        this.retentionProperties = retentionProperties;
        this.chunkTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * bucketAt(분 단위 절삭)부터 1분 구간을 센서별로 집계해 UPSERT 한다.
     * UNIQUE(sensor_id, bucket_at)이므로 이미 있으면 갱신한다 — 재실행해도 중복 행이 생기지 않는다.
     *
     * @return UPSERT된 센서 수
     */
    @Transactional
    public int aggregateMinute(Instant bucketAt) {
        Instant from = bucketAt.truncatedTo(ChronoUnit.MINUTES);
        Instant to = from.plus(1, ChronoUnit.MINUTES);

        List<Object[]> rows = sensorDataRepository.aggregateBySensor(from, to);
        for (Object[] row : rows) {
            Long sensorId = ((Number) row[0]).longValue();
            BigDecimal minV = scale(row[1]);
            BigDecimal maxV = scale(row[2]);
            BigDecimal avgV = scale(row[3]);
            int count = ((Number) row[4]).intValue();

            sensorData1mRepository.findBySensorIdAndBucketAt(sensorId, from)
                    .ifPresentOrElse(
                            existing -> existing.update(minV, maxV, avgV, count),
                            () -> sensorData1mRepository.save(
                                    new SensorData1m(sensorId, from, minV, maxV, avgV, count)));
        }
        if (!rows.isEmpty()) {
            log.debug("1분 집계 완료: bucket={}, 센서 {}개", from, rows.size());
        }
        return rows.size();
    }

    /**
     * 원본 7일 초과분 삭제 (docs/05 §3 — 일 1회 새벽 배치, docs/15 §7 청크 삭제).
     * 물리 삭제가 보존 정책 그 자체다. 1분 집계본이 영구 보관되므로 이력은 사라지지 않는다.
     *
     * 운영 규모(하루 약 78만 행 삭제)를 한 트랜잭션·한 문장으로 지우면 수집 INSERT가 락 대기에 걸리므로,
     * {@code chunk-size}행씩 별도 트랜잭션으로 반복하고 청크 사이에 짧게 쉰다. 마지막 청크가 chunk-size보다 작거나(0 포함)
     * 비면 종료한다 — 정확히 배수일 때는 빈 청크 1회를 더 돌고 끝난다. 인터럽트(종료 신호)를 받으면 즉시 멈춘다.
     * 이 메서드 자체는 트랜잭션이 아니다(청크 단위로 커밋). 중간에 실패해도 이미 지운 청크는 유지되고 다음 날 이어서 지운다.
     *
     * @return 삭제한 총 행 수
     */
    public int purgeRawBefore(Instant now) {
        Instant threshold = now.minus(RAW_RETENTION);
        int chunkSize = retentionProperties.chunkSize();
        long pauseMs = retentionProperties.chunkPauseMs();
        long startedNanos = System.nanoTime();
        int total = 0;
        int chunks = 0;
        try {
            while (true) {
                Integer deleted = chunkTransaction.execute(
                        status -> sensorDataRepository.deleteChunkByMeasuredAtBefore(threshold, chunkSize));
                int count = deleted == null ? 0 : deleted;
                total += count;
                chunks++;
                if (count < chunkSize) {
                    break;
                }
                if (pauseMs > 0) {
                    Thread.sleep(pauseMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("센서 원본 보존 배치 중단(인터럽트): {} 이전 {}건 삭제 후 종료", threshold, total);
        }
        log.info("센서 원본 보존 배치: {} 이전 {}건 삭제 (청크 {}회, chunkSize={}, {}ms)",
                threshold, total, chunks, chunkSize, (System.nanoTime() - startedNanos) / 1_000_000);
        return total;
    }

    private static BigDecimal scale(Object value) {
        if (value == null) {
            return null;
        }
        BigDecimal decimal = value instanceof BigDecimal bd ? bd : BigDecimal.valueOf(((Number) value).doubleValue());
        return decimal.setScale(2, RoundingMode.HALF_UP);
    }
}
