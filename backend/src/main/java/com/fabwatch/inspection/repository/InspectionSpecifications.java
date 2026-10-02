package com.fabwatch.inspection.repository;

import com.fabwatch.inspection.entity.Inspection;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 점검 이력 검색 필터 (docs/03 F-3.4 — 설비·기간·유형·교대조·작업자·NG 여부).
 * 기간은 started_at 기준, from 이상 / to 미만.
 */
public final class InspectionSpecifications {

    private InspectionSpecifications() {
    }

    public static Specification<Inspection> filter(Long equipmentId, Inspection.Type type, Inspection.Shift shift,
                                                   Long workerId, Boolean hasNg, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (equipmentId != null) {
                predicates.add(cb.equal(root.get("equipmentId"), equipmentId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (shift != null) {
                predicates.add(cb.equal(root.get("shift"), shift));
            }
            if (workerId != null) {
                predicates.add(cb.equal(root.get("workerId"), workerId));
            }
            if (hasNg != null) {
                predicates.add(cb.equal(root.get("hasNg"), hasNg));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("startedAt"), to));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
