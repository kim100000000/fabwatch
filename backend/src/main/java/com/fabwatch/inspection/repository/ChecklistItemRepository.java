package com.fabwatch.inspection.repository;

import com.fabwatch.inspection.entity.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {

    /** 활성 항목만, seq 오름차순(null은 뒤) → id 순 */
    List<ChecklistItem> findByEquipmentIdAndActiveTrueOrderBySeqAscIdAsc(Long equipmentId);

    List<ChecklistItem> findByEquipmentIdAndIdIn(Long equipmentId, Collection<Long> ids);

    Optional<ChecklistItem> findByIdAndEquipmentId(Long id, Long equipmentId);

    /** 다음 seq 계산용 — 비활성 포함 최대값 */
    Optional<ChecklistItem> findFirstByEquipmentIdAndSeqIsNotNullOrderBySeqDesc(Long equipmentId);
}
