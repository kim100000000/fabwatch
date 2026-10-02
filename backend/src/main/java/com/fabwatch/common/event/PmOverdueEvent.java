package com.fabwatch.common.event;

import java.time.Instant;

/**
 * PM 지연(예정일 + 3일 초과) 알림 이벤트 (docs/03 F-3.3).
 *
 * inspection 도메인(스케줄러)이 발행하고 alarm 도메인이 구독해 MAJOR "PM 지연" 알람(PM_OVERDUE)을 만든다.
 * inspection은 Alarm 엔티티/레포지토리를 직접 만지지 않는다 (CLAUDE.md "도메인 간 직접 참조 금지").
 * 같은 스케줄에 대해 1회만 발행된다 (pm_schedules.overdue_alarm_sent).
 */
public record PmOverdueEvent(
        Long scheduleId,
        Long equipmentId,
        Instant nextDueAt,
        int overdueDays,
        Instant detectedAt) {
}
