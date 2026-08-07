package com.fabwatch.equipment.repository;

import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.entity.EquipmentStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * 설비 목록 필터 (docs/06 §2 — filter: processId, status).
 */
public final class EquipmentSpecifications {

    private EquipmentSpecifications() {
    }

    public static Specification<Equipment> filter(Long processId, EquipmentStatus status) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (processId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("process").get("id"), processId));
            }
            if (status != null) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }
            return predicate;
        };
    }
}
