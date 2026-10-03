package com.fabwatch.equipment.dto;

import com.fabwatch.equipment.service.KpiPeriod;
import com.fabwatch.equipment.service.KpiTotals;

import java.time.Instant;

/**
 * GET /equipments/{id}/kpi (docs/06 §2). 시각은 UTC ISO-8601.
 * mtbfHours/mttrMin/availability는 계산 불가 시 null (프론트가 "고장 없음"/"-" 표시). availability는 0~1 비율.
 */
public record EquipmentKpiResponse(
        Long equipmentId,
        KpiPeriod period,
        Instant periodStart,
        Instant periodEnd,
        Double mtbfHours,
        Double mttrMin,
        Double availability,
        int downCount) {

    public static EquipmentKpiResponse of(Long equipmentId, KpiPeriod period, Instant start, Instant end,
                                          KpiTotals totals) {
        return new EquipmentKpiResponse(equipmentId, period, start, end,
                KpiRound.hours(totals.mtbfHours()), KpiRound.minutes(totals.mttrMin()),
                KpiRound.ratio(totals.availability()), totals.downCount());
    }
}
