package com.fabwatch.sensor.entity;

import com.fabwatch.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 센서 원본 데이터 (docs/05 sensor_data) — 7일 보관 후 배치 삭제. soft delete 대상 아님.
 */
@Entity
@Table(name = "sensor_data",
        indexes = @Index(name = "idx_sensor_data_sensor_measured", columnList = "sensor_id, measured_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SensorData extends BaseEntity {

    @Column(name = "sensor_id", nullable = false)
    private Long sensorId;

    @Column(name = "value", precision = 10, scale = 2, nullable = false)
    private BigDecimal value;

    /** DATETIME(3) — 2초 주기 생성이라 밀리초까지 보관 */
    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    public SensorData(Long sensorId, BigDecimal value, Instant measuredAt) {
        this.sensorId = sensorId;
        this.value = value;
        this.measuredAt = measuredAt;
    }
}
