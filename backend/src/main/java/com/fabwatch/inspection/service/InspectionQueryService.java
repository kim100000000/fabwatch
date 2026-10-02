package com.fabwatch.inspection.service;

import java.util.List;
import java.util.Optional;

/**
 * inspection 도메인이 다른 도메인에 노출하는 조회 통로.
 * aireport는 InspectionRepository/Inspection 엔티티를 직접 참조하지 않고 이 인터페이스만 쓴다.
 */
public interface InspectionQueryService {

    /** 점검 이력 단건. 없거나 삭제됐으면 empty. */
    Optional<InspectionSummary> findSummary(Long inspectionId);

    /** 설비의 최근 점검 이력(PM/BM 전체, 최신순). PM NG 항목 포함. */
    List<InspectionSummary> findRecentByEquipment(Long equipmentId, int limit);

    /** 설비의 최근 BM 이력(최신순) — 재발 판단용. */
    List<InspectionSummary> findRecentBmByEquipment(Long equipmentId, int limit);
}
