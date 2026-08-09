package com.fabwatch.sensor.dto;

import com.fabwatch.common.event.SensorLevel;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * GET /equipments/{id}/sensor-data/latest 응답 항목 (docs/06 §3 — 카드용 센서별 최신값 1건).
 * 카드가 임계치 기준선/색상을 그릴 수 있도록 임계치 4값도 함께 내려준다.
 * 아직 값이 한 건도 없으면 value/measuredAt은 null, level은 NORMAL.
 */
public record SensorLatestResponse(
        Long sensorId,
        Long equipmentId,
        String sensorType,
        String unit,
        BigDecimal value,
        Instant measuredAt,
        SensorLevel level,
        BigDecimal warnLow,
        BigDecimal warnHigh,
        BigDecimal critLow,
        BigDecimal critHigh) {
}
