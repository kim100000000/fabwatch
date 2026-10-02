package com.fabwatch.inspection.dto;

import java.time.Instant;
import java.util.List;

/** 점검 이력 상세 = InspectionResponse 전 필드 + checkResults (docs/06 §4) */
public record InspectionDetailResponse(
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
        Instant createdAt,
        List<CheckResultResponse> checkResults) {

    public static InspectionDetailResponse from(InspectionResponse r, List<CheckResultResponse> checkResults) {
        return new InspectionDetailResponse(
                r.id(), r.equipmentId(), r.equipmentCode(), r.equipmentName(), r.type(), r.shift(),
                r.workerId(), r.workerName(), r.startedAt(), r.endedAt(), r.durationMin(),
                r.content(), r.actionTaken(), r.cause4m(), r.causeDetail(), r.alarmId(), r.hasNg(),
                r.reviewedBy(), r.reviewedByName(), r.reviewedAt(), r.createdAt(), checkResults);
    }
}
