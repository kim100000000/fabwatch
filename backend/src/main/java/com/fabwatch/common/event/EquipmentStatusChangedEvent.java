package com.fabwatch.common.event;

import java.time.Instant;

/**
 * 설비 상태 변경 이벤트 — equipment 도메인이 발행, sensor 도메인의 SSE 브로드캐스터가 구독한다.
 * 이 record가 그대로 SSE `event: status` 의 JSON payload가 된다 (docs/06 §3).
 */
public record EquipmentStatusChangedEvent(
        Long equipmentId,
        String equipmentCode,
        /** 최초 등록 시에는 null */
        String fromStatus,
        String toStatus,
        String reason,
        /** 시스템(자동 DOWN)이면 null */
        Long changedBy,
        Instant changedAt) {
}
