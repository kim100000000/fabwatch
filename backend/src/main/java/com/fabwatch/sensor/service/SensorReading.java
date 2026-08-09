package com.fabwatch.sensor.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SensorDataSource가 공급하는 센서 값 1건 (docs/10 ADR-8).
 * value는 DECIMAL(10,2) 저장에 맞춰 소수 2자리로 정규화되어 들어온다.
 */
public record SensorReading(Long sensorId, Long equipmentId, BigDecimal value, Instant measuredAt) {
}
