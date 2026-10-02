package com.fabwatch.aireport.repository;

import com.fabwatch.aireport.entity.AiReport;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** AI 리포트 목록 필터 (docs/06 §7 — equipmentId, status, alarmId, inspectionId). 정렬은 Pageable(서버 고정). */
public final class AiReportSpecifications {

    private AiReportSpecifications() {
    }

    public static Specification<AiReport> filter(Long equipmentId, AiReport.Status status,
                                                 Long alarmId, Long inspectionId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (equipmentId != null) {
                predicates.add(cb.equal(root.get("equipmentId"), equipmentId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (alarmId != null) {
                predicates.add(cb.equal(root.get("alarmId"), alarmId));
            }
            if (inspectionId != null) {
                predicates.add(cb.equal(root.get("inspectionId"), inspectionId));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
