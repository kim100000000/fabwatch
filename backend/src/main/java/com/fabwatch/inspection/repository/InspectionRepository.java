package com.fabwatch.inspection.repository;

import com.fabwatch.inspection.entity.Inspection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InspectionRepository extends JpaRepository<Inspection, Long>, JpaSpecificationExecutor<Inspection> {

    /** 설비의 최근 점검 이력(최신순) — AI 리포트 컨텍스트용 */
    List<Inspection> findByEquipmentIdOrderByStartedAtDescIdDesc(Long equipmentId, Pageable pageable);

    /** 설비의 최근 특정 유형(BM) 이력(최신순) — 재발 판단용 */
    List<Inspection> findByEquipmentIdAndTypeOrderByStartedAtDescIdDesc(
            Long equipmentId, Inspection.Type type, Pageable pageable);

    /** 설비의 특정 유형 점검 중 가장 늦은 종료 시각 — PM 이력 수정 후 스케줄 last_done_at 재계산용 (soft delete 제외) */
    @Query("SELECT MAX(i.endedAt) FROM Inspection i WHERE i.equipmentId = :equipmentId AND i.type = :type")
    Optional<Instant> findLatestEndedAt(@Param("equipmentId") Long equipmentId, @Param("type") Inspection.Type type);
}
