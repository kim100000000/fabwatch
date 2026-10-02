package com.fabwatch.inspection.repository;

import com.fabwatch.inspection.entity.Inspection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface InspectionRepository extends JpaRepository<Inspection, Long>, JpaSpecificationExecutor<Inspection> {

    /** 설비의 최근 점검 이력(최신순) — AI 리포트 컨텍스트용 */
    List<Inspection> findByEquipmentIdOrderByStartedAtDescIdDesc(Long equipmentId, Pageable pageable);

    /** 설비의 최근 특정 유형(BM) 이력(최신순) — 재발 판단용 */
    List<Inspection> findByEquipmentIdAndTypeOrderByStartedAtDescIdDesc(
            Long equipmentId, Inspection.Type type, Pageable pageable);
}
