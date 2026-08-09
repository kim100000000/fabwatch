package com.fabwatch.alarm.repository;

import com.fabwatch.alarm.entity.Alarm;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 알람 목록 필터 + 정렬 (docs/06 §6 — filter: equipmentId, status, severity, from, to / 기본 OPEN 우선 정렬).
 *
 * 정렬을 Specification 안에서 거는 이유: "OPEN 우선"은 status 컬럼의 사전순(ACK &lt; OPEN &lt; RESOLVED)과
 * 다르기 때문에 CASE 식이 필요하고, Sort로는 표현할 수 없다.
 * count 쿼리에는 ORDER BY를 걸지 않는다(일부 DB에서 오류).
 */
public final class AlarmSpecifications {

    private AlarmSpecifications() {
    }

    public static Specification<Alarm> filter(Long equipmentId, Alarm.Status status, Alarm.Severity severity,
                                              Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (equipmentId != null) {
                predicates.add(cb.equal(root.get("equipmentId"), equipmentId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), to));
            }

            Class<?> resultType = query.getResultType();
            if (resultType != Long.class && resultType != long.class) {
                Expression<Integer> priority = cb.<Integer>selectCase()
                        .when(cb.equal(root.get("status"), Alarm.Status.OPEN), 0)
                        .when(cb.equal(root.get("status"), Alarm.Status.ACK), 1)
                        .otherwise(2)
                        .as(Integer.class);
                query.orderBy(cb.asc(priority), cb.desc(root.get("occurredAt")), cb.desc(root.get("id")));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
