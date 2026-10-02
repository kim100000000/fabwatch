package com.fabwatch.inspection.dto;

import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.inspection.service.PmScheduleCalculator;

import java.time.Instant;

/** PM 스케줄 응답 — overdue/overdueDays는 조회 시각 기준으로 계산한 파생 필드 */
public record PmScheduleResponse(
        Long id,
        Long equipmentId,
        String equipmentCode,
        String equipmentName,
        String cycleType,
        Integer cycleValue,
        Instant lastDoneAt,
        Instant nextDueAt,
        boolean overdue,
        int overdueDays) {

    public static PmScheduleResponse from(PmSchedule s, String equipmentCode, String equipmentName, Instant now) {
        return new PmScheduleResponse(
                s.getId(), s.getEquipmentId(), equipmentCode, equipmentName,
                s.getCycleType().name(), s.getCycleValue(), s.getLastDoneAt(), s.getNextDueAt(),
                PmScheduleCalculator.isOverdue(s.getNextDueAt(), now),
                PmScheduleCalculator.overdueDays(s.getNextDueAt(), now));
    }
}
