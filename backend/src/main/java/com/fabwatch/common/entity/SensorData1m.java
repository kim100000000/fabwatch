package com.fabwatch.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 센서 1분 집계 (docs/05 sensor_data_1m) — 영구 보관, 스케줄러가 1분마다 UPSERT.
 * TODO: 다음 라운드에 com.fabwatch.sensor.entity 패키지로 이동 예정.
 */
@Entity
@Table(name = "sensor_data_1m", uniqueConstraints = @UniqueConstraint(name = "uk_sensor_data_1m_sensor_bucket",
        columnNames = {"sensor_id", "bucket_at"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SensorData1m extends BaseEntity {

    @Column(name = "sensor_id", nullable = false)
    private Long sensorId;

    /** 분 단위 절삭 시각 */
    @Column(name = "bucket_at", nullable = false)
    private Instant bucketAt;

    @Column(name = "min_v", precision = 10, scale = 2)
    private BigDecimal minV;

    @Column(name = "max_v", precision = 10, scale = 2)
    private BigDecimal maxV;

    @Column(name = "avg_v", precision = 10, scale = 2)
    private BigDecimal avgV;

    @Column(name = "sample_count")
    private Integer sampleCount;

    public SensorData1m(Long sensorId, Instant bucketAt, BigDecimal minV, BigDecimal maxV,
                        BigDecimal avgV, Integer sampleCount) {
        this.sensorId = sensorId;
        this.bucketAt = bucketAt;
        this.minV = minV;
        this.maxV = maxV;
        this.avgV = avgV;
        this.sampleCount = sampleCount;
    }
}
