package com.fabwatch.inspection.service;

import java.time.Instant;
import java.util.List;

/**
 * 점검 이력의 도메인 외부 노출용 값 객체 (aireport 컨텍스트 수집용).
 * enum은 String으로 노출한다 — Inspection.Type 등의 import를 강요하지 않기 위함.
 */
public record InspectionSummary(
        Long id,
        Long equipmentId,
        /** PM / BM */
        String type,
        /** D / N */
        String shift,
        Long workerId,
        Instant startedAt,
        Integer durationMin,
        String content,
        String actionTaken,
        /** MAN / MACHINE / MATERIAL / METHOD (BM만) */
        String cause4m,
        String causeDetail,
        boolean hasNg,
        /** BM 연계 알람 (nullable) */
        Long alarmId,
        /** 체크리스트 NG 항목 — "항목명 — 메모" 형태. NG가 없으면 빈 리스트 */
        List<String> ngItems) {
}
