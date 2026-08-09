package com.fabwatch.sensor.service;

import com.fabwatch.sensor.entity.SensorData1m;
import com.fabwatch.sensor.repository.SensorData1mRepository;
import com.fabwatch.sensor.repository.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@RequiredArgsConstructor
public class SensorAggregationService {

    /** 원본 보존 기간 (docs/05 §3) */
    public static final Duration RAW_RETENTION = Duration.ofDays(7);

    private final SensorDataRepository sensorDataRepository;
    private final SensorData1mRepository sensorData1mRepository;

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
     * 원본 7일 초과분 삭제 (docs/05 §3 — 일 1회 새벽 배치).
     * 물리 삭제가 보존 정책 그 자체다. 1분 집계본이 영구 보관되므로 이력은 사라지지 않는다.
     */
    @Transactional
    public int purgeRawBefore(Instant now) {
        Instant threshold = now.minus(RAW_RETENTION);
        int deleted = sensorDataRepository.deleteByMeasuredAtBefore(threshold);
        log.info("센서 원본 보존 배치: {} 이전 {}건 삭제", threshold, deleted);
        return deleted;
    }

    private static BigDecimal scale(Object value) {
        if (value == null) {
            return null;
        }
        BigDecimal decimal = value instanceof BigDecimal bd ? bd : BigDecimal.valueOf(((Number) value).doubleValue());
        return decimal.setScale(2, RoundingMode.HALF_UP);
    }
}
