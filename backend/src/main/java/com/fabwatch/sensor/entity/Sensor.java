package com.fabwatch.sensor.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;

/**
 * 센서 (docs/05 sensors).
 *
 * equipment_id는 equipment 도메인 FK지만 도메인 간 직접 참조 금지 원칙에 따라 ID만 보관한다.
 */
@Entity
@Table(name = "sensors", uniqueConstraints = @UniqueConstraint(name = "uk_sensor_equipment_type",
        columnNames = {"equipment_id", "type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE sensors SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Sensor extends SoftDeletableEntity {

    /** 센서 종류 */
    public enum Type {
        TEMP, VIBRATION, PRESSURE, CURRENT
    }

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private Type type;

    /** ℃, mm/s, kPa, A */
    @Column(name = "unit", length = 10)
    private String unit;

    /** 시뮬레이터 기준값 (전부 가상값 — docs/08 B-3) */
    @Column(name = "base_value", precision = 10, scale = 2)
    private BigDecimal baseValue;

    @Column(name = "noise_sigma", precision = 10, scale = 2)
    private BigDecimal noiseSigma;

    /** 임계치 — NULL이면 미사용 (docs/03 F-2) */
    @Column(name = "warn_low", precision = 10, scale = 2)
    private BigDecimal warnLow;

    @Column(name = "warn_high", precision = 10, scale = 2)
    private BigDecimal warnHigh;

    @Column(name = "crit_low", precision = 10, scale = 2)
    private BigDecimal critLow;

    @Column(name = "crit_high", precision = 10, scale = 2)
    private BigDecimal critHigh;

    /**
     * 임계치 변경 (docs/03 F-2, FR-2.3). 순서 검증은 ThresholdRangeValidator, 이력 기록은 서비스 책임.
     * base_value/noise_sigma(시뮬레이터 기준값)는 여기서 건드리지 않는다.
     */
    public void updateThresholds(BigDecimal warnLow, BigDecimal warnHigh, BigDecimal critLow, BigDecimal critHigh) {
        this.warnLow = warnLow;
        this.warnHigh = warnHigh;
        this.critLow = critLow;
        this.critHigh = critHigh;
    }

    @Builder
    private Sensor(Long equipmentId, Type type, String unit, BigDecimal baseValue, BigDecimal noiseSigma,
                   BigDecimal warnLow, BigDecimal warnHigh, BigDecimal critLow, BigDecimal critHigh) {
        this.equipmentId = equipmentId;
        this.type = type;
        this.unit = unit;
        this.baseValue = baseValue;
        this.noiseSigma = noiseSigma;
        this.warnLow = warnLow;
        this.warnHigh = warnHigh;
        this.critLow = critLow;
        this.critHigh = critHigh;
    }
}
