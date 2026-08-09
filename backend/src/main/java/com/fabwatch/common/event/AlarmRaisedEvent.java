package com.fabwatch.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 신규 알람 발생 이벤트 — alarm 도메인이 발행, sensor 도메인의 SSE 브로드캐스터가 구독한다.
 * 이 record가 그대로 SSE `event: alarm` 의 JSON payload가 된다 (docs/06 §3).
 */
public record AlarmRaisedEvent(
        Long alarmId,
        Long equipmentId,
        String equipmentCode,
        Long sensorId,
        String sensorType,
        String alarmType,
        String severity,
        String status,
        String message,
        BigDecimal triggerValue,
        BigDecimal thresholdValue,
        Instant occurredAt) {
}
