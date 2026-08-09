package com.fabwatch.sensor.service;

import com.fabwatch.sensor.entity.Sensor;

import java.math.BigDecimal;

/**
 * 센서 정의(기준값 + 임계치)의 도메인 외부 노출용 값 객체.
 * 다른 도메인(simulator 등)은 Sensor 엔티티 대신 이 record만 본다 (도메인 간 직접 참조 금지).
 * type을 enum이 아니라 String으로 노출하는 것도 같은 이유다 — Sensor.Type import를 강요하지 않기 위함.
 */
public record SensorSpec(
        Long sensorId,
        Long equipmentId,
        /** TEMP / VIBRATION / PRESSURE / CURRENT */
        String type,
        String unit,
        BigDecimal baseValue,
        BigDecimal noiseSigma,
        BigDecimal warnLow,
        BigDecimal warnHigh,
        BigDecimal critLow,
        BigDecimal critHigh) {

    public static SensorSpec from(Sensor sensor) {
        return new SensorSpec(
                sensor.getId(),
                sensor.getEquipmentId(),
                sensor.getType().name(),
                sensor.getUnit(),
                sensor.getBaseValue(),
                sensor.getNoiseSigma(),
                sensor.getWarnLow(),
                sensor.getWarnHigh(),
                sensor.getCritLow(),
                sensor.getCritHigh());
    }
}
