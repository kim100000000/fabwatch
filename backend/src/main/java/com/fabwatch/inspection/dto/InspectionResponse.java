package com.fabwatch.inspection.dto;

import com.fabwatch.inspection.entity.Inspection;

import java.time.Instant;

/**
 * 점검 이력 목록 행 (docs/06 §4).
 * equipmentCode/equipmentName/workerName/reviewedByName은 다른 도메인 조회 인터페이스로 채운 표시용 필드다.
 */
public record InspectionResponse(
        Long id,
        Long equipmentId,
        String equipmentCode,
        String equipmentName,
        String type,
        String shift,
        Long workerId,
        String workerName,
        Instant startedAt,
        Instant endedAt,
        Integer durationMin,
        String content,
        String actionTaken,
        String cause4m,
        String causeDetail,
        Long alarmId,
        boolean hasNg,
        Long reviewedBy,
        String reviewedByName,
        Instant reviewedAt,
        Instant createdAt) {

    public static InspectionResponse from(Inspection i, String equipmentCode, String equipmentName,
                                          String workerName, String reviewedByName) {
        return new InspectionResponse(
                i.getId(), i.getEquipmentId(), equipmentCode, equipmentName,
                i.getType().name(), i.getShift().name(), i.getWorkerId(), workerName,
                i.getStartedAt(), i.getEndedAt(), i.getDurationMin(),
                i.getContent(), i.getActionTaken(),
                i.getCause4m() == null ? null : i.getCause4m().name(),
                i.getCauseDetail(), i.getAlarmId(), i.isHasNg(),
                i.getReviewedBy(), reviewedByName, i.getReviewedAt(), i.getCreatedAt());
    }
}
