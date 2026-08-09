package com.fabwatch.sensor.dto;

import com.fabwatch.sensor.entity.Sensor;

import java.math.BigDecimal;

/**
 * 센서 정의 응답 (임계치 수정 결과 / 설비 센서 목록).
 */
public record SensorResponse(
        Long sensorId,
        Long equipmentId,
        String sensorType,
        String unit,
        BigDecimal baseValue,
        BigDecimal noiseSigma,
        BigDecimal warnLow,
        BigDecimal warnHigh,
        BigDecimal critLow,
        BigDecimal critHigh) {

    public static SensorResponse from(Sensor sensor) {
        return new SensorResponse(
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
