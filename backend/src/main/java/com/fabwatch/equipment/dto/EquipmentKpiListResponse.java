package com.fabwatch.equipment.dto;

import com.fabwatch.equipment.service.KpiPeriod;
import com.fabwatch.equipment.service.KpiTotals;

import java.time.Instant;
import java.util.List;

/**
 * GET /equipments/kpi (docs/06 §2) — 설비별 KPI + 합산 후 재계산한 요약.
 * summary는 비율의 평균이 아니라 Σ 원재료로 다시 계산한 값이다(예: 가동률 = ΣRUN / Σ(전체−PM)).
 */
public record EquipmentKpiListResponse(
        KpiPeriod period,
        Instant periodStart,
        Instant periodEnd,
        Summary summary,
        List<Item> equipments) {

    public record Summary(Double mtbfHours, Double mttrMin, Double availability, int downCount) {
        public static Summary of(KpiTotals totals) {
            return new Summary(KpiRound.hours(totals.mtbfHours()), KpiRound.minutes(totals.mttrMin()),
                    KpiRound.ratio(totals.availability()), totals.downCount());
        }
    }

    public record Item(Long equipmentId, String equipmentCode, String equipmentName,
                       Double mtbfHours, Double mttrMin, Double availability, int downCount) {
        public static Item of(Long equipmentId, String code, String name, KpiTotals totals) {
            return new Item(equipmentId, code, name,
                    KpiRound.hours(totals.mtbfHours()), KpiRound.minutes(totals.mttrMin()),
                    KpiRound.ratio(totals.availability()), totals.downCount());
        }
    }
}
