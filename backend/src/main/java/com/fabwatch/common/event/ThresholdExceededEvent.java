package com.fabwatch.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 임계치 초과 이벤트 — sensor 도메인이 발행하고 alarm 도메인이 구독한다 (docs/03 F-4.3).
 *
 * sensor가 alarm의 서비스/엔티티를 직접 호출하지 않기 위한 경계면이다 (CLAUDE.md "도메인 간 직접 참조 금지").
 * 이벤트 payload 자체는 어느 도메인에도 속하지 않으므로 common/event에 둔다.
 *
 * ※ 2초 주기로 계속 발행된다. 알람 폭주 방지는 alarm 도메인의 중복 억제 로직 책임 (docs/03 F-4.3).
 */
public record ThresholdExceededEvent(
        Long equipmentId,
        Long sensorId,
        String sensorType,
        String unit,
        BigDecimal value,
        /** 초과한 기준값 (warnHigh/critLow 등 실제로 걸린 임계치) */
        BigDecimal thresholdValue,
        SensorLevel level,
        Instant occurredAt) {
}
