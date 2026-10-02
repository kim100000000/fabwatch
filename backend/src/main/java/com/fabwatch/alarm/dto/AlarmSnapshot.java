package com.fabwatch.alarm.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 알람 조회 결과의 도메인 외부 노출용 값 객체 (aireport 등 다른 도메인이 Alarm 엔티티 대신 이것만 본다).
 * enum은 String으로 노출해 Alarm.Severity 등의 import를 강요하지 않는다.
 */
public record AlarmSnapshot(
        Long id,
        Long equipmentId,
        /** NULL = 시스템 알람(PM 지연·수동 보고) */
        Long sensorId,
        /** SENSOR_THRESHOLD / PM_OVERDUE / MANUAL */
        String alarmType,
        /** WARNING / MAJOR / CRITICAL */
        String severity,
        /** OPEN / ACK / RESOLVED */
        String status,
        BigDecimal triggerValue,
        BigDecimal thresholdValue,
        String message,
        Instant occurredAt,
        String resolveNote) {
}
