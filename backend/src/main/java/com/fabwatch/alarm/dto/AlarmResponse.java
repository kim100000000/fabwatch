package com.fabwatch.alarm.dto;

import com.fabwatch.alarm.entity.Alarm;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 알람 응답 (docs/06 §6).
 * equipmentCode / ackByName / resolvedByName은 다른 도메인의 조회 인터페이스로 채운 표시용 필드다.
 */
public record AlarmResponse(
        Long id,
        Long equipmentId,
        String equipmentCode,
        Long sensorId,
        String alarmType,
        String severity,
        String status,
        BigDecimal triggerValue,
        BigDecimal thresholdValue,
        String message,
        Instant occurredAt,
        Long ackBy,
        String ackByName,
        Instant ackAt,
        Long resolvedBy,
        String resolvedByName,
        Instant resolvedAt,
        String resolveNote) {

    public static AlarmResponse from(Alarm alarm, String equipmentCode, String ackByName, String resolvedByName) {
        return new AlarmResponse(
                alarm.getId(),
                alarm.getEquipmentId(),
                equipmentCode,
                alarm.getSensorId(),
                alarm.getAlarmType().name(),
                alarm.getSeverity().name(),
                alarm.getStatus().name(),
                alarm.getTriggerValue(),
                alarm.getThresholdValue(),
                alarm.getMessage(),
                alarm.getOccurredAt(),
                alarm.getAckBy(),
                ackByName,
                alarm.getAckAt(),
                alarm.getResolvedBy(),
                resolvedByName,
                alarm.getResolvedAt(),
                alarm.getResolveNote());
    }
}
