package com.fabwatch.sensor.dto;

import com.fabwatch.common.event.SensorLevel;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SSE `event: sensor` payload (docs/06 §3).
 *
 * docs/06 스펙은 {sensorId, type, value, measuredAt, level}이지만
 * equipmentId(전체 구독 시 카드 매칭용)와 unit(차트 축 라벨용)을 추가했다 — 프론트에 공유 필요.
 */
public record SensorStreamPayload(
        Long sensorId,
        Long equipmentId,
        String type,
        String unit,
        BigDecimal value,
        Instant measuredAt,
        SensorLevel level) {
}
