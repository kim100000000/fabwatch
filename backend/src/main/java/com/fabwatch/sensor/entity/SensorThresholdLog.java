package com.fabwatch.sensor.entity;

import com.fabwatch.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 임계치 변경 이력 (docs/03 F-2 "변경 시 이력 남김 — 누가 언제 왜").
 *
 * ※ docs/05 테이블 목록에 없던 테이블이다. 기능명세가 이력 보관을 요구하는데 저장할 자리가 없어
 *   추가했다 (equipment_status_logs와 같은 성격의 이력 테이블). 문서 반영 필요.
 */
@Entity
@Table(name = "sensor_threshold_logs",
        indexes = @Index(name = "idx_sensor_threshold_log_sensor", columnList = "sensor_id, changed_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SensorThresholdLog extends BaseEntity {

    @Column(name = "sensor_id", nullable = false)
    private Long sensorId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "old_warn_low", precision = 10, scale = 2)
    private BigDecimal oldWarnLow;

    @Column(name = "old_warn_high", precision = 10, scale = 2)
    private BigDecimal oldWarnHigh;

    @Column(name = "old_crit_low", precision = 10, scale = 2)
    private BigDecimal oldCritLow;

    @Column(name = "old_crit_high", precision = 10, scale = 2)
    private BigDecimal oldCritHigh;

    @Column(name = "new_warn_low", precision = 10, scale = 2)
    private BigDecimal newWarnLow;

    @Column(name = "new_warn_high", precision = 10, scale = 2)
    private BigDecimal newWarnHigh;

    @Column(name = "new_crit_low", precision = 10, scale = 2)
    private BigDecimal newCritLow;

    @Column(name = "new_crit_high", precision = 10, scale = 2)
    private BigDecimal newCritHigh;

    /** 왜 바꿨는가 — 현장에서 임계치 임의 변경은 사고의 씨앗이라 필수 입력 */
    @Column(name = "reason", length = 300, nullable = false)
    private String reason;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Builder
    private SensorThresholdLog(Long sensorId, Long equipmentId,
                               BigDecimal oldWarnLow, BigDecimal oldWarnHigh,
                               BigDecimal oldCritLow, BigDecimal oldCritHigh,
                               BigDecimal newWarnLow, BigDecimal newWarnHigh,
                               BigDecimal newCritLow, BigDecimal newCritHigh,
                               String reason, Long changedBy, Instant changedAt) {
        this.sensorId = sensorId;
        this.equipmentId = equipmentId;
        this.oldWarnLow = oldWarnLow;
        this.oldWarnHigh = oldWarnHigh;
        this.oldCritLow = oldCritLow;
        this.oldCritHigh = oldCritHigh;
        this.newWarnLow = newWarnLow;
        this.newWarnHigh = newWarnHigh;
        this.newCritLow = newCritLow;
        this.newCritHigh = newCritHigh;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }
}
