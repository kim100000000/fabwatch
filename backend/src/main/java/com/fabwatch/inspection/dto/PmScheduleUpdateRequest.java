package com.fabwatch.inspection.dto;

import com.fabwatch.inspection.entity.PmSchedule;
import jakarta.validation.constraints.NotNull;

/** PUT /equipments/{id}/pm-schedule — WEEKLY 1~7(요일, 월=1), MONTHLY 1~28(일자), DAILY는 null */
public record PmScheduleUpdateRequest(
        @NotNull PmSchedule.CycleType cycleType,
        Integer cycleValue) {
}
