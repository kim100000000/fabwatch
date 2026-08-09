package com.fabwatch.sensor.dto;

import com.fabwatch.sensor.entity.SensorThresholdLog;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 임계치 변경 이력 응답 (docs/03 F-2 "누가 언제 왜").
 * docs/06에 없는 추가 엔드포인트의 응답이다 — 이력을 남기기만 하고 볼 수 없으면 의미가 없어 함께 만들었다.
 */
public record ThresholdLogResponse(
        Long id,
        Long sensorId,
        Long equipmentId,
        BigDecimal oldWarnLow,
        BigDecimal oldWarnHigh,
        BigDecimal oldCritLow,
        BigDecimal oldCritHigh,
        BigDecimal newWarnLow,
        BigDecimal newWarnHigh,
        BigDecimal newCritLow,
        BigDecimal newCritHigh,
        String reason,
        Long changedBy,
        String changedByName,
        Instant changedAt) {

    public static ThresholdLogResponse from(SensorThresholdLog log, String changedByName) {
        return new ThresholdLogResponse(
                log.getId(),
                log.getSensorId(),
                log.getEquipmentId(),
                log.getOldWarnLow(), log.getOldWarnHigh(), log.getOldCritLow(), log.getOldCritHigh(),
                log.getNewWarnLow(), log.getNewWarnHigh(), log.getNewCritLow(), log.getNewCritHigh(),
                log.getReason(),
                log.getChangedBy(),
                changedByName,
                log.getChangedAt());
    }
}
